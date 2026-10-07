package com.loopers.support.stereotype

import org.springframework.core.annotation.AliasFor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * provided 포트를 구현하는 application Service. 클래스 전체에 `@Transactional`을 건다.
 * 읽기만 하는 Service는 [readOnly]를 켜서 클래스 전체를 `@Transactional(readOnly = true)`로 연다.
 * `@AliasFor`는 메서드에 붙는 annotation이라 `get:`으로 Kotlin이 만드는 속성 메서드에 단다.
 *
 * `@Service`를 담고 있어 kotlin-spring이 이 표지가 달린 클래스를 open으로 연다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Service
@Transactional
annotation class ApplicationService(
    @get:AliasFor(annotation = Transactional::class, attribute = "readOnly")
    val readOnly: Boolean = false,
)
