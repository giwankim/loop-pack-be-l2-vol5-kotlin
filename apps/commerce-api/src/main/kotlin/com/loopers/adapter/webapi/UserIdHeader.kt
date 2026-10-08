package com.loopers.adapter.webapi

/**
 * 요청자 식별 헤더. API 게이트웨이가 이 헤더에 사용자 식별자를 실어 준다(CONTEXT.md 요청자).
 * 읽고 받아들이는 일은 [RequesterIdArgumentResolver]가 하고, API 문서는 이 이름으로 헤더를 적는다.
 */
object UserIdHeader {
    const val NAME = "X-USER-ID"
}
