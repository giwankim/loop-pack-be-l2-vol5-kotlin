package com.loopers.support

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.SQLException

/**
 * 테이블의 CHECK 제약이 이 UPDATE를 거절하는지 본다. 엔티티를 거치지 않는 SQL이라 거절한 것은 도메인의 검증이 아니라
 * DB 자신이다. MySQL의 3819는 `ER_CHECK_CONSTRAINT_VIOLATED`다.
 *
 * 주문 품목(`order_line_item`)이 이 확인을 쓴다.
 */
fun JdbcTemplate.assertCheckConstraintRejects(sql: String, vararg args: Any) {
    val exception = assertThrows<UncategorizedSQLException> { update(sql, *args) }
    assertThat(exception.sqlException!!.errorCode).isEqualTo(3819)
}

/**
 * [JdbcTemplate.assertCheckConstraintRejects]와 같은 확인을 네이티브 쿼리로 한다. JPA 슬라이스에는 `JdbcTemplate`이 없어
 * 저장소 테스트가 이것을 쓴다. Hibernate가 감싼 예외의 근원이 MySQL의 3819인지 본다.
 *
 * 주문(`orders`)이 이 확인을 쓴다.
 */
fun EntityManager.assertCheckConstraintRejects(sql: String, vararg parameters: Pair<String, Any>) {
    val query = createNativeQuery(sql)
    parameters.forEach { (name, value) -> query.setParameter(name, value) }

    val exception = assertThrows<PersistenceException> { query.executeUpdate() }

    assertThat(exception).rootCause().isInstanceOfSatisfying(SQLException::class.java) { cause ->
        assertThat(cause.errorCode).isEqualTo(3819)
    }
}
