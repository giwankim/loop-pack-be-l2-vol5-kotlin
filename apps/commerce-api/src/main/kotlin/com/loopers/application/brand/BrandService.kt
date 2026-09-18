package com.loopers.application.brand

import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.shared.PageSlice
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.validation.Valid
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Service
@Validated
class BrandService(
    private val brandRepository: BrandRepository,
    private val productRepository: ProductRepository,
) {
    /** 브랜드를 먼저 만들어 이름을 정리한 뒤, 정리된 이름으로 중복을 본다. 입력 그대로 조회하면 앞뒤 공백만 다른 이름이 중복을 빠져나간다. */
    @Transactional
    fun register(@Valid request: BrandAdminRegisterRequest): Brand {
        val brand = Brand(request.name)
        checkDuplicateName(brand.name)

        return brandRepository.save(brand)
    }

    @Transactional(readOnly = true)
    fun find(id: Long): Brand =
        brandRepository.findById(id) ?: throw CoreException(ErrorType.BRAND_NOT_FOUND)

    @Transactional(readOnly = true)
    fun findAll(@Valid request: BrandAdminListRequest): PageSlice<Brand> = brandRepository.findAll(request.page, request.size)

    /**
     * 이름을 바꾼다. 거절되면 기존 이름이 그대로 남아야 하므로, 브랜드를 바꾸기 전에 중복을 본다.
     * 물어볼 이름은 저장될 이름이어야 해서 [Brand.normalizeName]으로 먼저 다듬는다(설계 5.23).
     */
    @Transactional
    fun update(id: Long, @Valid request: BrandAdminUpdateRequest): Brand {
        val brand = find(id)
        val name = Brand.normalizeName(request.name)
        checkDuplicateName(name, excludingId = brand.id)
        brand.update(name)

        return brandRepository.save(brand)
    }

    /**
     * 삭제 시각을 찍는다. 삭제되지 않은 상품이 하나라도 남아 있으면 거절한다. 재고가 0인 상품도 남은 상품이다.
     *
     * 이 조건은 [Brand] 안의 불변식이 아니다. 브랜드는 자기 상품을 모르고, 답은 상품 저장소에만 있다(설계 5.1, 5.8).
     * 거절되면 브랜드가 그대로 남아야 하므로 [Brand.delete] 앞에서 묻는다. 뒤에서 물으면 찍힌 삭제 시각이
     * 영속성 컨텍스트에 남아 flush 때 저장된다.
     */
    @Transactional
    fun delete(id: Long) {
        val brand = find(id)
        if (productRepository.existsByBrandId(brand.id)) {
            throw CoreException(ErrorType.BRAND_HAS_PRODUCTS)
        }
        brand.delete()

        brandRepository.save(brand)
    }

    /**
     * 삭제되지 않은 다른 브랜드가 [name]을 쓰고 있으면 거절한다. 같은지는 컬럼 collation이 정하므로
     * 대소문자만 다른 이름도 겹친 것으로 본다(설계 5.13).
     *
     * [excludingId]는 수정이 자기 행을 중복으로 보지 않게 빼는 브랜드다. 등록에는 뺄 자기가 없어 비운다(설계 5.23).
     */
    private fun checkDuplicateName(name: String, excludingId: Long? = null) {
        val taken =
            if (excludingId == null) {
                brandRepository.existsByName(name)
            } else {
                brandRepository.existsByNameAndIdNot(name, excludingId)
            }
        if (taken) {
            throw CoreException(ErrorType.BRAND_NAME_DUPLICATED)
        }
    }
}
