package com.loopers.adapter.webapi.v1.like

import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.support.countLikes
import com.loopers.support.error.ErrorType
import com.loopers.support.flushAndClear
import com.loopers.support.isEqualToLong
import com.loopers.support.test.BaseWebApiAdapterTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult

/**
 * 고객 좋아요 API. 요청자는 `X-USER-ID` 헤더로 식별하며 관리자 경계 밖이라 principal은 싣지 않는다.
 * 고객 경로가 인증 없이 지나가는 까닭은 [BaseWebApiAdapterTest]에 있다.
 *
 * 멱등과 삭제된 상품의 규칙은 [com.loopers.application.like.provided.LikerTest]가 MySQL 위에서 이미 고정한다.
 * 여기서는 헤더가 요청자로 이어지는지, 오류가 어느 status로 내려가는지, 좋아요 수가 상품 상세에 닿는지를 본다(설계 6).
 * 요청 사이를 비우는 까닭은 [com.loopers.adapter.webapi.v1.product.ProductApiTest]와 같다.
 */
class LikeApiTest : BaseWebApiAdapterTest() {
    companion object {
        private const val PRODUCTS = "/api/v1/products"
    }

    /** 이 티켓의 인수 조건인 흐름. 누르기 → 상세 likeCount 1 → 취소 → 0. */
    @Test
    fun `liking shows one like in the product detail and unliking brings it back to zero`() {
        prepareUser()
        prepareProduct()
        entityManager.flushAndClear()

        val body = assertThat(requestLike(product.id, user.id)).hasStatusOk().bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.doesNotHavePath("$.data")
        entityManager.flushAndClear()

        val detail = assertThat(mvc.get().uri("$PRODUCTS/${product.id}")).bodyJson()
        detail.extractingPath("$.data.likeCount").isEqualTo(1)

        val unlikeBody = assertThat(requestUnlike(product.id, user.id)).hasStatusOk().bodyJson()
        unlikeBody.doesNotHavePath("$.data")
        entityManager.flushAndClear()

        val detailAfterUnlike = assertThat(mvc.get().uri("$PRODUCTS/${product.id}")).bodyJson()
        detailAfterUnlike.extractingPath("$.data.likeCount").isEqualTo(0)
    }

    @Test
    fun `liking shows in the product list item as well`() {
        val me = prepareUser()
        val other = prepareUser()
        prepareProduct()
        entityManager.flushAndClear()

        assertThat(requestLike(product.id, me.id)).hasStatusOk()
        assertThat(requestLike(product.id, other.id)).hasStatusOk()
        entityManager.flushAndClear()

        val body = assertThat(mvc.get().uri(PRODUCTS)).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items[0].id").isEqualToLong(product.id)
        body.extractingPath("$.data.items[0].likeCount").isEqualTo(2)
    }

    @Test
    fun `liking twice returns 200 both times and counts one like`() {
        prepareUser()
        prepareProduct()
        entityManager.flushAndClear()

        assertThat(requestLike(product.id, user.id)).hasStatusOk()
        entityManager.flushAndClear()
        assertThat(requestLike(product.id, user.id)).hasStatusOk()
        entityManager.flushAndClear()

        assertThat(entityManager.countLikes(user.id, product.id)).isOne()
        val body = assertThat(mvc.get().uri("$PRODUCTS/${product.id}")).bodyJson()
        body.extractingPath("$.data.likeCount").isEqualTo(1)
    }

    @Test
    fun `unliking without a like returns 200`() {
        prepareUser()
        prepareProduct()
        entityManager.flushAndClear()

        val body = assertThat(requestUnlike(product.id, user.id)).hasStatusOk().bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
    }

    @Test
    fun `liking without the user header returns 401`() {
        prepareProduct()
        entityManager.flushAndClear()

        val body = assertThat(mvc.post().uri("$PRODUCTS/${product.id}/likes")).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.UNAUTHORIZED.message)
    }

    @Test
    fun `unliking without the user header returns 401`() {
        prepareProduct()
        entityManager.flushAndClear()

        val body = assertThat(mvc.delete().uri("$PRODUCTS/${product.id}/likes")).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")
    }

    /** 헤더가 있으나 숫자가 아니면 요청자가 없는 것이 아니라 요청이 잘못된 것이다. 웹 경계가 타입 불일치로 거절한다(ADR 0015). */
    @Test
    fun `liking with a user header that is not a number returns 400`() {
        prepareProduct()
        entityManager.flushAndClear()

        val body = assertThat(
            mvc.post().uri("$PRODUCTS/${product.id}/likes").header(UserIdHeader.NAME, "abc"),
        ).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
    }

    @Test
    fun `liking as a user that does not exist returns 401 and saves nothing`() {
        prepareProduct()
        entityManager.flushAndClear()

        val body = assertThat(requestLike(product.id, userId = 999L)).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.UNAUTHORIZED.message)

        assertThat(entityManager.countLikes(999L, product.id)).isZero()
    }

    @Test
    fun `unliking as a user that does not exist returns 401`() {
        prepareProduct()
        entityManager.flushAndClear()

        assertThat(requestUnlike(product.id, userId = 999L)).hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `liking a deleted product returns 404`() {
        prepareUser()
        prepareProduct()
        deleteProduct()
        entityManager.flushAndClear()

        val body = assertThat(requestLike(product.id, user.id)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Not Found")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.PRODUCT_NOT_FOUND.message)
    }

    @Test
    fun `liking an unknown product returns 404`() {
        prepareUser()

        assertThat(requestLike(999L, user.id)).hasStatus(HttpStatus.NOT_FOUND)
    }

    /** 상품이 삭제되어도 남은 좋아요는 취소된다. 취소는 상품을 보지 않는다. */
    @Test
    fun `unliking a deleted product returns 200 and lets the remaining like go`() {
        prepareLike()
        deleteProduct()
        entityManager.flushAndClear()

        assertThat(requestUnlike(product.id, user.id)).hasStatusOk()
        entityManager.flushAndClear()

        assertThat(entityManager.countLikes(user.id, product.id)).isZero()
    }

    private fun requestLike(productId: Long, userId: Long): MvcTestResult {
        return mvc.post().uri("$PRODUCTS/$productId/likes").header(UserIdHeader.NAME, userId).exchange()
    }

    private fun requestUnlike(productId: Long, userId: Long): MvcTestResult {
        return mvc.delete().uri("$PRODUCTS/$productId/likes").header(UserIdHeader.NAME, userId).exchange()
    }
}
