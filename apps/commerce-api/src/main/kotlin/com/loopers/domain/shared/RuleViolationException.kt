package com.loopers.domain.shared

/**
 * 도메인 규칙을 어겨 거절됐음을 나타낸다. 상태는 바뀌지 않는다.
 * 인터페이스 계층이 보통 400, 재고·포인트 부족은 409로 옮기므로 도메인은 전송 방식을 알지 못한다.
 */
abstract class RuleViolationException(
    override val message: String,
) : RuntimeException(message)
