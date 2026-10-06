package com.loopers.application.product

import com.loopers.application.brand.provided.BrandFinder
import com.loopers.application.product.provided.ProductAdminRegisterRequest
import com.loopers.application.product.provided.ProductAdminStockUpdateRequest
import com.loopers.application.product.provided.ProductAdminUpdateRequest
import com.loopers.application.product.provided.ProductInfo
import com.loopers.application.product.provided.ProductRegister
import com.loopers.application.product.provided.StockDeductor
import com.loopers.application.product.required.ProductRepository
import com.loopers.domain.product.Product
import com.loopers.domain.product.Stock
import com.loopers.domain.shared.Money
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ValidatedApplicationService

/**
 * [ProductRegister]와 [StockDeductor]의 구현. 등록할 상품의 브랜드는 [BrandFinder]로 읽어 없는 브랜드를 브랜드의 오류로 거절한다.
 * 바꿀 상품은 저장소에서 읽는다. [com.loopers.application.product.provided.ProductFinder]는 엔티티가 아니라
 * [ProductInfo]를 돌려주기 때문이다.
 */
@ValidatedApplicationService
class ProductModifyService(
    private val brandFinder: BrandFinder,
    private val productRepository: ProductRepository,
    private val productInfoAssembler: ProductInfoAssembler,
) : ProductRegister,
    StockDeductor {
    /** 새 상품에는 좋아요가 없다는 불변식으로 0을 넣는다. 세어 볼 관계가 아직 없다(설계 5.7). */
    override fun register(request: ProductAdminRegisterRequest): ProductInfo {
        val brand = brandFinder.find(request.brandId)
        val product = Product(
            brand = brand,
            name = request.name,
            price = Money(request.price),
            stock = Stock(request.stock),
        )
        return ProductInfo.from(productRepository.save(product), likeCount = 0)
    }

    override fun update(id: Long, request: ProductAdminUpdateRequest): ProductInfo {
        val product = findOrThrow(id)
        product.update(name = request.name, price = Money(request.price))
        return productInfoAssembler.toInfo(product)
    }

    override fun updateStock(id: Long, request: ProductAdminStockUpdateRequest): ProductInfo {
        val product = findOrThrow(id)
        product.updateStock(request.quantity)
        return productInfoAssembler.toInfo(product)
    }

    /** 논리 삭제. 이미 삭제된 상품은 없는 상품이므로 다시 삭제할 수 없다. 남은 좋아요는 그대로 둔다. */
    override fun delete(id: Long) {
        findOrThrow(id).delete()
    }

    /** 주문 확정이 부른다. 재고 수정과 달리 최종 수량이 아니라 줄일 수량을 받는다. */
    override fun deduct(productId: Long, quantity: Int) {
        findOrThrow(productId).deductStock(quantity)
    }

    /** 삭제된 상품은 없는 상품이므로 저장소가 이미 걸러 주고, 없으면 여기서 거절한다. */
    private fun findOrThrow(id: Long): Product =
        productRepository.findById(id) ?: throw CoreException(ErrorType.PRODUCT_NOT_FOUND)
}
