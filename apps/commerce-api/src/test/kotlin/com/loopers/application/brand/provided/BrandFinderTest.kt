package com.loopers.application.brand.provided

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseApplicationServiceTest
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [BrandFinder]를 실제 MySQL 위에서 확인한다. 정리와 flush/clear의 까닭은 [BrandRegisterTest]와 같다.
 */
class BrandFinderTest(
    private val brandFinder: BrandFinder,
) : BaseApplicationServiceTest() {
    @Test
    fun `getting an unknown brand throws BRAND_NOT_FOUND`() {
        val exception = assertThrows<CoreException> { brandFinder.find(999L) }

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
    }

    /** 잠그는 읽기도 [BrandFinder.find]처럼 삭제된 브랜드를 없는 브랜드로 본다. 잠금은 테스트 트랜잭션이 끝날 때 풀린다. */
    @Test
    fun `getting an unknown or a deleted brand for update throws BRAND_NOT_FOUND`() {
        prepareBrand()
        deleteBrand()
        entityManager.flushAndClear()

        listOf(999L, brand.id).forEach { id ->
            val exception = assertThrows<CoreException> { brandFinder.findForUpdate(id) }

            assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
        }
    }

    /** 공유 잠금으로 읽어도 같다. 상품 등록이 이 읽기로 삭제된 브랜드를 거절한다. */
    @Test
    fun `getting an unknown or a deleted brand for share throws BRAND_NOT_FOUND`() {
        prepareBrand()
        deleteBrand()
        entityManager.flushAndClear()

        listOf(999L, brand.id).forEach { id ->
            val exception = assertThrows<CoreException> { brandFinder.findForShare(id) }

            assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
        }
    }

    @Test
    fun `listing brands returns the active ones newest first as a slice`() {
        prepareBrand()
        prepareBrand(name = "둘째")
        entityManager.flushAndClear()

        val slice = brandFinder.findAll(BrandAdminListRequest(page = 0, size = 1))

        assertThat(slice.content.map { it.name }).containsExactly("둘째")
        assertThat(slice.number).isZero()
        assertThat(slice.size).isOne()
        assertThat(slice.hasNext()).isTrue()
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
