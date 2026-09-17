package com.loopers.application.brand

import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class BrandService(
    private val brandRepository: BrandRepository,
) {
    @Transactional
    fun register(name: String): BrandInfo {
        val brand = Brand(name)
        if (brandRepository.existsByName(brand.name)) {
            throw CoreException(
                errorType = ErrorType.BRAND_NAME_DUPLICATED,
                customMessage = "[name = ${brand.name}] 같은 이름의 브랜드가 이미 있습니다.",
            )
        }
        return brandRepository.save(brand)
            .let { BrandInfo.from(it) }
    }

    @Transactional(readOnly = true)
    fun getBrand(id: Long): BrandInfo {
        val brand = brandRepository.find(id)
            ?: throw CoreException(errorType = ErrorType.BRAND_NOT_FOUND, customMessage = "[id = $id] 브랜드를 찾을 수 없습니다.")
        return BrandInfo.from(brand)
    }
}
