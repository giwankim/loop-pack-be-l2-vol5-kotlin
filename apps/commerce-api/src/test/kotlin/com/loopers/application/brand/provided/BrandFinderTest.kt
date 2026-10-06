package com.loopers.application.brand.provided

import com.loopers.domain.brand.createBrandAdminRegisterRequest
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.flushAndClear
import com.loopers.support.stereotype.ApplicationServiceTest
import jakarta.persistence.EntityManager
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [BrandFinder]를 실제 MySQL 위에서 확인한다. 읽을 브랜드는 같은 조각의 [BrandRegister]로 만든다.
 * 정리와 flush/clear의 까닭은 [BrandRegisterTest]와 같다.
 */
@ApplicationServiceTest
class BrandFinderTest(
    private val brandFinder: BrandFinder,
    private val brandRegister: BrandRegister,
    private val entityManager: EntityManager,
) {
    @Test
    fun `getting an unknown brand throws BRAND_NOT_FOUND`() {
        val exception = assertThrows<CoreException> { brandFinder.find(999L) }

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
    }

    @Test
    fun `listing brands returns the active ones newest first as a slice`() {
        brandRegister.register(createBrandAdminRegisterRequest())
        brandRegister.register(createBrandAdminRegisterRequest(name = "둘째"))
        entityManager.flushAndClear()

        val slice = brandFinder.findAll(BrandAdminListRequest(page = 0, size = 1))

        assertThat(slice.items.map { it.name }).containsExactly("둘째")
        assertThat(slice.page).isZero()
        assertThat(slice.size).isOne()
        assertThat(slice.hasNext).isTrue()
    }

    @Test
    fun `listing outside the page and size bounds is rejected by request validation`() {
        assertThat(violationsOf(BrandAdminListRequest(page = -1))).containsExactly("page는 0 이상이어야 합니다.")
        assertThat(violationsOf(BrandAdminListRequest(size = 0))).containsExactly("size는 1 이상이어야 합니다.")
        assertThat(violationsOf(BrandAdminListRequest(size = BrandAdminListRequest.MAX_SIZE + 1)))
            .containsExactly("size는 ${BrandAdminListRequest.MAX_SIZE} 이하여야 합니다.")
    }

    private fun violationsOf(request: BrandAdminListRequest): List<String> {
        val exception = assertThrows<ConstraintViolationException> { brandFinder.findAll(request) }
        return exception.constraintViolations.map { it.message }
    }
}
