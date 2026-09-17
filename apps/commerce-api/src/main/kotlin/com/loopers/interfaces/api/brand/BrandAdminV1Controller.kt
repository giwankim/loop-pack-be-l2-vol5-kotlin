package com.loopers.interfaces.api.brand

import com.loopers.application.brand.BrandService
import com.loopers.interfaces.api.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api-admin/v1/brands")
class BrandAdminV1Controller(
    private val brandService: BrandService,
) : BrandAdminV1ApiSpec {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun register(
        @RequestBody request: BrandAdminV1Dto.RegisterRequest,
    ): ApiResponse<BrandAdminV1Dto.BrandResponse> {
        return brandService.register(request.name)
            .let { BrandAdminV1Dto.BrandResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @GetMapping("/{brandId}")
    override fun getBrand(
        @PathVariable("brandId") brandId: Long,
    ): ApiResponse<BrandAdminV1Dto.BrandResponse> {
        return brandService.getBrand(brandId)
            .let { BrandAdminV1Dto.BrandResponse.from(it) }
            .let { ApiResponse.success(it) }
    }
}
