package com.loopers.support.stereotype

import org.springframework.web.bind.annotation.RestController

/** HTTP 요청을 받아 application에 넘기는 웹 어댑터. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Adapter
@RestController
annotation class WebApiAdapter
