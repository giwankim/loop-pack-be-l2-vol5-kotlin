package com.loopers.application.brand

import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.shared.Name
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.validation.Valid
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Service
@Validated
class BrandService(private val brandRepository: BrandRepository) {
    @Transactional
    fun register(@Valid request: BrandRegisterRequest): Brand {
        checkDuplicateNames(request)

        val brand = Brand(Name(request.name))

        return brandRepository.save(brand)
    }

    @Transactional(readOnly = true)
    fun find(id: Long): Brand =
        brandRepository.findById(id) ?: throw CoreException(ErrorType.BRAND_NOT_FOUND)

    private fun checkDuplicateNames(request: BrandRegisterRequest) {
        if (brandRepository.existsByName(Name(request.name))) {
            throw CoreException(ErrorType.BRAND_NAME_DUPLICATED)
        }
    }
}
