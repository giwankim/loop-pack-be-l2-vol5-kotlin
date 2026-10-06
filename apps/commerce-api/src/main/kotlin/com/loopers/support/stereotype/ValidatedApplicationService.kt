package com.loopers.support.stereotype

import org.springframework.validation.annotation.Validated

/**
 * Request를 검증하는 provided 포트를 구현하는 Service. `@Validated`가 메서드 검증을 건다.
 * `@Valid`는 포트 인터페이스의 파라미터에 둔다. 구현 메서드에 두면 Hibernate Validator가 HV000151로 거절한다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@ApplicationService
@Validated
annotation class ValidatedApplicationService
