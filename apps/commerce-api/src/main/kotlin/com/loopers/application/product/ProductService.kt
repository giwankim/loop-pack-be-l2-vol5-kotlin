package com.loopers.application.product

import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.product.Stock
import com.loopers.domain.shared.Money
import com.loopers.domain.shared.Name
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ProductService(
    private val productRepository: ProductRepository,
    private val brandRepository: BrandRepository,
) {
    @Transactional
    fun register(brandId: Long, name: String, price: Long, stock: Int): ProductInfo.Admin {
        val brand = brandRepository.findById(brandId) ?: throw CoreException(ErrorType.BRAND_NOT_FOUND)
        val product = Product(brand = brand, name = Name(name), price = Money(price), stock = Stock(stock))
        return ProductInfo.Admin.from(productRepository.save(product))
    }

    @Transactional(readOnly = true)
    fun getAdminProduct(id: Long): ProductInfo.Admin {
        val product = productRepository.findById(id) ?: throw CoreException(ErrorType.PRODUCT_NOT_FOUND)
        return ProductInfo.Admin.from(product)
    }
}
