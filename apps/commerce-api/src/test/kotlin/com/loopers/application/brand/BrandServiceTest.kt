package com.loopers.application.brand

import com.loopers.domain.brand.FakeBrandRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows

class BrandServiceTest {
    private val brandRepository = FakeBrandRepository()
    private val brandService = BrandService(brandRepository)

    @Test
    fun `registering an untaken name saves a brand that can be fetched back`() {
        val registered = brandService.register(name = "루퍼스")

        val found = brandService.getBrand(registered.id)
        assertAll(
            { assertThat(registered.name).isEqualTo("루퍼스") },
            { assertThat(found.id).isEqualTo(registered.id) },
            { assertThat(found.name).isEqualTo("루퍼스") },
            { assertThat(found.createdAt).isNotNull() },
            { assertThat(found.updatedAt).isNotNull() },
        )
    }

    @Test
    fun `registering a name whose trimmed form matches an existing brand throws BRAND_NAME_DUPLICATED and saves nothing`() {
        val existing = brandService.register(name = "루퍼스")
        val idTheRejectedBrandWouldGet = existing.id + 1 // fake 저장소는 id를 1씩 늘려 매긴다

        val result = assertThrows<CoreException> { brandService.register(name = " 루퍼스 ") }

        assertAll(
            { assertThat(result.errorType).isEqualTo(ErrorType.BRAND_NAME_DUPLICATED) },
            { assertThat(brandService.getBrand(existing.id).name).isEqualTo("루퍼스") },
            { assertThrows<CoreException> { brandService.getBrand(idTheRejectedBrandWouldGet) } },
        )
    }

    @Test
    fun `getting an unknown brand throws BRAND_NOT_FOUND`() {
        val result = assertThrows<CoreException> { brandService.getBrand(999L) }

        assertThat(result.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
    }
}
