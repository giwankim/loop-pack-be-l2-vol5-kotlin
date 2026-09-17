package com.loopers.application.brand

import com.loopers.application.shared.PageRequest
import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.shared.Slice
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

    @Transactional(readOnly = true)
    fun findAll(request: PageRequest): Slice<Brand> = brandRepository.findAll(request.page, request.size)

    /**
     * 이름을 바꾼다. 거절되면 기존 이름이 그대로 남아야 하므로, 브랜드를 바꾸기 전에 중복을 본다.
     * 물어볼 이름은 저장될 이름이어야 해서 [Brand.normalizeName]으로 먼저 다듬는다(설계 5.22).
     */
    @Transactional
    fun update(id: Long, @Valid request: BrandUpdateRequest): Brand {
        val brand = find(id)
        val name = Brand.normalizeName(request.name)
        if (brandRepository.existsByNameAndIdNot(name, brand.id)) {
            throw CoreException(ErrorType.BRAND_NAME_DUPLICATED)
        }
        brand.update(name)

        return brandRepository.save(brand)
    }

    /** 삭제 시각을 찍는다. 살아 있는 상품이 남은 브랜드를 거절하는 조건은 아직 없다(#6). */
    @Transactional
    fun delete(id: Long) {
        val brand = find(id)
        brand.delete()

        brandRepository.save(brand)
    }

    private fun checkDuplicateName(brand: Brand) {
        if (brandRepository.existsByName(brand.name)) {
            throw CoreException(ErrorType.BRAND_NAME_DUPLICATED)
        }
    }
}
