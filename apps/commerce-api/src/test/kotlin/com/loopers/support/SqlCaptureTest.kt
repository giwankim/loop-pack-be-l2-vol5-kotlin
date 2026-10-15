package com.loopers.support

import com.loopers.support.test.BaseWebApiAdapterTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.concurrent.thread

/** [captureSql]을 실제 MySQL 위에서 확인한다. MockMvc 요청의 SQL도 잡히는지 보려고 MockMvc 기반을 상속한다. */
class SqlCaptureTest : BaseWebApiAdapterTest() {
    @Test
    fun `captureSql returns the statements the block sent in order as Hibernate sent them with the block's result`() {
        val captured = captureSql {
            entityManager.createNativeQuery("select 'first'").singleResult
            entityManager.createNativeQuery("select :word").setParameter("word", "second").singleResult
        }

        assertThat(captured.statements).containsExactly("select 'first'", "select ?")
        assertThat(captured.result).isEqualTo("second")
    }

    /** 준비한 행을 블록 앞에서 flush해, 조회 전의 자동 flush가 블록 안에서 `insert`를 보내지 않게 한다. */
    @Test
    fun `a customer product list request inside the block sends the page query and the like count batch query`() {
        prepareProduct()
        entityManager.flushAndClear()

        val captured = captureSql { mvc.get().uri("/api/v1/products").exchange() }

        assertThat(captured.result).hasStatusOk()
        assertThat(captured.statements).hasSize(2)
        assertThat(captured.statements[0]).contains("from product ").contains(" limit ")
        assertThat(captured.statements[1]).contains("from likes ").contains(" group by ")
    }

    @Test
    fun `statements sent before or after the block are not recorded`() {
        entityManager.createNativeQuery("select 'before'").singleResult

        val captured = captureSql { entityManager.createNativeQuery("select 'inside'").singleResult }
        entityManager.createNativeQuery("select 'after'").singleResult

        assertThat(captured.statements).containsExactly("select 'inside'")
    }

    /** 다른 스레드에는 테스트 트랜잭션이 없으므로 공유 `EntityManager`가 그 조회만을 위한 영속성 컨텍스트를 열고 닫는다. */
    @Test
    fun `statements another thread sends during the block are not recorded`() {
        var anotherResult: Any? = null

        val captured = captureSql {
            thread { anotherResult = entityManager.createNativeQuery("select 'another'").singleResult }.join()
            entityManager.createNativeQuery("select 'inside'").singleResult
        }

        assertThat(anotherResult).isEqualTo("another")
        assertThat(captured.statements).containsExactly("select 'inside'")
    }
}
