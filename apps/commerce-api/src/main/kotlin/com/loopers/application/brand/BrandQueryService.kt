package com.loopers.application.brand

import com.loopers.application.brand.provided.BrandAdminListRequest
import com.loopers.application.brand.provided.BrandFinder
import com.loopers.application.brand.required.BrandRepository
import com.loopers.domain.brand.Brand
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ValidatedApplicationService
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Slice

/** [BrandFinder]의 구현. 관리자 목록이 Request를 받으므로 검증하는 Service다. */
@ValidatedApplicationService(readOnly = true)
class BrandQueryService(
    private val brandRepository: BrandRepository,
) : BrandFinder {
    override fun find(id: Long): Brand {
        return brandRepository.findById(id) ?: throw CoreException(ErrorType.BRAND_NOT_FOUND)
    }

    /** 클래스의 `readOnly`는 부르는 쪽의 쓰기 트랜잭션에 참여할 때 걸리지 않는다. */
    override fun findForUpdate(id: Long): Brand {
        return brandRepository.findForUpdateById(id) ?: throw CoreException(ErrorType.BRAND_NOT_FOUND)
    }

    /** 클래스의 `readOnly`는 부르는 쪽의 쓰기 트랜잭션에 참여할 때 걸리지 않는다. */
    override fun findForShare(id: Long): Brand {
        return brandRepository.findForShareById(id) ?: throw CoreException(ErrorType.BRAND_NOT_FOUND)
    }

    override fun findAll(request: BrandAdminListRequest): Slice<Brand> {
        return brandRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(request.page, request.size))
    }
}
