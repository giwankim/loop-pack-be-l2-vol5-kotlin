package com.loopers.application.brand.required

import com.loopers.domain.brand.Brand
import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseRepositoryTest
import com.loopers.support.withStatistics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Spring Data가 만든 [BrandRepository]가 실제 MySQL에서 계약을 지키는지 확인한다. 패키지 위치의 이유는
 * [com.loopers.application.user.required.UserRepositoryTest]와 같다.
 *
 * 목록 [BrandRepository.findAllByOrderByCreatedAtDescIdDesc]는 이름이 차례를 적는 파생 조회다. 목록 테스트는 그 이름이
 * `SimpleJpaRepository`의 기본 구현이 아니라 파생 조회로 간다는 것도 함께 고정한다. 짧은 이름 `findAll(Pageable)`로 바꾸면
 * 호출이 기본 구현으로 가서 차례가 사라지고 count 쿼리가 붙는다.
 *
 * 목록 테스트 하나는 목록이 보내는 쿼리 수를 센다. 총 개수를 세지 않는다는 약속은
 * 반환 타입이 `Slice`라는 사실에만 걸려 있어, 세어 보지 않으면 `Page`로 바꿔도 아무 테스트가 깨지지 않는다.
 */
class BrandRepositoryTest(
    private val brandRepository: BrandRepository,
) : BaseRepositoryTest() {
    companion object {
        private val FIRST_REGISTERED_AT: Instant = Instant.parse("2026-09-18T10:00:00Z")
    }

    @Test
    fun `findById reads a saved brand back with the same values after flush and clear`() {
        prepareBrand()
        entityManager.flushAndClear()

        val found = brandRepository.findById(brand.id)

        assertThat(found).isNotNull().isNotSameAs(brand)
        assertThat(found?.id).isEqualTo(brand.id)
        assertThat(found?.name).isEqualTo(brand.name)
        assertThat(found?.createdAt).isNotNull()
        assertThat(found?.updatedAt).isNotNull()
        assertThat(found?.deletedAt).isNull()
    }

    @Test
    fun `findById returns null for a deleted brand`() {
        prepareBrand()
        deleteBrand()
        entityManager.flushAndClear()

        val found = brandRepository.findById(brand.id)

        assertThat(found).isNull()
    }

    @Test
    fun `existsByName is true for a name a saved brand uses and false for an unused one`() {
        prepareBrand(name = "루퍼스")
        entityManager.flushAndClear()

        val taken = brandRepository.existsByName("루퍼스")
        val free = brandRepository.existsByName("다른 브랜드")

        assertThat(taken).isTrue()
        assertThat(free).isFalse()
    }

    @Test
    fun `existsByName is false when only a deleted brand uses the name`() {
        prepareBrand()
        deleteBrand()
        entityManager.flushAndClear()

        val taken = brandRepository.existsByName(brand.name)

        assertThat(taken).isFalse()
    }

    @Test
    fun `existsByNameAndIdNot is true when another active brand uses the name`() {
        val other = prepareBrand()
        val renaming = prepareBrand()
        entityManager.flushAndClear()

        val taken = brandRepository.existsByNameAndIdNot(other.name, renaming.id)

        assertThat(taken).isTrue()
        assertThat(other.id).isNotEqualTo(renaming.id)
    }

    /** 자기 이름으로 바꾸는 수정이 자기 행을 찾아 중복이 되지 않아야 한다. */
    @Test
    fun `existsByNameAndIdNot is false for the brand's own name`() {
        prepareBrand()
        entityManager.flushAndClear()

        val taken = brandRepository.existsByNameAndIdNot(brand.name, brand.id)

        assertThat(taken).isFalse()
    }

    /** 삭제된 브랜드는 없는 브랜드이므로 그 이름은 비어 있다. 수정이 그 이름을 가져갈 수 있어야 한다. */
    @Test
    fun `existsByNameAndIdNot is false when only a deleted brand uses the name`() {
        val deleted = prepareBrand()
        deleteBrand(deleted)
        val renaming = prepareBrand()
        entityManager.flushAndClear()

        val taken = brandRepository.existsByNameAndIdNot(deleted.name, renaming.id)

        assertThat(taken).isFalse()
    }

    @Test
    fun `findAllByOrderByCreatedAtDescIdDesc returns active brands with the newest registration first`() {
        prepareBrandRegisteredAt(registeredAt = FIRST_REGISTERED_AT, name = "첫째")
        prepareBrandRegisteredAt(registeredAt = FIRST_REGISTERED_AT.plus(1, ChronoUnit.MINUTES), name = "둘째")
        prepareBrandRegisteredAt(registeredAt = FIRST_REGISTERED_AT.plus(2, ChronoUnit.MINUTES), name = "셋째")

        val slice = brandRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 20))

        assertThat(slice.content.map { it.name }).containsExactly("셋째", "둘째", "첫째")
    }

    @Test
    fun `findAllByOrderByCreatedAtDescIdDesc breaks a tie on registration time with the higher id first`() {
        val first = prepareBrandRegisteredAt(registeredAt = FIRST_REGISTERED_AT)
        val second = prepareBrandRegisteredAt(registeredAt = FIRST_REGISTERED_AT)

        val slice = brandRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 20))

        assertThat(slice.content.map { it.id }).containsExactly(second.id, first.id)
    }

    @Test
    fun `findAllByOrderByCreatedAtDescIdDesc leaves out deleted brands`() {
        prepareBrandRegisteredAt(registeredAt = FIRST_REGISTERED_AT, name = "루퍼스")
        deleteBrand(prepareBrand())
        entityManager.flushAndClear()

        val slice = brandRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 20))

        assertThat(slice.content.map { it.name }).containsExactly("루퍼스")
    }

    @Test
    fun `findAllByOrderByCreatedAtDescIdDesc has no next slice when the active brands fill the page exactly`() {
        repeat(2) { prepareBrand() }
        entityManager.flushAndClear()

        val slice = brandRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 2))

        assertThat(slice.content).hasSize(2)
        assertThat(slice.hasNext()).isFalse()
        assertThat(slice.number).isZero()
        assertThat(slice.size).isEqualTo(2)
    }

    @Test
    fun `findAllByOrderByCreatedAtDescIdDesc has a next slice when one more active brand follows the page`() {
        repeat(3) { prepareBrand() }
        entityManager.flushAndClear()

        val slice = brandRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 2))

        assertThat(slice.content).hasSize(2)
        assertThat(slice.hasNext()).isTrue()
        assertThat(slice.number).isZero()
        assertThat(slice.size).isEqualTo(2)
    }

    /** 1분 간격으로 등록한다. 목록은 최신순이므로 가장 먼저 등록한 브랜드만 둘째 조각에 남는다. */
    @Test
    fun `findAllByOrderByCreatedAtDescIdDesc skips the brands the earlier pages already read`() {
        val brands = List(3) {
            prepareBrandRegisteredAt(registeredAt = FIRST_REGISTERED_AT.plus(it.toLong(), ChronoUnit.MINUTES))
        }

        val slice = brandRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(1, 2))

        assertThat(slice.content.map { it.id }).containsExactly(brands.first().id)
        assertThat(slice.hasNext()).isFalse()
        assertThat(slice.number).isEqualTo(1)
        assertThat(slice.size).isEqualTo(2)
    }

    /**
     * 한 조각을 읽는 데 쿼리는 하나뿐이다. 총 개수를 세는 쿼리가 따라붙지 않는다는 것이 이 하나의 뜻이다(설계 5.5).
     * 파생 조회의 반환 타입을 `Page`로 바꾸거나 이름을 `findAll(Pageable)`로 줄이면 count 쿼리가 늘어 이 테스트가 깨진다.
     */
    @Test
    fun `findAllByOrderByCreatedAtDescIdDesc reads a slice with a single query and never counts the total`() {
        repeat(3) { prepareBrand() }
        entityManager.flushAndClear()

        entityManager.withStatistics { statistics ->
            val slice = brandRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 2))

            assertThat(slice.hasNext()).isTrue()
            assertThat(statistics.prepareStatementCount).isOne()
        }
    }

    /**
     * 등록 시각을 정해 브랜드를 준비한다. [com.loopers.domain.BaseEntity]가 `@PrePersist`에서 지금 시각을 찍으므로,
     * 정렬과 동률을 흔들림 없이 확인하려면 [prepareBrand]로 저장한 뒤 벌크 수정으로 시각을 옮겨야 한다.
     */
    private fun prepareBrandRegisteredAt(registeredAt: Instant, name: String? = null): Brand {
        val prepared = prepareBrand(name = name)
        entityManager.flush()
        entityManager.createQuery("update Brand b set b.createdAt = :registeredAt where b.id = :id")
            .setParameter("registeredAt", registeredAt)
            .setParameter("id", prepared.id)
            .executeUpdate()
        entityManager.flushAndClear()
        return prepared
    }
}
