package com.loopers.domain.brand

import com.loopers.config.jpa.DataSourceConfig
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.utils.flushAndClear
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import

/**
 * [BrandRepository] 계약을 실제 MySQL에서 확인한다. 구현이 무엇인지는 보지 않고 인터페이스로만 부른다.
 * 슬라이스는 사용자 `@Configuration`을 스캔하지 않으므로 데이터소스 설정과 컨테이너 설정을 직접 가져오고,
 * 내장 DB로 바꾸지 않게 한다. 테스트마다 트랜잭션이 롤백되어 정리가 필요 없다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DataSourceConfig::class, MySqlTestContainersConfig::class)
class BrandRepositoryTest(
    private val brandRepository: BrandRepository,
    private val entityManager: EntityManager,
) {
    @Test
    fun `find reads a saved brand back with the same values after flush and clear`() {
        val saved = brandRepository.save(Brand("루퍼스"))
        entityManager.flushAndClear()

        val found = brandRepository.find(saved.id)

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
    fun `find returns null for a deleted brand`() {
        val deleted = saveDeleted("루퍼스")

        val found = brandRepository.find(deleted.id)

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

    private fun saveDeleted(name: String): Brand =
        brandRepository.save(Brand(name).apply { delete() })
            .also { entityManager.flushAndClear() }
}
