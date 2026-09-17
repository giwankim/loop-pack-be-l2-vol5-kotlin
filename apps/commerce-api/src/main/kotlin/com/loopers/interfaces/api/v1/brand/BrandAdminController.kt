package com.loopers.interfaces.api.v1.brand

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
class BrandAdminController(
    private val brandService: BrandService,
) : BrandAdminApiSpec {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun register(
        @RequestBody request: BrandAdminDto.RegisterRequest,
    ): ApiResponse<BrandAdminDto.BrandResponse> {
        return brandService.register(request.name)
            .let { BrandAdminDto.BrandResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @GetMapping("/{brandId}")
    override fun getBrand(
        @PathVariable("brandId") brandId: Long,
    ): ApiResponse<BrandAdminDto.BrandResponse> {
        return brandService.getBrand(brandId)
            .let { BrandAdminDto.BrandResponse.from(it) }
            .let { ApiResponse.success(it) }
    }
}
