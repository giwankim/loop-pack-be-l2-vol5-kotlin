package com.loopers.adapter.webapi.v1.brand

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.application.brand.provided.BrandFinder
import com.loopers.support.stereotype.WebApiAdapter
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping

@WebApiAdapter
@RequestMapping("/api/v1/brands")
class BrandApi(
    private val brandFinder: BrandFinder,
) : BrandApiSpec {
    @GetMapping("/{brandId}")
    override fun getBrand(
        @PathVariable("brandId") brandId: Long,
    ): ApiResponse<BrandResponse> {
        val brand = brandFinder.find(brandId)

        return ApiResponse.success(BrandResponse.from(brand))
    }
}
