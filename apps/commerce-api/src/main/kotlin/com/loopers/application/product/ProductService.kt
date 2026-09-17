package com.loopers.application.product

import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.product.Stock
import com.loopers.domain.shared.Money
import com.loopers.domain.shared.Slice
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.validation.Valid
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Service
@Validated
class ProductService(
    private val productRepository: ProductRepository,
    private val brandRepository: BrandRepository,
) {
    @Transactional
    fun register(@Valid request: ProductRegisterRequest): ProductInfo {
        val brand = brandRepository.findById(request.brandId) ?: throw CoreException(ErrorType.BRAND_NOT_FOUND)
        val product = Product(
            brand = brand,
            name = request.name,
            price = Money(request.price),
            stock = Stock(request.stock),
        )
        return ProductInfo.from(productRepository.save(product))
    }

    @Transactional(readOnly = true)
    fun find(id: Long): ProductInfo {
        return ProductInfo.from(product(id))
    }

    @Transactional
    fun update(id: Long, @Valid request: ProductUpdateRequest): ProductInfo {
        val product = product(id)
        product.update(name = request.name, price = Money(request.price))
        return ProductInfo.from(product)
    }

    @Transactional
    fun updateStock(id: Long, @Valid request: ProductStockUpdateRequest): ProductInfo {
        val product = product(id)
        product.updateStock(request.quantity)
        return ProductInfo.from(product)
    }

    /**
     * 늦게 등록된 상품이 앞서는 한 조각. 항목마다 브랜드를 읽으므로 [ProductInfo]로 옮기는 일은 트랜잭션 안에서 끝난다.
     */
    @Transactional(readOnly = true)
    fun findAll(@Valid request: ProductListRequest): Slice<ProductInfo> =
        productRepository
            .findAll(brandId = request.brandId, page = request.page, size = request.size)
            .map(ProductInfo::from)

    /** 논리 삭제. 이미 삭제된 상품은 없는 상품이므로 다시 삭제할 수 없다. 남은 좋아요는 그대로 둔다. */
    @Transactional
    fun delete(id: Long) {
        product(id).delete()
    }

    /** 삭제된 상품은 없는 상품이므로 저장소가 이미 걸러 준다. */
    private fun product(id: Long): Product =
        productRepository.findById(id) ?: throw CoreException(ErrorType.PRODUCT_NOT_FOUND)
}
