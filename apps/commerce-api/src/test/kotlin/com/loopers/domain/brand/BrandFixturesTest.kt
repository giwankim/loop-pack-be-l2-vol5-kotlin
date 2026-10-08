package com.loopers.domain.brand

import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * `BrandFixtures.kt`가 약속하는 기본값의 범위를 확인한다. 기본값이 범위를 벗어나면 그것을 쓰는 테스트가 가끔만 깨지므로,
 * 여기서 기본값을 많이 뽑아 결정적으로 드러낸다. Instancio가 생성자를 건너뛰므로 규칙은 여기서 지킨다(ADR 0010).
 */
class BrandFixturesTest {
    companion object {
        private const val SAMPLES = 1_000
    }

    private val validator = Validation.buildDefaultValidatorFactory().validator

    /**
     * Instancio가 만든 엔티티라면 `id`와 `deletedAt`이 무작위다.
     * 생성자는 이름의 하한을 검사하지 않으므로 2자 이상인지는 여기서 본다.
     */
    @Test
    fun `createBrand builds unsaved brands named 2 to 100 characters`() {
        val brands = List(SAMPLES) { createBrand() }

        assertThat(brands).allSatisfy { brand ->
            assertThat(brand.id).isZero()
            assertThat(brand.deletedAt).isNull()
            assertThat(brand.name).hasSizeBetween(2, 100)
        }
    }

    @Test
    fun `createBrand builds brands the real constructor accepts`() {
        val brands = List(SAMPLES) { createBrand() }

        assertThat(brands).allSatisfy { brand ->
            assertDoesNotThrow { Brand(name = brand.name) }
        }
    }

    @Test
    fun `createBrand uses the name it is given`() {
        val brand = createBrand(name = "루퍼스")

        assertThat(brand.name).isEqualTo("루퍼스")
    }

    @Test
    fun `createBrandAdminRegisterRequest satisfies the request's own constraints with names of 2 to 100 characters`() {
        val requests = List(SAMPLES) { createBrandAdminRegisterRequest() }

        assertThat(requests.flatMap { validator.validate(it) }).isEmpty()
        assertThat(requests).allSatisfy { request -> assertThat(request.name).hasSizeBetween(2, 100) }
    }

    @Test
    fun `createBrandAdminRegisterRequest uses the name it is given`() {
        val request = createBrandAdminRegisterRequest(name = "루퍼스")

        assertThat(request.name).isEqualTo("루퍼스")
    }

    @Test
    fun `createBrandAdminUpdateRequest satisfies the request's own constraints with names of 2 to 100 characters`() {
        val requests = List(SAMPLES) { createBrandAdminUpdateRequest() }

        assertThat(requests.flatMap { validator.validate(it) }).isEmpty()
        assertThat(requests).allSatisfy { request -> assertThat(request.name).hasSizeBetween(2, 100) }
    }

    @Test
    fun `createBrandAdminUpdateRequest uses the name it is given`() {
        val request = createBrandAdminUpdateRequest(name = "루퍼스")

        assertThat(request.name).isEqualTo("루퍼스")
    }
}
