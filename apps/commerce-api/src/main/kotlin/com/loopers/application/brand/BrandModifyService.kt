package com.loopers.application.brand

import com.loopers.application.brand.provided.BrandAdminRegisterRequest
import com.loopers.application.brand.provided.BrandAdminUpdateRequest
import com.loopers.application.brand.provided.BrandFinder
import com.loopers.application.brand.provided.BrandRegister
import com.loopers.application.brand.required.BrandRepository
import com.loopers.domain.brand.Brand
import com.loopers.domain.product.ProductRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ValidatedApplicationService

/** [BrandRegister]의 구현. 바꿀 브랜드는 [BrandFinder]로 읽어 없는 브랜드를 같은 오류로 거절한다. */
@ValidatedApplicationService
class BrandModifyService(
    private val brandFinder: BrandFinder,
    private val brandRepository: BrandRepository,
    private val productRepository: ProductRepository,
) : BrandRegister {
    /** 이름 규칙을 지나는 브랜드를 만든 뒤, 저장하기 전에 중복을 본다. 이름은 받은 그대로 저장한다. */
    override fun register(request: BrandAdminRegisterRequest): Brand {
        val brand = Brand(request.name)
        checkDuplicateName(brand.name)

        return brandRepository.save(brand)
    }

    /**
     * 이름을 바꾼다. 거절되면 기존 이름이 그대로 남아야 하므로, 브랜드를 바꾸기 전에 다른 브랜드가 그 이름을 쓰는지 본다.
     * 이름은 받은 그대로 저장되므로 받은 이름으로 묻는다.
     */
    override fun update(id: Long, request: BrandAdminUpdateRequest): Brand {
        val brand = brandFinder.find(id)
        checkDuplicateName(request.name, excludingId = brand.id)
        brand.update(request.name)

        return brandRepository.save(brand)
    }

    /**
     * 삭제 시각을 찍는다. 삭제되지 않은 상품이 하나라도 남아 있으면 거절한다. 재고가 0인 상품도 남은 상품이다.
     *
     * 이 조건은 [Brand] 안의 불변식이 아니다. 브랜드는 자기 상품을 모르고, 답은 상품 저장소에만 있다(설계 5.1, 5.8).
     * 거절되면 브랜드가 그대로 남아야 하므로 [Brand.delete] 앞에서 묻는다. 뒤에서 물으면 찍힌 삭제 시각이
     * 영속성 컨텍스트에 남아 flush 때 저장된다.
     */
    override fun delete(id: Long) {
        val brand = brandFinder.find(id)
        if (productRepository.existsByBrandId(brand.id)) {
            throw CoreException(ErrorType.BRAND_HAS_PRODUCTS)
        }
        brand.delete()

        brandRepository.save(brand)
    }

    /**
     * 삭제되지 않은 다른 브랜드가 [name]을 쓰고 있으면 거절한다. 같은지는 컬럼 collation이 정하므로
     * 대소문자나 뒤 공백만 다른 이름도 겹친 것으로 보고, 앞 공백이 다른 이름은 다른 것으로 본다(설계 5.13).
     *
     * [excludingId]는 수정이 자기 행을 중복으로 보지 않게 빼는 브랜드다. 등록에는 뺄 자기가 없어 비운다.
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
