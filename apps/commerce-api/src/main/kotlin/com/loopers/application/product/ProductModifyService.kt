package com.loopers.application.product

import com.loopers.application.brand.provided.BrandFinder
import com.loopers.application.brand.required.ProductDeleter
import com.loopers.application.product.provided.ProductAdminRegisterRequest
import com.loopers.application.product.provided.ProductAdminStockUpdateRequest
import com.loopers.application.product.provided.ProductAdminUpdateRequest
import com.loopers.application.product.provided.ProductFinder
import com.loopers.application.product.provided.ProductInfo
import com.loopers.application.product.provided.ProductRegister
import com.loopers.application.product.provided.StockDeductor
import com.loopers.application.product.required.ProductRepository
import com.loopers.domain.product.Product
import com.loopers.domain.shared.Money
import com.loopers.support.stereotype.ValidatedApplicationService

/**
 * [ProductRegister]와 [StockDeductor], 브랜드가 선언한 [ProductDeleter]의 구현. 셋 모두 상품을 바꾸므로 한 Service가 직접 구현한다.
 * 등록할 상품의 브랜드는 [BrandFinder]로 읽어 없는 브랜드를 브랜드의 오류로 거절한다.
 * 바꿀 상품은 같은 조각의 [ProductFinder.findForUpdate]로 잠가 얻으므로, 없는 상품의 거절도 조회와 같다(ADR 0014).
 * 잠금은 커밋까지 가므로 같은 상품을 바꾸는 다른 쓰기는 이 쓰기가 끝난 뒤의 행을 읽는다(ADR 0018).
 * 관리자 응답이 브랜드 이름과 좋아요 수를 합치므로 쓰기는 [ProductInfo]를 돌려준다.
 */
@ValidatedApplicationService
class ProductModifyService(
    private val brandFinder: BrandFinder,
    private val productFinder: ProductFinder,
    private val productRepository: ProductRepository,
    private val productInfoAssembler: ProductInfoAssembler,
) : ProductRegister,
    StockDeductor,
    ProductDeleter {
    /** 새 상품에는 좋아요가 없다는 불변식으로 0을 넣는다. 세어 볼 관계가 아직 없다(설계 5.7). */
    override fun register(request: ProductAdminRegisterRequest): ProductInfo {
        val brand = brandFinder.find(request.brandId)
        val product = Product(
            brand = brand,
            name = request.name,
            price = Money(request.price),
            stock = request.stock,
        )
        return ProductInfo.from(productRepository.save(product), likeCount = 0)
    }

    override fun update(id: Long, request: ProductAdminUpdateRequest): ProductInfo {
        val product = productFinder.findForUpdate(id)
        product.update(name = request.name, price = Money(request.price))
        return productInfoAssembler.toInfo(product)
    }

    override fun updateStock(id: Long, request: ProductAdminStockUpdateRequest): ProductInfo {
        val product = productFinder.findForUpdate(id)
        product.updateStock(request.quantity)
        return productInfoAssembler.toInfo(product)
    }

    /** 논리 삭제. 이미 삭제된 상품은 없는 상품이므로 다시 삭제할 수 없다. 남은 좋아요는 그대로 둔다. */
    override fun delete(id: Long) {
        productFinder.findForUpdate(id).delete()
    }

    /** 주문 확정이 부른다. 재고 수정과 달리 최종 수량이 아니라 줄일 수량을 받는다. */
    override fun deduct(productId: Long, quantity: Int) {
        productFinder.findForUpdate(productId).deductStock(quantity)
    }

    /**
     * 브랜드 삭제가 부른다. 잠가 읽은 차례대로 삭제 시각을 찍고, UPDATE는 커밋의 flush가 묶어 보낸다(ADR 0018).
     * 이미 삭제된 상품은 삭제 필터가 읽지 않으므로 그 삭제 시각에 닿지 않는다.
     */
    override fun deleteAllOfBrand(brandId: Long) {
        productRepository.findForUpdateByBrandIdOrderById(brandId).forEach { it.delete() }
    }
}
