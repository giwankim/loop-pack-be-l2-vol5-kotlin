package com.loopers.utils

import jakarta.persistence.EntityManager

/**
 * `likes` 테이블을 저장 약속을 거치지 않고 SQL로 센다.
 *
 * "두 번 눌러도 행이 하나다", "취소하면 행이 지워진다"는 저장소가 아니라 테이블의 사실이다(ADR 0001).
 * 저장소로 되물으면 저장소가 스스로를 확인하는 셈이라, 저장소·application·HTTP 세 자리의 테스트가 이것을 함께 쓴다(설계 6).
 */
fun EntityManager.countLikes(userId: Long, productId: Long): Long =
    countRows(
        "select count(*) from likes where user_id = :userId and product_id = :productId",
        "userId" to userId,
        "productId" to productId,
    )

/** `likes`의 모든 행 수. */
fun EntityManager.countLikes(): Long = countRows("select count(*) from likes")

fun EntityManager.likeRowExists(id: Long): Boolean =
    countRows("select count(*) from likes where id = :id", "id" to id) > 0

private fun EntityManager.countRows(sql: String, vararg params: Pair<String, Any>): Long {
    val query = createNativeQuery(sql)
    params.forEach { (name, value) -> query.setParameter(name, value) }
    return (query.singleResult as Number).toLong()
}
