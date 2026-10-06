package com.loopers.application.user.provided

/**
 * 사용자 조각이 내주는 읽기. 좋아요·주문·포인트가 요청자를 확인할 때 부른다.
 * 사용자를 만들거나 바꾸는 유스케이스가 없어 쓰기 포트는 없다.
 */
interface UserFinder {
    /** [userId]가 가리키는 사용자가 없으면 요청자가 없는 것이므로 `UNAUTHORIZED`를 던진다(설계 5.27). */
    fun checkExists(userId: Long)
}
