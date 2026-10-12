package com.loopers.support

import jakarta.persistence.EntityManager
import java.time.Instant

/**
 * `brand`와 `product` 행의 삭제 시각을 저장 약속을 거치지 않고 SQL로 읽는다.
 *
 * 삭제됨은 브랜드나 상품이 삭제 시각을 가진 상태다(CONTEXT.md). 그런데 엔티티의 `@SQLRestriction("deleted_at is null")`이 모든 조회에 붙어
 * 삭제된 행을 숨기므로, 어느 포트로도 그 시각을 볼 수 없다. 그래서 SQL 제한을 지나는 native 조회로 읽는다.
 *
 * 시각은 엔티티의 `deletedAt`처럼 [Instant]로 받는다. Hibernate는 [Instant]를 엔티티 필드와 같은 `TIMESTAMP_UTC` 타입으로 읽어 열의 값을
 * UTC로 보므로, SQL로 `'2020-01-01 00:00:00.123456'`을 넣은 행은 `2020-01-01T00:00:00.123456Z`로 읽힌다. 결과 타입을 주지 않으면
 * Hibernate가 열을 `hibernate.jdbc.time_zone`(UTC)으로 읽은 뒤 JVM 시간대(테스트 JVM은 Asia/Seoul)의 `LocalDateTime`으로 돌려주어,
 * 열에 넣은 값보다 9시간 늦게 읽힌다.
 */
fun EntityManager.brandDeletedAt(id: Long): Instant? {
    return createNativeQuery("select deleted_at from brand where id = :id", Instant::class.java)
        .setParameter("id", id)
        .singleResult as Instant?
}

/** `product` 행의 삭제 시각. 까닭과 타입은 [brandDeletedAt]과 같다. */
fun EntityManager.productDeletedAt(id: Long): Instant? {
    return createNativeQuery("select deleted_at from product where id = :id", Instant::class.java)
        .setParameter("id", id)
        .singleResult as Instant?
}
