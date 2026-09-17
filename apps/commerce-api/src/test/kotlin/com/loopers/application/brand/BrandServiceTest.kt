package com.loopers.application.brand

import com.loopers.application.shared.PageRequest
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.utils.flushAndClear
import jakarta.persistence.EntityManager
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

/**
 * [BrandService]를 실제 MySQL 위에서 확인한다. 테스트 트랜잭션이 서비스 트랜잭션을 감싸므로 테스트마다 롤백으로 정리한다.
 * 같은 트랜잭션 안에서는 영속성 컨텍스트가 조회를 가로채므로, 저장 뒤에 flush/clear를 해서 다음 조회가 SQL을 실제로 보내게 한다.
 * 추가 설정이 없는 `@SpringBootTest`라 [com.loopers.CommerceApiContextTest]와 컨텍스트를 나눠 쓴다.
 */
@SpringBootTest
@Transactional
class BrandServiceTest(
    private val brandService: BrandService,
    private val entityManager: EntityManager,
) {
    @Test
    fun `registering an untaken name saves a brand that can be fetched back`() {
        val registered = brandService.register(BrandRegisterRequest("루퍼스"))
        entityManager.flushAndClear()

        val found = brandService.find(registered.id)

        assertAll(
            { assertThat(registered.name).isEqualTo("루퍼스") },
            { assertThat(found).isNotSameAs(registered) },
            { assertThat(found.id).isEqualTo(registered.id) },
            { assertThat(found.name).isEqualTo("루퍼스") },
            { assertThat(found.createdAt).isNotNull() },
            { assertThat(found.updatedAt).isNotNull() },
        )
    }

    @Test
    fun `registering a name that matches an existing brand throws BRAND_NAME_DUPLICATED and saves nothing`() {
        val existing = brandService.register(BrandRegisterRequest("루퍼스"))
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { brandService.register(BrandRegisterRequest(" 루퍼스 ")) }
        entityManager.flushAndClear()

        assertAll(
            { assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NAME_DUPLICATED) },
            { assertThat(countBrands()).isOne() },
            { assertThat(brandService.find(existing.id).name).isEqualTo("루퍼스") },
        )
    }

    @Test
    fun `registering a name that differs from an existing brand only in letter case throws BRAND_NAME_DUPLICATED`() {
        brandService.register(BrandRegisterRequest("Loopers"))
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { brandService.register(BrandRegisterRequest("LOOPERS")) }
        entityManager.flushAndClear()

        assertAll(
            { assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NAME_DUPLICATED) },
            { assertThat(countBrands()).isOne() },
        )
    }

    @Test
    fun `registering a blank name is rejected by request validation before the domain and saves nothing`() {
        val exception = assertThrows<ConstraintViolationException> { brandService.register(BrandRegisterRequest("   ")) }
        entityManager.flushAndClear()

        assertAll(
            { assertThat(exception.constraintViolations.map { it.message }).containsExactly("브랜드 이름은 공백일 수 없습니다.") },
            { assertThat(countBrands()).isZero() },
        )
    }

    @Test
    fun `getting an unknown brand throws BRAND_NOT_FOUND`() {
        val exception = assertThrows<CoreException> { brandService.find(999L) }

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
    }

    @Test
    fun `listing brands returns the live ones newest first as a slice`() {
        brandService.register(BrandRegisterRequest("첫째"))
        brandService.register(BrandRegisterRequest("둘째"))
        entityManager.flushAndClear()

        val slice = brandService.findAll(PageRequest(page = 0, size = 1))

        assertAll(
            { assertThat(slice.items.map { it.name }).containsExactly("둘째") },
            { assertThat(slice.page).isZero() },
            { assertThat(slice.size).isOne() },
            { assertThat(slice.hasNext).isTrue() },
        )
    }

    @Test
    fun `updating a brand replaces its name`() {
        val registered = brandService.register(BrandRegisterRequest("루퍼스"))
        entityManager.flushAndClear()

        brandService.update(registered.id, BrandUpdateRequest(" 무신사 "))
        entityManager.flushAndClear()

        assertThat(brandService.find(registered.id).name).isEqualTo("무신사")
    }

    @Test
    fun `updating to a name another live brand uses throws BRAND_NAME_DUPLICATED and keeps the old name`() {
        brandService.register(BrandRegisterRequest("루퍼스"))
        val renamed = brandService.register(BrandRegisterRequest("무신사"))
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { brandService.update(renamed.id, BrandUpdateRequest("루퍼스")) }
        entityManager.flushAndClear()

        assertAll(
            { assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NAME_DUPLICATED) },
            { assertThat(brandService.find(renamed.id).name).isEqualTo("무신사") },
        )
    }

    @Test
    fun `updating a brand to its own name in a different letter case is not a duplicate`() {
        val registered = brandService.register(BrandRegisterRequest("Loopers"))
        entityManager.flushAndClear()

        brandService.update(registered.id, BrandUpdateRequest("LOOPERS"))
        entityManager.flushAndClear()

        assertThat(brandService.find(registered.id).name).isEqualTo("LOOPERS")
    }

    @Test
    fun `updating a blank name is rejected by request validation before the domain and keeps the old name`() {
        val registered = brandService.register(BrandRegisterRequest("루퍼스"))
        entityManager.flushAndClear()

        val exception = assertThrows<ConstraintViolationException> {
            brandService.update(registered.id, BrandUpdateRequest("   "))
        }
        entityManager.flushAndClear()

        assertAll(
            { assertThat(exception.constraintViolations.map { it.message }).containsExactly("브랜드 이름은 공백일 수 없습니다.") },
            { assertThat(brandService.find(registered.id).name).isEqualTo("루퍼스") },
        )
    }

    @Test
    fun `updating an unknown brand throws BRAND_NOT_FOUND`() {
        val exception = assertThrows<CoreException> { brandService.update(999L, BrandUpdateRequest("루퍼스")) }

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
    }

    @Test
    fun `a deleted brand is gone from the detail and from the list`() {
        val registered = brandService.register(BrandRegisterRequest("루퍼스"))
        entityManager.flushAndClear()

        brandService.delete(registered.id)
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { brandService.find(registered.id) }

        assertAll(
            { assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND) },
            { assertThat(brandService.findAll(PageRequest()).items).isEmpty() },
            { assertThat(countBrands()).isZero() },
        )
    }

    @Test
    fun `deleting a brand twice throws BRAND_NOT_FOUND the second time`() {
        val registered = brandService.register(BrandRegisterRequest("루퍼스"))
        entityManager.flushAndClear()
        brandService.delete(registered.id)
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { brandService.delete(registered.id) }

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
    }

    @Test
    fun `a deleted brand frees its name for a new brand`() {
        val registered = brandService.register(BrandRegisterRequest("루퍼스"))
        entityManager.flushAndClear()
        brandService.delete(registered.id)
        entityManager.flushAndClear()

        val reregistered = brandService.register(BrandRegisterRequest("루퍼스"))

        assertThat(reregistered.id).isNotEqualTo(registered.id)
    }

    /** 삭제되지 않은 브랜드 행 수. 엔티티의 SQL 제한이 JPQL에도 붙는다. */
    private fun countBrands(): Long =
        entityManager
            .createQuery("select count(b) from Brand b", Long::class.java)
            .singleResult
}
