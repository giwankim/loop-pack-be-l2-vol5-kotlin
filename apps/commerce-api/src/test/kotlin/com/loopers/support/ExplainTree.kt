package com.loopers.support

import org.springframework.jdbc.core.JdbcTemplate

/**
 * [sql]'s `EXPLAIN FORMAT=TREE`, bound to [binds]. It plans the statement without running it, so it takes no lock. [sql] is meant to be
 * a statement the application sent, caught by [captureSql]. EXPLAIN goes through JDBC, so it is not captured itself.
 */
fun JdbcTemplate.explainTree(sql: String, binds: List<Any>): String {
    check(sql.count { it == '?' } == binds.size) { "${binds.size} bind values for the SQL's placeholders: $sql" }

    return queryForObject("explain format=tree $sql", String::class.java, *binds.toTypedArray())!!
}
