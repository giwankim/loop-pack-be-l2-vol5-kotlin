package com.loopers.interfaces.api.v1.brand

import com.loopers.application.brand.BrandRegisterRequest
import com.loopers.application.brand.BrandService
import com.loopers.interfaces.api.ApiResponse
import jakarta.validation.Valid
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
        @RequestBody @Valid request: BrandRegisterRequest,
    ): ApiResponse<BrandAdminResponse> {
        return brandService.register(request)
            .let { BrandAdminResponse.from(it) }
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
}
