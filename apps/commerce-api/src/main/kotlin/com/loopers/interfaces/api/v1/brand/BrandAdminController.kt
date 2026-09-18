package com.loopers.interfaces.api.v1.brand

import com.loopers.application.brand.BrandAdminListRequest
import com.loopers.application.brand.BrandAdminRegisterRequest
import com.loopers.application.brand.BrandService
import com.loopers.application.brand.BrandAdminUpdateRequest
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.PageResponse
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
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api-admin/v1/brands")
class BrandAdminController(
    private val brandService: BrandService,
) : BrandAdminApiSpec {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun register(
        @RequestBody @Valid request: BrandAdminRegisterRequest,
    ): ApiResponse<BrandAdminResponse> {
        return brandService.register(request)
            .let { BrandAdminResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    /** 쿼리 문자열을 [BrandAdminListRequest]로 바로 받는다. 본문이 없는 요청의 `@RequestBody` 자리다(설계 5.17). */
    @GetMapping
    override fun getBrands(
        @ModelAttribute @Valid request: BrandAdminListRequest,
    ): ApiResponse<PageResponse<BrandAdminResponse>> {
        return brandService.findAll(request)
            .let { PageResponse.from(it, BrandAdminResponse::from) }
            .let { ApiResponse.success(it) }
    }

    @GetMapping("/{brandId}")
    override fun getBrand(
        @PathVariable("brandId") brandId: Long,
    ): ApiResponse<BrandAdminResponse> {
        return brandService.find(brandId)
            .let { BrandAdminResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @PutMapping("/{brandId}")
    override fun update(
        @PathVariable("brandId") brandId: Long,
        @RequestBody @Valid request: BrandAdminUpdateRequest,
    ): ApiResponse<BrandAdminResponse> {
        return brandService.update(brandId, request)
            .let { BrandAdminResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @DeleteMapping("/{brandId}")
    override fun delete(
        @PathVariable("brandId") brandId: Long,
    ): ApiResponse<Any> {
        brandService.delete(brandId)

        return ApiResponse.success()
    }
}
