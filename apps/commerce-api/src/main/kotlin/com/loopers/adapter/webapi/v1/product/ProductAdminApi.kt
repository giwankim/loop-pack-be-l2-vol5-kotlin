package com.loopers.adapter.webapi.v1.product

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.adapter.webapi.PageResponse
import com.loopers.application.product.provided.ProductAdminListRequest
import com.loopers.application.product.provided.ProductAdminRegisterRequest
import com.loopers.application.product.provided.ProductAdminStockUpdateRequest
import com.loopers.application.product.provided.ProductAdminUpdateRequest
import com.loopers.application.product.provided.ProductFinder
import com.loopers.application.product.provided.ProductRegister
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
@RequestMapping("/api-admin/v1/products")
class ProductAdminApi(
    private val productFinder: ProductFinder,
    private val productRegister: ProductRegister,
) : ProductAdminApiSpec {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun register(
        @RequestBody @Valid request: ProductAdminRegisterRequest,
    ): ApiResponse<ProductAdminResponse> {
        val product = productRegister.register(request)
        return ApiResponse.success(ProductAdminResponse.from(product))
    }

    /** 쿼리 문자열을 [ProductAdminListRequest]로 바로 받는다. 본문이 없는 요청의 `@RequestBody` 자리다(설계 5.17). */
    @GetMapping
    override fun getProducts(
        @ModelAttribute @Valid request: ProductAdminListRequest,
    ): ApiResponse<PageResponse<ProductAdminResponse>> {
        val products = productFinder.findAll(request)
        return ApiResponse.success(PageResponse.from(products, ProductAdminResponse::from))
    }

    @GetMapping("/{productId}")
    override fun getProduct(
        @PathVariable("productId") productId: Long,
    ): ApiResponse<ProductAdminResponse> {
        val product = productFinder.findInfo(productId)
        return ApiResponse.success(ProductAdminResponse.from(product))
    }

    @PutMapping("/{productId}")
    override fun updateProduct(
        @PathVariable("productId") productId: Long,
        @RequestBody @Valid request: ProductAdminUpdateRequest,
    ): ApiResponse<ProductAdminResponse> {
        val product = productRegister.update(productId, request)
        return ApiResponse.success(ProductAdminResponse.from(product))
    }

    @PutMapping("/{productId}/stock")
    override fun updateStock(
        @PathVariable("productId") productId: Long,
        @RequestBody @Valid request: ProductAdminStockUpdateRequest,
    ): ApiResponse<ProductAdminResponse> {
        val product = productRegister.updateStock(productId, request)
        return ApiResponse.success(ProductAdminResponse.from(product))
    }

    @DeleteMapping("/{productId}")
    override fun deleteProduct(
        @PathVariable("productId") productId: Long,
    ): ApiResponse<Any> {
        productRegister.delete(productId)
        return ApiResponse.success()
    }
}
