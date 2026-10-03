package com.loopers.domain.brand

import com.loopers.application.brand.BrandAdminRegisterRequest
import com.loopers.application.brand.BrandAdminUpdateRequest
import com.loopers.domain.unsaved
import org.instancio.kotlin.KInstancio
import org.instancio.kotlin.KInstancio.gen
import org.instancio.kotlin.KSelect.field

/** 저장하지 않은 브랜드. 생성자를 건너뛰므로 이름 규칙은 계약 테스트가 지킨다. */
fun createBrand(name: String? = null): Brand {
    return KInstancio.of<Brand>()
        .unsaved()
        .set(field(Brand::name), name ?: gen().string().minLength(2).maxLength(100).get())
        .create()
}

/** 브랜드 등록 요청. 등록 규칙을 지나는 이름을 가진다. */
fun createBrandAdminRegisterRequest(name: String? = null): BrandAdminRegisterRequest {
    return KInstancio.of<BrandAdminRegisterRequest>()
        .set(field(BrandAdminRegisterRequest::name), name ?: gen().string().minLength(2).maxLength(100).get())
        .create()
}

/** 브랜드 수정 요청. 수정 규칙을 지나는 이름을 가진다. */
fun createBrandAdminUpdateRequest(name: String? = null): BrandAdminUpdateRequest {
    return KInstancio.of<BrandAdminUpdateRequest>()
        .set(field(BrandAdminUpdateRequest::name), name ?: gen().string().minLength(2).maxLength(100).get())
        .create()
}
