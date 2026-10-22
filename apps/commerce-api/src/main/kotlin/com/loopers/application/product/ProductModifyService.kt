package com.loopers.application.product

import com.loopers.application.brand.provided.BrandFinder
import com.loopers.application.brand.required.ProductDeleter
import com.loopers.application.product.provided.ProductAdminRegisterRequest
import com.loopers.application.product.provided.ProductAdminStockUpdateRequest
import com.loopers.application.product.provided.ProductAdminUpdateRequest
import com.loopers.application.product.provided.ProductFinder
import com.loopers.application.product.provided.ProductInfo
import com.loopers.application.product.provided.ProductListChangeMarker
import com.loopers.application.product.provided.ProductRegister
import com.loopers.application.product.provided.StockDeductor
import com.loopers.application.product.provided.StockRestorer
import com.loopers.application.product.required.ProductRepository
import com.loopers.domain.product.Product
import com.loopers.domain.shared.Money
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ValidatedApplicationService

/**
 * [ProductRegister]와 [StockDeductor], [StockRestorer], 브랜드가 선언한 [ProductDeleter]의 구현. 넷 모두 상품을 바꾸므로 한 Service가 직접 구현한다.
 * 등록할 상품의 브랜드는 [BrandFinder]로 읽어 없는 브랜드를 브랜드의 오류로 거절한다.
 * 바꿀 상품은 같은 조각의 [ProductFinder.findForUpdate]로 잠가 얻으므로, 없는 상품의 거절도 조회와 같다(ADR 0014).
 * 잠금은 커밋까지 가므로 같은 상품을 바꾸는 다른 쓰기는 이 쓰기가 끝난 뒤의 행을 읽는다(ADR 0018).
 * 관리자 응답이 브랜드 이름과 좋아요 수를 합치므로 쓰기는 [ProductInfo]를 돌려준다.
 * 고객 목록을 바꾸는 쓰기는 [ProductListChangeMarker]로 표시해, 커밋 바로 앞에 상품 목록 revision을 올린다(ADR 0020).
 */
