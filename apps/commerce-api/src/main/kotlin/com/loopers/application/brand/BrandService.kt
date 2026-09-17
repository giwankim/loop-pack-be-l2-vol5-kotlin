package com.loopers.application.brand

import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.validation.Valid
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Service
@Validated
class BrandService(private val brandRepository: BrandRepository) {
    /** 브랜드를 먼저 만들어 이름을 정리한 뒤, 정리된 이름으로 중복을 본다. 입력 그대로 조회하면 앞뒤 공백만 다른 이름이 중복을 빠져나간다. */
    @Transactional
    fun register(@Valid request: BrandRegisterRequest): Brand {
        val brand = Brand(request.name)
        checkDuplicateName(brand)

        return brandRepository.save(brand)
    }

    @Transactional(readOnly = true)
    fun find(id: Long): Brand =
        brandRepository.findById(id) ?: throw CoreException(ErrorType.BRAND_NOT_FOUND)

    private fun checkDuplicateName(brand: Brand) {
        if (brandRepository.existsByName(brand.name)) {
            throw CoreException(ErrorType.BRAND_NAME_DUPLICATED)
        }
    }
}
