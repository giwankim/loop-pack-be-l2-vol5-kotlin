package com.loopers.domain.brand

import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `BrandFixtures.kt`가 약속하는 기본값의 범위를 확인한다. 기본값이 범위를 벗어나면 그것을 쓰는 테스트가 가끔만 깨지므로,
 * 여기서 기본값을 많이 뽑아 결정적으로 드러낸다(ADR 0007).
 */
class BrandFixturesTest {
    companion object {
        private const val SAMPLES = 1_000
    }

    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `brandName draws 1 to NAME_MAX_LENGTH uppercase letters`() {
        val names = List(SAMPLES) { brandName() }

        assertThat(names).allSatisfy { name ->
            assertThat(name).hasSizeBetween(1, Brand.NAME_MAX_LENGTH).matches("[A-Z]+")
        }
    }

    /** 만들어지는 것 자체가 생성자의 이름 규칙을 지났다는 뜻이다. Instancio가 만든 엔티티라면 `id`와 `deletedAt`이 무작위다. */
    @Test
    fun `createBrand builds unsaved brands`() {
        val brands = List(SAMPLES) { createBrand() }

        assertThat(brands).allSatisfy { brand ->
            assertThat(brand.id).isZero()
            assertThat(brand.deletedAt).isNull()
        }
    }

    @Test
    fun `createBrandAdminRegisterRequest satisfies the request's own constraints`() {
        val violations = List(SAMPLES) { createBrandAdminRegisterRequest() }.flatMap { validator.validate(it) }

        assertThat(violations).isEmpty()
    }

    @Test
    fun `createBrandAdminUpdateRequest satisfies the request's own constraints`() {
        val violations = List(SAMPLES) { createBrandAdminUpdateRequest() }.flatMap { validator.validate(it) }

        assertThat(violations).isEmpty()
    }
}
