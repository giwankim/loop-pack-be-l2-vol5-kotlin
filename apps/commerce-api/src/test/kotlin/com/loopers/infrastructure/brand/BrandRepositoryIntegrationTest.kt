package com.loopers.infrastructure.brand

import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.utils.DatabaseCleanUp
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest
class BrandRepositoryIntegrationTest(
    private val brandRepository: BrandRepository,
    private val entityManager: EntityManager,
    private val transactionTemplate: TransactionTemplate,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    /** 영속성 컨텍스트를 비워 다음 조회가 DB에서 다시 읽게 한다. */
    private fun flushAndClear() {
        entityManager.flush()
        entityManager.clear()
    }

    private fun <T : Any> inTransaction(block: () -> T?): T? = transactionTemplate.execute { block() }

    private fun saveDeleted(name: String): Brand = inTransaction {
        brandRepository.save(Brand(name).apply { delete() })
    }!!

    @Test
    fun `findLiveById reads a saved brand back with the same values after flush and clear`() {
        transactionTemplate.executeWithoutResult {
            val saved = brandRepository.save(Brand("루퍼스"))
            flushAndClear()

            val found = brandRepository.findLiveById(saved.id)

            assertAll(
                { assertThat(found).isNotNull().isNotSameAs(saved) },
                { assertThat(found?.id).isEqualTo(saved.id) },
                { assertThat(found?.name).isEqualTo("루퍼스") },
                { assertThat(found?.createdAt).isNotNull() },
                { assertThat(found?.updatedAt).isNotNull() },
                { assertThat(found?.deletedAt).isNull() },
            )
        }
    }

    @Test
    fun `findLiveById returns null for a deleted brand`() {
        val deleted = saveDeleted("루퍼스")

        val found = inTransaction { brandRepository.findLiveById(deleted.id) }

        assertThat(found).isNull()
    }

    @Test
    fun `existsLiveByName is true for a name a live brand uses and false for an unused one`() {
        transactionTemplate.executeWithoutResult { brandRepository.save(Brand("루퍼스")) }

        val taken = inTransaction { brandRepository.existsLiveByName("루퍼스") }
        val free = inTransaction { brandRepository.existsLiveByName("다른 브랜드") }

        assertAll(
            { assertThat(taken).isTrue() },
            { assertThat(free).isFalse() },
        )
    }

    @Test
    fun `existsLiveByName is false when only a deleted brand uses the name`() {
        saveDeleted("루퍼스")

        val taken = inTransaction { brandRepository.existsLiveByName("루퍼스") }

        assertThat(taken).isFalse()
    }
}
