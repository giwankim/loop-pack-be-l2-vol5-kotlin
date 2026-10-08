package com.loopers.application.user.provided

/**
 * 사용자 조각이 내주는 읽기. 웹 경계가 요청자를 받아들일지 정할 때 부른다(ADR 0015).
 * 사용자를 만들거나 바꾸는 유스케이스가 없어 쓰기 포트는 없다.
 */
interface UserFinder {
    /** [userId]가 가리키는 사용자가 있는가. 없을 때 무엇으로 답할지는 부르는 쪽이 정한다. */
    fun exists(userId: Long): Boolean
}
