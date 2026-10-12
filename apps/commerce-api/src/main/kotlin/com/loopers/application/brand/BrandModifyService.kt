package com.loopers.application.brand

import com.loopers.application.brand.provided.BrandAdminRegisterRequest
import com.loopers.application.brand.provided.BrandAdminUpdateRequest
import com.loopers.application.brand.provided.BrandFinder
import com.loopers.application.brand.provided.BrandRegister
import com.loopers.application.brand.provided.BrandValidator
import com.loopers.application.brand.required.BrandRepository
import com.loopers.application.brand.required.ProductDeleter
import com.loopers.domain.brand.Brand
import com.loopers.support.stereotype.ValidatedApplicationService

/**
 * [BrandRegister]의 구현. 바꿀 브랜드는 [BrandFinder]로 읽어 없는 브랜드를 같은 오류로 거절한다.
 * 저장소에 물어야 하는 사전 조건은 [BrandValidator]가 보고, 상품의 삭제는 브랜드가 선언한 [ProductDeleter]에 시킨다.
 * 여기에는 단계의 차례만 있다(ADR 0014, 0017).
 */
@ValidatedApplicationService
class BrandModifyService(
    private val brandFinder: BrandFinder,
    private val brandRepository: BrandRepository,
    private val brandValidator: BrandValidator,
    private val productDeleter: ProductDeleter,
) : BrandRegister {
    /** 이름 규칙을 지나는 브랜드를 만든 뒤, 저장하기 전에 중복을 본다. 이름은 받은 그대로 저장한다. */
    override fun register(request: BrandAdminRegisterRequest): Brand {
        val brand = Brand(request.name)
        brandValidator.validateForRegister(request)

        return brandRepository.save(brand)
    }

    /**
     * 이름을 바꾼다. 거절되면 기존 이름이 그대로 남아야 하므로, 브랜드를 바꾸기 전에 다른 브랜드가 그 이름을 쓰는지 본다.
     * 브랜드는 잠가 읽는다. 행 전체를 쓰므로, 잠그지 않으면 겹친 삭제가 커밋한 삭제 시각을 옛 값으로 덮어 브랜드가 되살아난다(ADR 0018).
     */
    override fun update(id: Long, request: BrandAdminUpdateRequest): Brand {
        val brand = brandFinder.findForUpdate(id)
        brandValidator.validateForUpdate(brand, request)
        brand.update(request.name)

        return brandRepository.save(brand)
    }

    /**
     * 브랜드를 잠가 읽고, 그 브랜드의 삭제되지 않은 상품을 [ProductDeleter]로 삭제한 뒤, 브랜드에 삭제 시각을 찍는다.
     * 한 트랜잭션이 브랜드와 상품을 함께 바꾸는, 주문 확정에 이은 두 번째 예외다(ADR 0017). 그래서 어느 단계가 실패해도 브랜드와
     * 상품은 함께 되돌아간다. 잠금은 브랜드 → 상품(id 오름차순)의 차례로 건다(ADR 0018).
     */
    override fun delete(id: Long) {
        val brand = brandFinder.findForUpdate(id)
        productDeleter.deleteAllOfBrand(brand.id)
        brand.delete()

        brandRepository.save(brand)
    }
}
