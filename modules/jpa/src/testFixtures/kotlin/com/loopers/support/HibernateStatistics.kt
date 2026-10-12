package com.loopers.support

import jakarta.persistence.EntityManager
import org.hibernate.SessionFactory
import org.hibernate.stat.Statistics

/**
 * 이 영속성 컨텍스트가 속한 `SessionFactory`의 Hibernate 통계.
 *
 * 조회가 몇 번 나갔는지를 세어야 지켜지는 약속이 있다. 목록이 총 개수를 세지 않는다, 좋아요 수를 항목마다가 아니라
 * 한 번에 센다 같은 것이다. 반환 타입이나 호출 모양만 보면 그 약속이 깨져도 아무 테스트가 말하지 않는다.
 *
 * 통계는 꺼져 있고, 세는 테스트는 [withStatistics]로 켠다. `@PublishedApi internal`이라 모듈 밖의 테스트는
 * [withStatistics]를 거쳐서만 통계에 닿고, 손으로 켜고 끄지 못한다.
 */
@PublishedApi
internal val EntityManager.statistics: Statistics
    get() = entityManagerFactory.unwrap(SessionFactory::class.java).statistics

/**
 * 통계를 켜고 비운 뒤 [block]에 넘겨 실행하고, 끝나면 끈다. [block]의 결과를 돌려준다.
 *
 * `hibernate.generate_statistics` 속성으로 켜지 않는다. 속성이 다르면 컨텍스트 캐시 키가 갈려 Spring 컨텍스트가 하나 더 뜬다.
 * 실행 중에 켠 통계는 컨텍스트를 나눠 쓰는 다음 테스트까지 남으므로, [block]이 실패해도 `finally`에서 끈다.
 */
inline fun <T> EntityManager.withStatistics(block: (Statistics) -> T): T {
    val statistics = this.statistics
    statistics.isStatisticsEnabled = true
    statistics.clear()
    try {
        return block(statistics)
    } finally {
        statistics.isStatisticsEnabled = false
    }
}
