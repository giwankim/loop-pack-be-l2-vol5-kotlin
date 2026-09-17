package com.loopers.interfaces.api.v1.product

import com.loopers.application.product.ProductRegisterRequest
import com.loopers.application.product.ProductService
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
@RequestMapping("/api-admin/v1/products")
class ProductAdminController(
    private val productService: ProductService,
) : ProductAdminApiSpec {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun register(
        @RequestBody @Valid request: ProductRegisterRequest,
    ): ApiResponse<ProductAdminDto.ProductResponse> {
        return productService.register(request)
            .let { ProductAdminDto.ProductResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @GetMapping("/{productId}")
    override fun getProduct(
        @PathVariable("productId") productId: Long,
    ): ApiResponse<ProductAdminDto.ProductResponse> {
        return productService.find(productId)
            .let { ProductAdminDto.ProductResponse.from(it) }
            .let { ApiResponse.success(it) }
    }
}
