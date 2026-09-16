package com.loopers.application.brand

import com.loopers.domain.brand.FakeBrandRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows

class BrandServiceTest {
    private val brandRepository = FakeBrandRepository()
    private val brandService = BrandService(brandRepository)

    @DisplayName("브랜드를 등록할 때, ")
    @Nested
    inner class Register {
        @DisplayName("살아 있는 브랜드와 겹치지 않는 이름이면, 등록되고 다시 조회된다.")
        @Test
        fun registersBrand_whenNameIsNotTaken() {
            // act
            val registered = brandService.register(name = "루퍼스")

            // assert
            val found = brandService.getBrand(registered.id)
            assertAll(
                { assertThat(registered.name).isEqualTo("루퍼스") },
                { assertThat(found.id).isEqualTo(registered.id) },
                { assertThat(found.name).isEqualTo("루퍼스") },
                { assertThat(found.createdAt).isNotNull() },
                { assertThat(found.updatedAt).isNotNull() },
            )
        }

        @DisplayName("앞뒤 공백을 뗀 이름이 살아 있는 브랜드와 같으면, BRAND_NAME_DUPLICATED 예외가 발생하고 새 브랜드는 저장되지 않는다.")
        @Test
        fun throwsBrandNameDuplicated_whenLiveBrandHasSameName() {
            // arrange
            val existing = brandService.register(name = "루퍼스")
            val idTheRejectedBrandWouldGet = existing.id + 1 // fake 저장소는 id를 1씩 늘려 매긴다

            // act
            val result = assertThrows<CoreException> { brandService.register(name = " 루퍼스 ") }

            // assert
            assertAll(
                { assertThat(result.errorType).isEqualTo(ErrorType.BRAND_NAME_DUPLICATED) },
                { assertThat(brandService.getBrand(existing.id).name).isEqualTo("루퍼스") },
                { assertThrows<CoreException> { brandService.getBrand(idTheRejectedBrandWouldGet) } },
            )
        }
    }

    @DisplayName("브랜드를 조회할 때, ")
    @Nested
    inner class GetBrand {
        @DisplayName("없는 식별자면, BRAND_NOT_FOUND 예외가 발생한다.")
        @Test
        fun throwsBrandNotFound_whenBrandDoesNotExist() {
            // act
            val result = assertThrows<CoreException> { brandService.getBrand(999L) }

            // assert
            assertThat(result.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
        }
    }
}
