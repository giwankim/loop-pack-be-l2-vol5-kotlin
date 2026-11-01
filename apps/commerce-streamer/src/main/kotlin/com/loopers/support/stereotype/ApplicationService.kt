package com.loopers.support.stereotype

import org.springframework.core.annotation.AliasFor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * provided 포트를 구현하는 application Service. 클래스 전체에 `@Transactional`을 건다. `NEVER`인 조율 Service(`OrderPaymentSaga`)가
 * 부르는 트랜잭션 단계의 빈(`OrderPaymentSagaSteps`)은 포트를 구현하지 않지만, 단계마다 선언한 트랜잭션이 걸리도록 이 표지를 단다(ADR 0028 규칙 2).
 * 읽기만 하는 Service는 [readOnly]를 켜서 클래스 전체를 `@Transactional(readOnly = true)`로 연다.
 * PG를 부르는 Service와 그것을 조율하는 Service는 [propagation]을 `NEVER`로 두어 트랜잭션 안에서 불리면 거절된다(ADR 0028 규칙 2).
 * `@AliasFor`는 메서드에 붙는 annotation이라 `get:`으로 Kotlin이 만드는 속성 메서드에 단다.
 *
 * `@Service`를 담고 있어 kotlin-spring이 이 표지가 달린 클래스를 open으로 연다.
 *
 * Copied from commerce-api's `ApplicationService`, examples included: the apps deploy separately, so they share no module
 * for a ten-line annotation (ADR 0033 rejects a shared stereotype module).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Service
@Transactional
annotation class ApplicationService(
    @get:AliasFor(annotation = Transactional::class, attribute = "readOnly")
    val readOnly: Boolean = false,
    @get:AliasFor(annotation = Transactional::class, attribute = "propagation")
    val propagation: Propagation = Propagation.REQUIRED,
)
