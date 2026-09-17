package com.loopers.application.product

import com.loopers.domain.product.Product
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 상품 등록 입력. interfaces가 HTTP 본문을 이 모양으로 바로 받아 [ProductService.register]에 넘긴다.
 * 제약 애노테이션은 Controller(`@Valid`)와 Service(`@Validated`)가 같은 규칙으로 먼저 거른다.
 * 값 객체와 엔티티의 검사는 그대로 남아 있어 규칙이 두 곳에 적힌다(설계 5.18).
 */
data class ProductRegisterRequest(
    val brandId: Long,
    @field:NotBlank(message = "상품 이름은 공백일 수 없습니다.")
    @field:Size(max = Product.NAME_MAX_LENGTH, message = "상품 이름은 {max}자 이하여야 합니다.")
    val name: String,
    @field:Min(Product.MIN_PRICE_AMOUNT, message = "상품 가격은 {value}원 이상이어야 합니다.")
    @field:Max(Product.MAX_PRICE_AMOUNT, message = "상품 가격은 {value}원 이하여야 합니다.")
    val price: Long,
    @field:Min(0, message = "재고는 0 이상이어야 합니다.")
    val stock: Int,
)
