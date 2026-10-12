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
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MvcTestResult

/**
 * 고객 좋아요 API. 누르기·취소·내 좋아요 목록이 모두 `/api/v1/likes` 아래에 있다(설계 5.36).
 * 요청자는 `X-USER-ID` 헤더로 식별하며 관리자 경계 밖이라 principal은 싣지 않는다.
 * 고객 경로가 인증 없이 지나가는 까닭은 [BaseWebApiAdapterTest]에 있다.
 *
 * 멱등과 삭제된 상품의 규칙은 [com.loopers.application.like.provided.LikerTest]가 MySQL 위에서 이미 고정한다.
 * 목록의 차례와 삭제 필터, `hasNext`는 [com.loopers.application.product.required.ProductRepositoryTest]가 SQL로,
 * 요청자 구분은 [com.loopers.application.like.provided.LikeFinderTest]가 고정한다.
 * 여기서는 헤더가 요청자로 이어지는지, 본문과 쿼리 문자열이 어디까지 닿는지, 오류가 어느 status로 내려가는지,
 * 좋아요 수가 상품 상세에 닿는지, 목록 응답 JSON의 모양을 본다(설계 6).
 * 요청 사이를 비우는 까닭은 [com.loopers.adapter.webapi.v1.product.ProductApiTest]와 같다.
 */
class LikeApiTest : BaseWebApiAdapterTest() {
    companion object {
        private const val LIKES = "/api/v1/likes"
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

        val body = assertThat(
            mvc.post().uri(LIKES).contentType(MediaType.APPLICATION_JSON).content("""{"productId":${product.id}}"""),
        ).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.UNAUTHORIZED.message)
    }

    @Test
    fun `unliking without the user header returns 401`() {
        prepareProduct()
        entityManager.flushAndClear()

        val body = assertThat(mvc.delete().uri("$LIKES/${product.id}")).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")
    }

    /** 헤더가 있으나 숫자가 아니면 요청자가 없는 것이 아니라 요청이 잘못된 것이다. 웹 경계가 타입 불일치로 거절한다(ADR 0015). */
    @Test
    fun `liking with a user header that is not a number returns 400`() {
        prepareProduct()
        entityManager.flushAndClear()

        val body = assertThat(
            mvc.post().uri(LIKES)
                .header(UserIdHeader.NAME, "abc")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"productId":${product.id}}"""),
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

        val body = assertThat(requestLike(999L, user.id)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.PRODUCT_NOT_FOUND.message)
    }

    /** 1 미만의 상품 ID는 상품을 찾기 전에 Request 제약이 거른다. 범용 400이며 메시지가 규칙을 말한다(설계 5.18). */
    @Test
    fun `liking with a product id below one returns 400`() {
        prepareUser()
        entityManager.flushAndClear()

        listOf(0L, -1L).forEach { productId ->
            val body = assertThat(requestLike(productId, user.id)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
            body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
            body.extractingPath("$.meta.message").isEqualTo("상품 ID는 1 이상이어야 합니다.")
        }
    }

    /** 본문이 없으면 읽을 본문이 없는 것이라 다른 본문 오류와 같은 범용 400이다. */
    @Test
    fun `liking without a body returns 400`() {
        prepareUser()
        entityManager.flushAndClear()

        val body = assertThat(
            mvc.post().uri(LIKES).header(UserIdHeader.NAME, user.id).contentType(MediaType.APPLICATION_JSON),
        ).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
    }

    /** 상품 ID가 빠지거나 null인 본문은 `LikeRequest`로 읽지 못한다. 다른 본문 오류와 같은 범용 400이다. */
    @Test
    fun `liking without a product id returns 400`() {
        prepareUser()
        entityManager.flushAndClear()
        val invalidJsons = listOf("""{}""", """{"productId": null}""")

        invalidJsons.forEach { json ->
            val body = assertThat(requestLike(json = json, userId = user.id)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
            body.extractingPath("$.meta.result").isEqualTo("FAIL")
            body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        }
    }

    /** 경로의 상품 ID가 숫자가 아니면 요청이 잘못된 것이다. 어느 값이 틀렸는지 메시지가 이름으로 말한다. */
    @Test
    fun `unliking with a product id that is not a number returns 400`() {
        prepareUser()
        entityManager.flushAndClear()

        val body = assertThat(
            mvc.delete().uri("$LIKES/abc").header(UserIdHeader.NAME, user.id),
        ).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").asString().contains("'productId'")
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

    /** 항목은 고객 상품 목록의 항목과 같은 모양이고 최근에 누른 상품이 앞선다. */
    @Test
    fun `the requester reads their like list, the most recently liked product first`() {
        prepareUser()
        prepareBrand(name = "루퍼스")
        val earlier = prepareProduct(brand)
        val socks = prepareProduct(brand, name = "양말", price = 3_000, stock = 0)
        prepareLike(user, earlier)
        prepareLike(user, socks)
        entityManager.flushAndClear()

        val body = assertThat(requestGetLikes(user.id)).hasStatusOk().bodyJson()
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

        val body = assertThat(requestGetLikes(user.id)).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items").asArray().isEmpty()
        body.extractingPath("$.data.hasNext").isEqualTo(false)
    }

    @Test
    fun `reading the like list without the user header returns 401`() {
        val body = assertThat(mvc.get().uri(LIKES)).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.UNAUTHORIZED.message)
    }

    @Test
    fun `reading the like list as a user that does not exist returns 401`() {
        val body = assertThat(requestGetLikes(userId = 999L)).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
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

        val body = assertThat(requestGetLikes(user.id, "size" to "1")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items.length()").isEqualTo(1)
        body.extractingPath("$.data.items[0].id").isEqualToLong(later.id)
        body.extractingPath("$.data.size").isEqualTo(1)
        body.extractingPath("$.data.hasNext").isEqualTo(true)

        val list = assertThat(requestGetLikes(user.id, "page" to "1", "size" to "1")).hasStatusOk().bodyJson()
        list.extractingPath("$.data.items[0].id").isEqualToLong(earlier.id)
        list.extractingPath("$.data.page").isEqualTo(1)
        list.extractingPath("$.data.hasNext").isEqualTo(false)
    }

    @Test
    fun `listing likes outside the page and size bounds returns 400`() {
        prepareUser()
        entityManager.flushAndClear()

        val body = assertThat(requestGetLikes(user.id, "page" to "-1")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").asString().contains("page는 0 이상이어야 합니다")

        val error = assertThat(requestGetLikes(user.id, "size" to "101")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        error.extractingPath("$.meta.message").asString().contains("size는 100 이하여야 합니다")
    }

    private fun requestLike(productId: Long, userId: Long): MvcTestResult {
        return requestLike(json = """{"productId":$productId}""", userId = userId)
    }

    private fun requestLike(json: String, userId: Long): MvcTestResult {
        return mvc.post().uri(LIKES)
            .header(UserIdHeader.NAME, userId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)
            .exchange()
    }

    private fun requestUnlike(productId: Long, userId: Long): MvcTestResult {
        return mvc.delete().uri("$LIKES/$productId").header(UserIdHeader.NAME, userId).exchange()
    }

    private fun requestGetLikes(userId: Long, vararg query: Pair<String, String>): MvcTestResult {
        return mvc.get().uri(LIKES)
            .header(UserIdHeader.NAME, userId)
            .apply { query.forEach { (name, value) -> param(name, value) } }
            .exchange()
    }
}
