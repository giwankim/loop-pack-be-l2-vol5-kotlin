package com.loopers.support

import org.hibernate.resource.jdbc.spi.StatementInspector

/** [captureSql]의 결과. [statements]는 블록이 보낸 차례이고, 바인드 값 자리는 Hibernate가 보낸 그대로 `?`다. */
data class CapturedSql<T>(
    val result: T,
    val statements: List<String>,
)

/**
 * [block]을 실행하는 동안 현재 스레드가 보낸 SQL을 모아 [block]의 결과와 함께 돌려준다.
 *
 * EXPLAIN할 SQL을 손으로 옮겨 적지 않고 애플리케이션이 실제로 보낸 문장에서 얻으려고 둔다. 쿼리가 바뀌면 계획도 따라 바뀐다.
 * MockMvc는 요청을 테스트 스레드에서 처리하므로 블록 안에서 보낸 요청의 SQL도 잡힌다. 바인드 값은 잡지 않는다. 부르는 쪽이 안다.
 *
 * [SqlCaptureConfig]가 [SqlCaptureInspector]를 설치한 컨텍스트에서만 잡힌다. `BaseApplicationServiceTest`가 그 설정을 가져온다.
 */
fun <T> captureSql(block: () -> T): CapturedSql<T> {
    val statements = mutableListOf<String>()
    recording.set(statements)
    try {
        val result = block()
        return CapturedSql(result, statements.toList())
    } finally {
        recording.remove()
    }
}

/** [captureSql] 안에 있는 스레드의 기록. 블록 밖의 스레드에는 값이 없다. */
private val recording = ThreadLocal<MutableList<String>>()

/**
 * Hibernate가 준비하는 모든 문장을 받아, 현재 스레드가 [captureSql] 안에 있을 때만 적어 두고 SQL은 바꾸지 않고 돌려준다.
 * Hibernate는 SessionFactory 하나에 인스턴스 하나를 쓰므로, 블록과 스레드의 구분은 `ThreadLocal`인 기록이 맡는다.
 */
class SqlCaptureInspector : StatementInspector {
    override fun inspect(sql: String): String {
        recording.get()?.add(sql)
        return sql
    }
}
