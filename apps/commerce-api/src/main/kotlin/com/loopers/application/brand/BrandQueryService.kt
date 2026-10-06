package com.loopers.application.brand

import com.loopers.application.brand.provided.BrandAdminListRequest
import com.loopers.application.brand.provided.BrandFinder
import com.loopers.application.brand.required.BrandRepository
import com.loopers.domain.brand.Brand
import com.loopers.domain.shared.PageSlice
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ValidatedApplicationService
import org.springframework.transaction.annotation.Transactional

/**
 * [BrandFinder]의 구현. 관리자 목록이 Request를 받으므로 검증하는 Service다.
 * 읽기 메서드는 클래스의 `@Transactional`보다 앞서는 `@Transactional(readOnly = true)`를 단다.
 */
@ValidatedApplicationService
class BrandQueryService(
    private val brandRepository: BrandRepository,
) : BrandFinder {
    @Transactional(readOnly = true)
    override fun find(id: Long): Brand =
        brandRepository.findById(id) ?: throw CoreException(ErrorType.BRAND_NOT_FOUND)

    @Transactional(readOnly = true)
    override fun findAll(request: BrandAdminListRequest): PageSlice<Brand> =
        brandRepository.findAll(request.page, request.size)
}
