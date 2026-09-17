package com.loopers.application.brand

import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BrandService(
    private val brandRepository: BrandRepository,
) {
    @Transactional
    fun register(name: String): Brand {
        val brand = Brand(name)
        if (brandRepository.existsByName(brand.name)) {
            throw CoreException(ErrorType.BRAND_NAME_DUPLICATED)
        }
        return brandRepository.save(brand)
    }

    @Transactional(readOnly = true)
    fun getBrand(id: Long): Brand =
        brandRepository.find(id) ?: throw CoreException(ErrorType.BRAND_NOT_FOUND)
}
