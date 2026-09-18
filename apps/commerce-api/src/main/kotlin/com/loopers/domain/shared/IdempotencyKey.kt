package com.loopers.domain.shared

/**
 * 충전 키와 주문 생성 키가 공유하는 형식. 사용자가 `Idempotency-Key` 헤더에 실어 보내는 값으로,
 * ASCII 영문·숫자·하이픈·밑줄 1–[MAX_LENGTH]자이며 대소문자를 구분하고 공백을 떼지 않는다(설계 5.8).
 *
 * 길이·형식·저장 열의 정의를 한 곳에 두어 Request 제약, 헤더 검사, 엔티티 열이 같은 상수를 읽게 한다(카탈로그 설계 5.18).
 * 키 자체는 값이라 타입을 두지 않는다. 어느 기록에 남는지는 작업마다 다르다(ADR 0004).
 */
object IdempotencyKey {
    const val MAX_LENGTH = 128

    /** 허용하는 키 전체와 맞는 정규식. `@Pattern`과 `Regex.matches` 모두 문자열 전체를 견준다. */
    const val PATTERN = "[A-Za-z0-9_-]{1,$MAX_LENGTH}"

    /** 사람에게 말하는 규칙. 거절 메시지가 같은 문장을 쓴다. */
    const val RULE = "1자 이상 ${MAX_LENGTH}자 이하의 영문·숫자·하이픈·밑줄"

    /**
     * 키를 담는 열. 서버 기본 collation(`utf8mb4_general_ci`)은 대소문자를 무시하므로 열에서 binary collation을 못 박아
     * 조회와 유일 제약이 같은 비교를 쓰게 한다(설계 12.2).
     */
    const val COLUMN_DEFINITION = "varchar($MAX_LENGTH) character set utf8mb4 collate utf8mb4_bin"
}
