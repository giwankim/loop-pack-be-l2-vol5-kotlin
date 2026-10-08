package com.loopers.application.brand

import com.loopers.application.brand.provided.BrandAdminRegisterRequest
import com.loopers.application.brand.provided.BrandAdminUpdateRequest
import com.loopers.application.brand.provided.BrandFinder
import com.loopers.application.brand.provided.BrandRegister
import com.loopers.application.brand.provided.BrandValidator
import com.loopers.application.brand.required.BrandRepository
import com.loopers.domain.brand.Brand
import com.loopers.support.stereotype.ValidatedApplicationService

/**
 * [BrandRegister]의 구현. 바꿀 브랜드는 [BrandFinder]로 읽어 없는 브랜드를 같은 오류로 거절한다.
 * 저장소나 상품 조각에 물어야 하는 사전 조건은 [BrandValidator]가 보고, 여기에는 단계의 차례만 있다(ADR 0014).
 */
@ValidatedApplicationService
class BrandModifyService(
    private val brandFinder: BrandFinder,
    private val brandRepository: BrandRepository,
    private val brandValidator: BrandValidator,
) : BrandRegister {
    /** 이름 규칙을 지나는 브랜드를 만든 뒤, 저장하기 전에 중복을 본다. 이름은 받은 그대로 저장한다. */
    override fun register(request: BrandAdminRegisterRequest): Brand {
        val brand = Brand(request.name)
        brandValidator.validateForRegister(request)

        return brandRepository.save(brand)
    }

    /** 이름을 바꾼다. 거절되면 기존 이름이 그대로 남아야 하므로, 브랜드를 바꾸기 전에 다른 브랜드가 그 이름을 쓰는지 본다. */
    override fun update(id: Long, request: BrandAdminUpdateRequest): Brand {
        val brand = brandFinder.find(id)
        brandValidator.validateForUpdate(brand, request)
        brand.update(request.name)

        return brandRepository.save(brand)
    }

    /**
     * 삭제 시각을 찍는다. 삭제되지 않은 상품이 하나라도 남아 있으면 거절한다.
     *
     * 이 조건은 [Brand] 안의 불변식이 아니라 [BrandValidator]가 상품 조각에 묻는다(설계 5.1, 5.8).
     * 거절되면 브랜드가 그대로 남아야 하므로 [Brand.delete] 앞에서 묻는다. 뒤에서 물으면 찍힌 삭제 시각이
     * 영속성 컨텍스트에 남아 flush 때 저장된다.
     */
    override fun delete(id: Long) {
        val brand = brandFinder.find(id)
        brandValidator.validateForDelete(brand)
        brand.delete()

        brandRepository.save(brand)
    }
}
