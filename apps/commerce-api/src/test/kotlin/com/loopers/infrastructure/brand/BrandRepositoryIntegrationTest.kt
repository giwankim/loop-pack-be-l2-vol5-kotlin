package com.loopers.infrastructure.brand

import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.utils.DatabaseCleanUp
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
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

    @DisplayName("살아 있는 브랜드를 식별자로 조회할 때, ")
    @Nested
    inner class FindLiveById {
        @DisplayName("저장한 브랜드는 flush/clear 뒤에도 같은 값으로 다시 읽힌다.")
        @Test
        fun returnsSavedBrand_afterFlushAndClear() {
            transactionTemplate.executeWithoutResult {
                // arrange
                val saved = brandRepository.save(Brand("루퍼스"))
                flushAndClear()

                // act
                val found = brandRepository.findLiveById(saved.id)

                // assert
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

        @DisplayName("삭제된 브랜드는 없는 브랜드로 답한다.")
        @Test
        fun returnsNull_whenBrandIsDeleted() {
            // arrange
            val deleted = saveDeleted("루퍼스")

            // act
            val found = inTransaction { brandRepository.findLiveById(deleted.id) }

            // assert
            assertThat(found).isNull()
        }
    }

    @DisplayName("살아 있는 브랜드의 이름을 확인할 때, ")
    @Nested
    inner class ExistsLiveByName {
        @DisplayName("살아 있는 브랜드가 쓰는 이름이면 참이고, 아무도 쓰지 않는 이름이면 거짓이다.")
        @Test
        fun returnsWhetherLiveBrandUsesName() {
            // arrange
            transactionTemplate.executeWithoutResult { brandRepository.save(Brand("루퍼스")) }

            // act
            val taken = inTransaction { brandRepository.existsLiveByName("루퍼스") }
            val free = inTransaction { brandRepository.existsLiveByName("다른 브랜드") }

            // assert
            assertAll(
                { assertThat(taken).isTrue() },
                { assertThat(free).isFalse() },
            )
        }

        @DisplayName("삭제된 브랜드만 쓰는 이름이면 거짓이다.")
        @Test
        fun returnsFalse_whenOnlyDeletedBrandUsesName() {
            // arrange
            saveDeleted("루퍼스")

            // act
            val taken = inTransaction { brandRepository.existsLiveByName("루퍼스") }

            // assert
            assertThat(taken).isFalse()
        }
    }
}
