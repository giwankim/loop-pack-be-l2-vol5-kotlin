package com.loopers.adapter.webapi.v1.brand

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.adapter.webapi.PageResponse
import com.loopers.application.brand.provided.BrandAdminListRequest
import com.loopers.application.brand.provided.BrandAdminRegisterRequest
import com.loopers.application.brand.provided.BrandAdminUpdateRequest
import com.loopers.application.brand.provided.BrandFinder
import com.loopers.application.brand.provided.BrandRegister
import com.loopers.support.stereotype.WebApiAdapter
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus

@WebApiAdapter
@RequestMapping("/api-admin/v1/brands")
class BrandAdminApi(
    private val brandFinder: BrandFinder,
    private val brandRegister: BrandRegister,
) : BrandAdminApiSpec {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun register(
        @RequestBody @Valid request: BrandAdminRegisterRequest,
    ): ApiResponse<BrandAdminResponse> {
        return brandRegister.register(request)
            .let { BrandAdminResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    /** 쿼리 문자열을 [BrandAdminListRequest]로 바로 받는다. 본문이 없는 요청의 `@RequestBody` 자리다(설계 5.17). */
    @GetMapping
    override fun getBrands(
        @ModelAttribute @Valid request: BrandAdminListRequest,
    ): ApiResponse<PageResponse<BrandAdminResponse>> {
        return brandFinder.findAll(request)
            .let { PageResponse.from(it, BrandAdminResponse::from) }
            .let { ApiResponse.success(it) }
    }

    @GetMapping("/{brandId}")
    override fun getBrand(
        @PathVariable("brandId") brandId: Long,
    ): ApiResponse<BrandAdminResponse> {
        return brandFinder.find(brandId)
            .let { BrandAdminResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @PutMapping("/{brandId}")
    override fun update(
        @PathVariable("brandId") brandId: Long,
        @RequestBody @Valid request: BrandAdminUpdateRequest,
    ): ApiResponse<BrandAdminResponse> {
        return brandRegister.update(brandId, request)
            .let { BrandAdminResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @DeleteMapping("/{brandId}")
    override fun delete(
        @PathVariable("brandId") brandId: Long,
    ): ApiResponse<Any> {
        brandRegister.delete(brandId)

        return ApiResponse.success()
    }
}
