package com.loopers.application.product

import com.loopers.domain.product.Product
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 상품 수정 입력. 브랜드는 여기에 없다. 상품이 속한 브랜드는 바뀌지 않는다.
 * 제약이 붙는 자리와 까닭은 [ProductAdminRegisterRequest]와 같다(설계 5.18).
 */
data class ProductAdminUpdateRequest(
    @field:NotBlank(message = "상품 이름은 공백일 수 없습니다.")
    @field:Size(max = Product.NAME_MAX_LENGTH, message = "상품 이름은 {max}자 이하여야 합니다.")
    val name: String,
    @field:Min(Product.MIN_PRICE_AMOUNT, message = "상품 가격은 {value}원 이상이어야 합니다.")
    @field:Max(Product.MAX_PRICE_AMOUNT, message = "상품 가격은 {value}원 이하여야 합니다.")
    val price: Long,
)
