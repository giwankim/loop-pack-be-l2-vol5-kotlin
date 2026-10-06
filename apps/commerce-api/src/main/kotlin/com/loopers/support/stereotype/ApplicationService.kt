package com.loopers.support.stereotype

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * provided 포트를 구현하는 application Service. 클래스 전체에 `@Transactional`을 건다.
 * 메서드에 단 `@Transactional(readOnly = true)`가 이 설정보다 앞서므로 읽기 메서드는 그것을 그대로 단다.
 *
 * `@Service`를 담고 있어 kotlin-spring이 이 표지가 달린 클래스를 open으로 연다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Service
@Transactional
annotation class ApplicationService
