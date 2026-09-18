package com.loopers.interfaces.api.v1.product

import com.loopers.application.product.ProductListRequest
import com.loopers.application.product.ProductRegisterRequest
import com.loopers.application.product.ProductService
import com.loopers.application.product.ProductStockUpdateRequest
import com.loopers.application.product.ProductUpdateRequest
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.SliceResponse
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
@RequestMapping("/api-admin/v1/products")
class ProductAdminController(
    private val productService: ProductService,
) : ProductAdminApiSpec {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun register(
        @RequestBody @Valid request: ProductRegisterRequest,
    ): ApiResponse<ProductAdminResponse> {
        return productService.register(request)
            .let { ProductAdminResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    /** 쿼리 문자열을 [ProductListRequest]로 바로 받는다. 본문이 없는 요청의 `@RequestBody` 자리다(설계 5.17). */
    @GetMapping
    override fun getProducts(
        @ModelAttribute @Valid request: ProductListRequest,
    ): ApiResponse<SliceResponse<ProductAdminResponse>> {
        return productService.findAll(request)
            .map(ProductAdminResponse::from)
            .let { SliceResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @GetMapping("/{productId}")
    override fun getProduct(
        @PathVariable("productId") productId: Long,
    ): ApiResponse<ProductAdminResponse> {
        return productService.find(productId)
            .let { ProductAdminResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @PutMapping("/{productId}")
    override fun updateProduct(
        @PathVariable("productId") productId: Long,
        @RequestBody @Valid request: ProductUpdateRequest,
    ): ApiResponse<ProductAdminResponse> {
        return productService.update(productId, request)
            .let { ProductAdminResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @PutMapping("/{productId}/stock")
    override fun updateStock(
        @PathVariable("productId") productId: Long,
        @RequestBody @Valid request: ProductStockUpdateRequest,
    ): ApiResponse<ProductAdminResponse> {
        return productService.updateStock(productId, request)
            .let { ProductAdminResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @DeleteMapping("/{productId}")
    override fun deleteProduct(
        @PathVariable("productId") productId: Long,
    ): ApiResponse<Any> {
        productService.delete(productId)
        return ApiResponse.success()
    }
}
