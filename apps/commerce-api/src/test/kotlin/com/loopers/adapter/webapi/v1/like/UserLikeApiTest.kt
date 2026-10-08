package com.loopers.adapter.webapi.v1.like

import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.support.error.ErrorType
import com.loopers.support.flushAndClear
import com.loopers.support.isEqualToLong
import com.loopers.support.test.BaseWebApiAdapterTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult

/**
 * 내 좋아요 목록 API. 요청자는 `X-USER-ID` 헤더로 식별하며 관리자 경계 밖이라 principal은 싣지 않는다.
 * 고객 경로가 인증 없이 지나가는 까닭은 [BaseWebApiAdapterTest]에 있다.
 *
 * 차례와 삭제 필터, `hasNext`는 [com.loopers.application.product.required.ProductRepositoryTest]가 SQL로,
 * 요청자 구분과 삭제된 상품은 [com.loopers.application.like.provided.LikeFinderTest]가 MySQL 위에서 이미 고정한다.
 * 여기서는 경로와 헤더가 요청자로 이어지는지, 쿼리 문자열이 조각에 닿는지, 응답 JSON의 모양만 본다(설계 6).
 */
class UserLikeApiTest : BaseWebApiAdapterTest() {
    companion object {
        private const val USERS = "/api/v1/users"
    }

    /** 이 티켓의 인수 조건인 흐름. 항목은 고객 상품 목록의 항목과 같은 모양이고 최근에 누른 상품이 앞선다. */
    @Test
    fun `a customer reads their own like list, the most recently liked product first`() {
        prepareUser()
        prepareBrand(name = "루퍼스")
        val earlier = prepareProduct(brand)
        val socks = prepareProduct(brand, name = "양말", price = 3_000, stock = 0)
        prepareLike(user, earlier)
        prepareLike(user, socks)
        entityManager.flushAndClear()

        val body = assertThat(requestGetLikes(user.id, user.id)).hasStatusOk().bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.extractingPath("$.data.items.length()").isEqualTo(2)
        body.extractingPath("$.data.items[0].id").isEqualToLong(socks.id)
        body.extractingPath("$.data.items[0].name").isEqualTo("양말")
        body.extractingPath("$.data.items[0].price").isEqualTo(3_000)
        body.extractingPath("$.data.items[0].soldOut").isEqualTo(true)
        body.extractingPath("$.data.items[0].brand.id").isEqualToLong(brand.id)
        body.extractingPath("$.data.items[0].brand.name").isEqualTo("루퍼스")
        body.extractingPath("$.data.items[0].likeCount").isEqualTo(1)
        body.doesNotHavePath("$.data.items[0].stock")
        body.doesNotHavePath("$.data.items[0].createdAt")
        body.extractingPath("$.data.items[1].id").isEqualToLong(earlier.id)
        body.extractingPath("$.data.page").isEqualTo(0)
        body.extractingPath("$.data.size").isEqualTo(20)
        body.extractingPath("$.data.hasNext").isEqualTo(false)
    }

    @Test
    fun `an empty like list is a slice without items rather than not found`() {
        prepareUser()
        entityManager.flushAndClear()

        val body = assertThat(requestGetLikes(user.id, user.id)).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items").asArray().isEmpty()
        body.extractingPath("$.data.hasNext").isEqualTo(false)
    }

    /** 요청자는 자기 것만 다룰 수 있다. 다른 사용자의 목록은 없는 것이 아니라 볼 수 없는 것이라 403이다. */
    @Test
    fun `reading another user's like list returns 403`() {
        val me = prepareUser()
        val other = prepareUser()
        prepareLike(user = other)
        entityManager.flushAndClear()

        val body = assertThat(requestGetLikes(userId = me.id, pathUserId = other.id)).hasStatus(HttpStatus.FORBIDDEN).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Forbidden")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.FORBIDDEN.message)
    }

    /** 헤더가 없으면 요청자가 없으므로, 경로의 사용자와 견주어 볼 것도 없이 401이다(설계 5.27). */
    @Test
    fun `reading a like list without the user header returns 401`() {
        prepareUser()
        entityManager.flushAndClear()

        val body = assertThat(mvc.get().uri("$USERS/${user.id}/likes")).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.UNAUTHORIZED.message)
    }

    /**
     * 헤더가 없고 경로마저 남의 것이면 401과 403이 둘 다 답할 수 있다. 401이 먼저인 것은 견줄 요청자가 없기 때문이고,
     * 자기 경로로만 확인하면 두 갈래가 같은 답을 내어 차례가 뒤바뀌어도 모른다(설계 5.30).
     */
    @Test
    fun `reading another user's like list without the user header returns 401 rather than 403`() {
        val other = prepareUser()
        entityManager.flushAndClear()

        val body = assertThat(mvc.get().uri("$USERS/${other.id}/likes")).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")
    }

    @Test
    fun `reading the like list of a user that does not exist returns 401`() {
        val body = assertThat(requestGetLikes(userId = 999L, pathUserId = 999L)).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")
    }

    @Test
    fun `the page and size in the query string reach the slice`() {
        prepareUser()
        prepareBrand()
        val earlier = prepareProduct(brand)
        val later = prepareProduct(brand)
        prepareLike(user, earlier)
        prepareLike(user, later)
        entityManager.flushAndClear()

        val body = assertThat(requestGetLikes(user.id, user.id, "size" to "1")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items.length()").isEqualTo(1)
        body.extractingPath("$.data.items[0].id").isEqualToLong(later.id)
        body.extractingPath("$.data.size").isEqualTo(1)
        body.extractingPath("$.data.hasNext").isEqualTo(true)

        val list = assertThat(requestGetLikes(user.id, user.id, "page" to "1", "size" to "1")).hasStatusOk().bodyJson()
        list.extractingPath("$.data.items[0].id").isEqualToLong(earlier.id)
        list.extractingPath("$.data.page").isEqualTo(1)
        list.extractingPath("$.data.hasNext").isEqualTo(false)
    }

    @Test
    fun `listing likes outside the page and size bounds returns 400`() {
        prepareUser()
        entityManager.flushAndClear()

        val body = assertThat(requestGetLikes(user.id, user.id, "page" to "-1")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").asString().contains("page는 0 이상이어야 합니다")

        val error = assertThat(requestGetLikes(user.id, user.id, "size" to "101")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        error.extractingPath("$.meta.message").asString().contains("size는 100 이하여야 합니다")
    }

    /** 파라미터의 차례는 [UserIdHeader.requireSelf]와 같게 둔다. 둘 다 `Long`이라 차례가 어긋나면 알아채기 어렵다. */
    private fun requestGetLikes(userId: Long, pathUserId: Long, vararg query: Pair<String, String>): MvcTestResult =
        mvc.get().uri("$USERS/$pathUserId/likes")
            .header(UserIdHeader.NAME, userId)
            .apply { query.forEach { (name, value) -> param(name, value) } }
            .exchange()
}
