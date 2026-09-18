package com.loopers.utils

import jakarta.persistence.EntityManager
import org.hibernate.SessionFactory
import org.hibernate.stat.Statistics

/**
 * 이 영속성 컨텍스트가 속한 `SessionFactory`의 Hibernate 통계.
 *
 * 조회가 몇 번 나갔는지를 세어야 지켜지는 약속이 있다. 목록이 총 개수를 세지 않는다, 좋아요 수를 항목마다가 아니라
 * 한 번에 센다 같은 것이다. 반환 타입이나 호출 모양만 보면 그 약속이 깨져도 아무 테스트가 말하지 않는다.
 *
 * 통계는 `hibernate.generate_statistics`로 켜거나 [Statistics.setStatisticsEnabled]로 실행 중에 켠다.
 * 뒤쪽은 Spring 컨텍스트를 새로 띄우지 않으므로 컨텍스트를 나눠 쓰는 테스트가 고른다.
 */
val EntityManager.statistics: Statistics
    get() = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
