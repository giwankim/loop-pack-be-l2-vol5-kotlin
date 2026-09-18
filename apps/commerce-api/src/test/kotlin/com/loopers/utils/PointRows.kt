package com.loopers.utils

import jakarta.persistence.EntityManager

/**
 * `point_account`·`point_history` 테이블을 저장 약속을 거치지 않고 SQL로 읽는다.
 *
 * "잔액이 그대로다", "이력이 하나다", "실패한 요청은 이력을 남기지 않는다"는 저장소가 아니라 테이블의 사실이다.
 * 영속성 컨텍스트도 거치지 않으므로 롤백 뒤의 상태를 다른 트랜잭션에서 그대로 본다. 까닭은 [countLikes]와 같다.
 */
fun EntityManager.balanceOf(accountId: Long): Long =
    (
        createNativeQuery("select balance from point_account where id = :id")
            .setParameter("id", accountId)
            .singleResult as Number
    ).toLong()

/** 한 계정의 이력 행 수. */
fun EntityManager.countPointHistories(accountId: Long): Long =
    countRows("select count(*) from point_history where point_account_id = :accountId", "accountId" to accountId)

/** 한 계정의 CHARGE 이력을 충전 키로 센다. 키 비교는 열의 collation을 따르므로 대소문자를 구분한다. */
fun EntityManager.countPointHistories(accountId: Long, chargeKey: String): Long =
    countRows(
        "select count(*) from point_history where point_account_id = :accountId and charge_key = :chargeKey",
        "accountId" to accountId,
        "chargeKey" to chargeKey,
    )

/** 한 사용자의 계정 행 수. 조회나 충전이 계정을 만들지 않았는지 볼 때 쓴다. */
fun EntityManager.countPointAccounts(userId: Long): Long =
    countRows("select count(*) from point_account where user_id = :userId", "userId" to userId)

private fun EntityManager.countRows(sql: String, vararg params: Pair<String, Any>): Long {
    val query = createNativeQuery(sql)
    params.forEach { (name, value) -> query.setParameter(name, value) }
    return (query.singleResult as Number).toLong()
}
