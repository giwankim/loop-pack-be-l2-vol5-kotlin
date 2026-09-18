package com.loopers.interfaces.api.v1.brand

import com.loopers.application.brand.BrandRegisterRequest
import com.loopers.application.brand.BrandService
import com.loopers.application.brand.BrandUpdateRequest
import com.loopers.application.shared.PageQuery
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.PageResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
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
        @RequestBody @Valid request: BrandRegisterRequest,
    ): ApiResponse<BrandAdminResponse> {
        return brandService.register(request)
            .let { BrandAdminResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    /** 기본값은 [PageQuery] 하나가 들고 있으므로, 없는 파라미터는 null로 받아 그대로 넘긴다. */
    @GetMapping
    override fun getBrands(
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): ApiResponse<PageResponse<BrandAdminResponse>> {
        return brandService.findAll(PageQuery.of(page, size))
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
        @RequestBody @Valid request: BrandUpdateRequest,
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
