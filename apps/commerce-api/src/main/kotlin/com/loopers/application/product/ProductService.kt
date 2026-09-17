package com.loopers.application.product

import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.product.Stock
import com.loopers.domain.shared.Money
import com.loopers.domain.shared.Name
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
            name = Name(request.name),
            price = Money(request.price),
            stock = Stock(request.stock),
        )
        return ProductInfo.from(productRepository.save(product))
    }

    @Transactional(readOnly = true)
    fun find(id: Long): ProductInfo {
        val product = productRepository.findById(id) ?: throw CoreException(ErrorType.PRODUCT_NOT_FOUND)
        return ProductInfo.from(product)
    }
}
