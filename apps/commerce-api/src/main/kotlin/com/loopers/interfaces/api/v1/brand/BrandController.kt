package com.loopers.interfaces.api.v1.brand

import com.loopers.application.brand.BrandService
import com.loopers.interfaces.api.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/brands")
class BrandController(
    private val brandService: BrandService,
) : BrandApiSpec {
    @GetMapping("/{brandId}")
    override fun getBrand(
        @PathVariable("brandId") brandId: Long,
    ): ApiResponse<BrandResponse> {
        return brandService.find(brandId)
            .let { BrandResponse.from(it) }
            .let { ApiResponse.success(it) }
    }
}
