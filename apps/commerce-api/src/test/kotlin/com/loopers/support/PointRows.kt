package com.loopers.support

import jakarta.persistence.EntityManager

/**
 * `point_account` 테이블을 저장 약속을 거치지 않고 SQL로 읽는다.
 *
 * "잔액이 그대로다", "조회가 계정을 만들지 않았다"는 저장소가 아니라 테이블의 사실이다.
 * 영속성 컨텍스트도 거치지 않으므로 롤백 뒤의 상태를 다른 트랜잭션에서 그대로 본다. 까닭은 [countLikes]와 같다.
 */
fun EntityManager.balanceOf(accountId: Long): Long {
    return (
        createNativeQuery("select balance from point_account where id = :id")
            .setParameter("id", accountId)
            .singleResult as Number
    ).toLong()
}

/** 한 사용자의 계정 행 수. 조회나 충전이 계정을 만들지 않았는지 볼 때 쓴다. */
fun EntityManager.countPointAccounts(userId: Long): Long {
    return countRows("select count(*) from point_account where user_id = :userId", "userId" to userId)
}

private fun EntityManager.countRows(sql: String, vararg params: Pair<String, Any>): Long {
    val query = createNativeQuery(sql)
    params.forEach { (name, value) -> query.setParameter(name, value) }
    return (query.singleResult as Number).toLong()
}
