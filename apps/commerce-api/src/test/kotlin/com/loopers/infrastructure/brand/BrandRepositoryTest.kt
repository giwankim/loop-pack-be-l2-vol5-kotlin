package com.loopers.infrastructure.brand

import com.loopers.config.jpa.DataSourceConfig
import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.utils.flushAndClear
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * [BrandRepositoryImpl]이 [BrandRepository] 계약을 실제 MySQL에서 지키는지 확인한다. 구현 클래스는 등록만 하고 부르는 것은 인터페이스다.
 * 슬라이스는 사용자 `@Configuration`과 `@Component`를 스캔하지 않으므로 데이터소스 설정, 컨테이너 설정,
 * 저장소 구현을 직접 가져오고, 내장 DB로 바꾸지 않게 한다. 구현을 알아야 하므로 domain이 아니라 infrastructure 패키지에 둔다(설계 5.20).
 * 테스트마다 트랜잭션이 롤백되어 정리가 필요 없다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DataSourceConfig::class, MySqlTestContainersConfig::class, BrandRepositoryImpl::class)
class BrandRepositoryTest(
    private val brandRepository: BrandRepository,
    private val entityManager: EntityManager,
) {
    companion object {
        private val BASE: ZonedDateTime = ZonedDateTime.of(2026, 9, 18, 10, 0, 0, 0, ZoneOffset.UTC)
    }

    @Test
    fun `findById reads a saved brand back with the same values after flush and clear`() {
        val saved = brandRepository.save(Brand("루퍼스"))
        entityManager.flushAndClear()

        val found = brandRepository.findById(saved.id)

        assertAll(
            { assertThat(found).isNotNull().isNotSameAs(saved) },
            { assertThat(found?.id).isEqualTo(saved.id) },
            { assertThat(found?.name).isEqualTo("루퍼스") },
            { assertThat(found?.createdAt).isNotNull() },
            { assertThat(found?.updatedAt).isNotNull() },
            { assertThat(found?.deletedAt).isNull() },
        )
    }

    @Test
    fun `findById returns null for a deleted brand`() {
        val deleted = saveDeleted("루퍼스")

        val found = brandRepository.findById(deleted.id)

        assertThat(found).isNull()
    }

    @Test
    fun `existsByName is true for a name a saved brand uses and false for an unused one`() {
        brandRepository.save(Brand("루퍼스"))
        entityManager.flushAndClear()

        val taken = brandRepository.existsByName("루퍼스")
        val free = brandRepository.existsByName("다른 브랜드")

        assertAll(
            { assertThat(taken).isTrue() },
            { assertThat(free).isFalse() },
        )
    }

    @Test
    fun `existsByName is false when only a deleted brand uses the name`() {
        saveDeleted("루퍼스")

        val taken = brandRepository.existsByName("루퍼스")

        assertThat(taken).isFalse()
    }

    @Test
    fun `findAll returns live brands with the newest registration first`() {
        save("첫째", registeredAt = BASE)
        save("둘째", registeredAt = BASE.plusMinutes(1))
        save("셋째", registeredAt = BASE.plusMinutes(2))

        val slice = brandRepository.findAll(page = 0, size = 20)

        assertThat(slice.items.map { it.name }).containsExactly("셋째", "둘째", "첫째")
    }

    @Test
    fun `findAll breaks a tie on registration time with the higher id first`() {
        val first = save("첫째", registeredAt = BASE)
        val second = save("둘째", registeredAt = BASE)

        val slice = brandRepository.findAll(page = 0, size = 20)

        assertThat(slice.items.map { it.id }).containsExactly(second.id, first.id)
    }

    @Test
    fun `findAll leaves out deleted brands`() {
        save("루퍼스", registeredAt = BASE)
        saveDeleted("무신사")

        val slice = brandRepository.findAll(page = 0, size = 20)

        assertThat(slice.items.map { it.name }).containsExactly("루퍼스")
    }

    @Test
    fun `findAll has no next slice when the live brands fill the page exactly`() {
        saveBrands(count = 2)

        val slice = brandRepository.findAll(page = 0, size = 2)

        assertAll(
            { assertThat(slice.items).hasSize(2) },
            { assertThat(slice.hasNext).isFalse() },
            { assertThat(slice.page).isZero() },
            { assertThat(slice.size).isEqualTo(2) },
        )
    }

    @Test
    fun `findAll has a next slice when one more live brand follows the page`() {
        saveBrands(count = 3)

        val slice = brandRepository.findAll(page = 0, size = 2)

        assertAll(
            { assertThat(slice.items).hasSize(2) },
            { assertThat(slice.hasNext).isTrue() },
        )
    }

    @Test
    fun `findAll skips the brands the earlier pages already read`() {
        saveBrands(count = 3)

        val slice = brandRepository.findAll(page = 1, size = 2)

        assertAll(
            { assertThat(slice.items.map { it.name }).containsExactly("브랜드 0") },
            { assertThat(slice.hasNext).isFalse() },
            { assertThat(slice.page).isEqualTo(1) },
        )
    }

    /** 최신 등록이 뒤 번호가 되도록 `브랜드 0`부터 1분 간격으로 만든다. */
    private fun saveBrands(count: Int) {
        repeat(count) { save("브랜드 $it", registeredAt = BASE.plusMinutes(it.toLong())) }
    }

    /**
     * 등록 시각을 정해 저장한다. [com.loopers.domain.BaseEntity]가 `@PrePersist`에서 지금 시각을 찍으므로,
     * 정렬과 동률을 흔들림 없이 확인하려면 저장한 뒤 벌크 수정으로 시각을 옮겨야 한다.
     */
    private fun save(name: String, registeredAt: ZonedDateTime): Brand {
        val saved = brandRepository.save(Brand(name))
        entityManager.flush()
        entityManager.createQuery("update Brand b set b.createdAt = :registeredAt where b.id = :id")
            .setParameter("registeredAt", registeredAt)
            .setParameter("id", saved.id)
            .executeUpdate()
        entityManager.flushAndClear()
        return saved
    }

    private fun saveDeleted(name: String): Brand =
        brandRepository.save(Brand(name).apply { delete() })
            .also { entityManager.flushAndClear() }
}
