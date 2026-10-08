package com.loopers.support.stereotype

import org.springframework.web.bind.annotation.RestController

/**
 * HTTP 요청을 받아 application에 넘기는 웹 어댑터임을 밝히는 표지. `@RestController`를 담는다.
 *
 * 웹 어댑터만 표지를 단다. adapter.persistence의 구현은 splearn의 integration·security 어댑터처럼 `@Component`다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@RestController
annotation class WebApiAdapter
