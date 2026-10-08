package com.loopers.application.brand

import com.loopers.application.brand.provided.BrandAdminRegisterRequest
import com.loopers.application.brand.provided.BrandAdminUpdateRequest
import com.loopers.application.brand.provided.BrandValidator
import com.loopers.application.brand.required.ActiveProductChecker
import com.loopers.application.brand.required.BrandRepository
import com.loopers.domain.brand.Brand
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ValidatedApplicationService

/**
 * [BrandValidator]의 구현. 이름 중복은 자기 저장소에, 남은 상품은 브랜드가 선언한 [ActiveProductChecker]로 상품 조각에 묻는다
 * (설계 5.8, ADR 0014). Request를 받으므로 검증하는 Service다. 읽기만 하므로 `readOnly`로 열고,
 * 브랜드 쓰기의 트랜잭션 안에서는 거기에 참여한다.
 */
@ValidatedApplicationService(readOnly = true)
class BrandValidationService(
    private val brandRepository: BrandRepository,
    private val activeProductChecker: ActiveProductChecker,
) : BrandValidator {
    /** 이름은 받은 그대로 저장되므로 받은 이름으로 묻는다. */
    override fun validateForRegister(request: BrandAdminRegisterRequest) {
        checkDuplicateName(request.name)
    }

    /** 수정이 자기 행을 중복으로 보지 않도록 [brand]를 빼고 묻는다. */
    override fun validateForUpdate(brand: Brand, request: BrandAdminUpdateRequest) {
        checkDuplicateName(request.name, excludingId = brand.id)
    }

    /** 브랜드는 자기 상품을 모르고, 답은 상품 조각에만 있다(설계 5.1, 5.8). */
    override fun validateForDelete(brand: Brand) {
        if (activeProductChecker.hasActiveProducts(brand.id)) {
            throw CoreException(ErrorType.BRAND_HAS_PRODUCTS)
        }
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