@ValidatedApplicationService
class ProductModifyService(
    private val brandFinder: BrandFinder,
    private val productFinder: ProductFinder,
    private val productRepository: ProductRepository,
    private val productInfoAssembler: ProductInfoAssembler,
    private val productListChangeMarker: ProductListChangeMarker,
) : ProductRegister,
    StockDeductor,
    StockRestorer,
    ProductDeleter {
    /**
     * 브랜드를 공유 잠금으로 읽어, 커밋할 때까지 브랜드의 삭제가 끼어들지 못하게 한다. 외래 키 검사의 공유 잠금으로는 모자란다.
     * 논리 삭제는 브랜드 행을 남기므로 삭제가 커밋된 뒤에도 그 검사가 통과한다(ADR 0018).
     * 새 상품에는 좋아요가 없다는 불변식으로 0을 넣는다. 세어 볼 관계가 아직 없다(설계 5.7).
     * 새 상품은 전체 목록과 그 브랜드의 목록에 나타나므로 언제나 표시한다.
     */
    override fun register(request: ProductAdminRegisterRequest): ProductInfo {
        val brand = brandFinder.findForShare(request.brandId)

        val product = productRepository.save(
            Product(
                brand = brand,
                name = request.name,
                price = Money(request.price),
                stock = request.stock,
            ),
        )
        productListChangeMarker.markChanged()

        return ProductInfo.from(product, 0)
    }

    /** 이름과 가격은 모두 고객 목록에 보이므로 값이 그대로여도 표시한다. */
    override fun update(productId: Long, request: ProductAdminUpdateRequest): ProductInfo {
        val product = productFinder.findForUpdate(productId)

        product.update(name = request.name, price = Money(request.price))
        productListChangeMarker.markChanged()

        return productInfoAssembler.toInfo(productRepository.save(product))
    }

    /** 고객은 재고를 품절 여부로만 보므로 품절이 뒤집힐 때만 표시한다. 잠가 읽은 행의 앞뒤를 견주므로 따로 잠글 것이 없다. */
    override fun updateStock(productId: Long, request: ProductAdminStockUpdateRequest): ProductInfo {
        val product = productFinder.findForUpdate(productId)
        val wasSoldOut = product.isSoldOut()

        product.updateStock(request.quantity)
        if (product.isSoldOut() != wasSoldOut) {
            productListChangeMarker.markChanged()
        }

        return productInfoAssembler.toInfo(productRepository.save(product))
    }

    /**
     * 논리 삭제. 이미 삭제된 상품은 없는 상품이므로 다시 삭제할 수 없다. 남은 좋아요는 그대로 둔다.
     * 삭제된 상품은 목록에서 사라지므로 언제나 표시한다.
     */
    override fun delete(productId: Long) {
        val product = productFinder.findForUpdate(productId)

        product.delete()
        productListChangeMarker.markChanged()

        productRepository.save(product)
    }

    /**
     * 주문 확정이 부른다. 재고 수정과 달리 최종 수량이 아니라 줄일 수량을 받는다.
     * 잠가 읽기, 없는 상품의 거절, 차감을 차례로 나눈다. 모두 잠가 읽은 뒤에 거절하므로 없는 상품이 재고 부족보다 앞선다(포인트·주문 설계 15).
     *
     * 어느 상품이든 품절이 되면 목록을 바꿨다고 표시한다. 차감은 재고를 줄이기만 하므로 품절이 풀리는 일은 없다.
     * 표시는 주문이 아니라 재고를 바꾸는 이 조각이 한다. 올리기는 커밋 앞이라 확정이 잡는 잠금 가운데 맨 뒤에 온다(ADR 0020).
     */
    override fun deduct(quantities: Map<Long, Int>) {
        val locked = lockAll(quantities.keys)
        val products = locked.map { product -> product ?: throw CoreException(ErrorType.PRODUCT_NOT_FOUND) }
        val wereSoldOut = products.map { product -> product.isSoldOut() }

        products.forEach { product -> product.deductStock(quantities.getValue(product.id)) }
        if (products.map { product -> product.isSoldOut() } != wereSoldOut) {
            productListChangeMarker.markChanged()
        }

        productRepository.saveAll(products)
    }

    /**
     * 결제에 실패한 주문의 TX B가 부른다. [deduct]처럼 모두 잠가 읽은 뒤에 바꾸되, 없거나 삭제된 상품은 거절하지 않고 건너뛴다(ADR 0026 규칙 4).
     *
     * 어느 상품이든 품절이 풀리면 목록을 바꿨다고 표시한다. 상품 목록을 바꾸는 아홉째 쓰기다(ADR 0026 규칙 6). 돌려주기는 재고를 늘리기만 하므로
     * 품절이 되는 일은 없다. 올리기는 커밋 앞이라 TX B의 주문과 상품 잠금보다 뒤에 온다(ADR 0020 규칙 3).
     */
    override fun restore(quantities: Map<Long, Int>) {
        val products = lockAll(quantities.keys).filterNotNull()
        val wereSoldOut = products.map { product -> product.isSoldOut() }

        products.forEach { product -> product.restoreStock(quantities.getValue(product.id)) }
        if (products.map { product -> product.isSoldOut() } != wereSoldOut) {
            productListChangeMarker.markChanged()
        }

        productRepository.saveAll(products)
    }

    /**
     * 브랜드 삭제가 부른다. 잠가 읽은 차례대로 삭제 시각을 찍어 저장한다. UPDATE는 커밋의 flush가 묶어 보낸다(ADR 0018).
     * 이미 삭제된 상품은 삭제 필터가 읽지 않으므로 그 삭제 시각에 닿지 않는다.
     * 지울 상품이 없어도 표시한다. 그 분기는 값어치가 없다. 올리기는 커밋 앞에서 상품과 브랜드의 UPDATE 뒤에 나간다(ADR 0020).
     */
    override fun deleteAllOfBrand(brandId: Long) {
        val products = productRepository.findForUpdateByBrandIdOrderById(brandId)

        products.forEach { it.delete() }
        productListChangeMarker.markChanged()

        productRepository.saveAll(products)
    }

    /**
     * 차감하거나 돌려줄 상품 행을 id 오름차순으로 잠가 읽는다. 없거나 삭제된 상품은 null로 남기고, 거절할지 건너뛸지는 [deduct]와 [restore]가 정한다.
     * 트랜잭션이 이 상품들을 처음 읽는 자리라서 잠근다(ADR 0018 규칙 2). 받은 키의 차례를 믿지 않고 여기서 정렬하므로,
     * 여러 상품을 잠그는 브랜드 삭제의 연쇄와 같은 차례가 된다(규칙 3).
     */
    private fun lockAll(productIds: Set<Long>): List<Product?> {
        return productIds.sorted().map { productId -> productFinder.findForUpdateOrNull(productId) }
    }
}
