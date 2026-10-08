package com.loopers.support

import org.springframework.test.json.JsonPathValueAssert

/**
 * JSON 경로의 수가 [expected]와 같은지 `Long`으로 견준다.
 *
 * JSONPath는 `Int` 범위의 정수를 `Integer`로, 그보다 큰 정수를 `Long`으로 읽는다. `Integer` 1은 `Long` 1과 같지 않으므로
 * `extractingPath(…).isEqualTo(1L)`은 맞는 ID에서도 실패한다. 여기서 `Integer`만 `Long`으로 넓힌다. 소수는 그대로 두므로
 * `1.0`이 `1L`과 같다고 잘리지 않는다.
 *
 * 이름이 `isEqualTo`면 멤버 `isEqualTo(Object)`가 먼저 골라져 이 확장이 불리지 않는다(ADR 0011).
 */
fun JsonPathValueAssert.isEqualToLong(expected: Long): JsonPathValueAssert {
    return apply {
        asNumber().extracting { if (it is Int) it.toLong() else it }.isEqualTo(expected)
    }
}
