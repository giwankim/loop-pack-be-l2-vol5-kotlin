package com.loopers.support.stereotype

import org.springframework.core.annotation.AliasFor
import org.springframework.transaction.annotation.Propagation
import org.springframework.validation.annotation.Validated

/**
 * Request를 검증하는 provided 포트를 구현하는 Service. `@Validated`가 메서드 검증을 건다.
 * `@Valid`는 포트 인터페이스의 파라미터에 둔다. 구현 메서드에 두면 Hibernate Validator가 HV000151로 거절한다.
 * [readOnly]와 [propagation]은 [ApplicationService]의 같은 이름으로 넘어간다.
 *
 * Copied from commerce-api's `ValidatedApplicationService`: the apps deploy separately, so they share no module for a
 * ten-line annotation (ADR 0033 rejects a shared stereotype module).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@ApplicationService
@Validated
annotation class ValidatedApplicationService(
    @get:AliasFor(annotation = ApplicationService::class, attribute = "readOnly")
    val readOnly: Boolean = false,
    @get:AliasFor(annotation = ApplicationService::class, attribute = "propagation")
    val propagation: Propagation = Propagation.REQUIRED,
)
