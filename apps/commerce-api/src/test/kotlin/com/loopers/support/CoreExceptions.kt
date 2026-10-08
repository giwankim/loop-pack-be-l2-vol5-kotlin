package com.loopers.support

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.junit.jupiter.api.assertThrows

/**
 * [call]이 던진 [CoreException]에 실린 [ErrorType]. 던지지 않거나 다른 예외를 던지면 실패한다.
 *
 * 같은 거절을 여러 호출에 대고 한 테스트에서 견주는 자리(모르는 정렬 철자 셋, 없는 상품의 세 가지 쓰기)가 쓴다.
 */
fun errorTypeOf(call: () -> Unit): ErrorType =
    assertThrows<CoreException> { call() }.errorType
