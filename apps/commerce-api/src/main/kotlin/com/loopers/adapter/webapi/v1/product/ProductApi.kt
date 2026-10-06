package com.loopers.adapter.webapi.v1.product

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.adapter.webapi.PageResponse
import com.loopers.application.product.ProductListRequest
import com.loopers.application.product.ProductService
import com.loopers.support.stereotype.WebApiAdapter
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping

@WebApiAdapter
@RequestMapping("/api/v1/products")
class ProductApi(
    private val productService: ProductService,
) : ProductApiSpec {
    @GetMapping("/{productId}")
    override fun getProduct(
        @PathVariable("productId") productId: Long,
    ): ApiResponse<ProductResponse> {
        return productService.find(productId)
            .let { ProductResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    /** 쿼리 문자열을 [ProductListRequest]로 바로 받는다. 까닭은 관리자 목록과 같다(설계 5.17). */
    @GetMapping
    override fun getProducts(
        @ModelAttribute @Valid request: ProductListRequest,
    ): ApiResponse<PageResponse<ProductResponse>> {
        return productService.findAll(request)
            .let { PageResponse.from(it, ProductResponse::from) }
            .let { ApiResponse.success(it) }
    }
}
