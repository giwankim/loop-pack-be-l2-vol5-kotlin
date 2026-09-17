package com.loopers.application.brand

import com.loopers.domain.brand.Brand
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 브랜드 등록 입력. interfaces가 HTTP 본문을 이 모양으로 바로 받아 [BrandService.register]에 넘긴다.
 * 제약 애노테이션은 Controller(`@Valid`)와 Service(`@Validated`)가 같은 규칙으로 먼저 거른다.
 * 값 객체와 엔티티의 검사는 그대로 남아 있어 규칙이 두 곳에 적힌다(설계 5.18).
 */
data class BrandRegisterRequest(
    @field:NotBlank(message = "이름은 공백일 수 없습니다.")
    @field:Size(max = Brand.NAME_MAX_LENGTH, message = "브랜드 이름은 {max}자 이하여야 합니다.")
    val name: String,
)
