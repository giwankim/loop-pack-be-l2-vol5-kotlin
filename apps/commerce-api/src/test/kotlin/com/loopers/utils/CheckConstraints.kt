package com.loopers.utils

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 테이블의 CHECK 제약이 이 UPDATE를 거절하는지 본다. 엔티티를 거치지 않는 SQL이라 거절한 것은 도메인의 검증이 아니라
 * DB 자신이다. MySQL의 3819는 `ER_CHECK_CONSTRAINT_VIOLATED`다.
 *
 * 주문(`orders`·`order_line_item`)과 포인트 이력(`point_history`)이 같은 확인을 쓴다.
 */
fun JdbcTemplate.assertCheckConstraintRejects(sql: String, vararg args: Any) {
    val exception = assertThrows<UncategorizedSQLException> { update(sql, *args) }
    assertThat(exception.sqlException!!.errorCode).isEqualTo(3819)
}
