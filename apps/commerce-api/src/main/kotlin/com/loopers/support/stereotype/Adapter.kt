package com.loopers.support.stereotype

/** 어댑터 계층의 클래스임을 밝히는 표지. 스스로는 아무 일도 하지 않는다. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Adapter
