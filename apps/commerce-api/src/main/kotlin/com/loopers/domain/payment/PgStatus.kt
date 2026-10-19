package com.loopers.domain.payment

/** PG 거래의 상태(`CONTEXT.md` PG 상태). PG가 거래 생성을 받아들인 것은 성공이 아니다. */
enum class PgStatus { PENDING, SUCCESS, FAILED }
