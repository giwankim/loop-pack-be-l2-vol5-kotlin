package com.loopers.application.brand

import com.loopers.domain.brand.Brand
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 브랜드 수정 입력. 바꿀 수 있는 것은 이름뿐이다.
 * 등록과 같은 이름 규칙을 쓰지만, 한쪽의 규칙이 바뀌어도 다른 쪽이 따라가지 않도록 [BrandAdminRegisterRequest]와 타입을 나눈다.
 */
data class BrandAdminUpdateRequest(
    @field:NotBlank(message = "브랜드 이름은 공백일 수 없습니다.")
    @field:Size(max = Brand.NAME_MAX_LENGTH, message = "브랜드 이름은 {max}자 이하여야 합니다.")
    val name: String,
)
