package com.loopers.adapter.webapi.v1.like

import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.application.brand.required.BrandRepository
import com.loopers.application.product.required.ProductRepository
import com.loopers.application.user.required.UserRepository
import com.loopers.config.security.AdminSecurityConfig
import com.loopers.domain.brand.createBrand
import com.loopers.domain.product.createProduct
import com.loopers.domain.user.User
import com.loopers.support.countLikes
import com.loopers.support.error.ErrorType
import com.loopers.support.flushAndClear
import com.loopers.support.isEqualToLong
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.testcontainers.RedisTestContainersConfig
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.transaction.annotation.Transactional

/**
 * 고객 좋아요 API. 요청자는 `X-USER-ID` 헤더로 식별하며 관리자 경계 밖이라 principal은 싣지 않는다.
 * [AdminSecurityConfig]를 가져오는 까닭은 [com.loopers.adapter.webapi.v1.brand.BrandApiMockMvcTest]와 같다.
 *
 * 멱등과 삭제된 상품의 규칙은 [com.loopers.application.like.provided.LikerTest]가 MySQL 위에서 이미 고정한다.
 * 여기서는 헤더가 요청자로 이어지는지, 오류가 어느 status로 내려가는지, 좋아요 수가 상품 상세에 닿는지를 본다(설계 6).
 * 요청 사이를 비우는 까닭은 [com.loopers.adapter.webapi.v1.product.ProductApiMockMvcTest]와 같다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, AdminSecurityConfig::class)
@Transactional
class LikeApiMockMvcTest(
    private val mvc: MockMvcTester,
    private val brandRepository: BrandRepository,
    private val productRepository: ProductRepository,
    private val userRepository: UserRepository,
    private val entityManager: EntityManager,
) {
    companion object {
        private const val PRODUCTS = "/api/v1/products"
    }

    /** 이 티켓의 인수 조건인 흐름. 누르기 → 상세 likeCount 1 → 취소 → 0. */
    @Test
    fun `liking shows one like in the product detail and unliking brings it back to zero`() {
        val userId = registerUser()
        val productId = productRepository.save(createProduct(brandRepository.save(createBrand()))).id
        entityManager.flushAndClear()

        val body = assertThat(like(productId, userId)).hasStatusOk().bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.doesNotHavePath("$.data")
        entityManager.flushAndClear()

        val detail = assertThat(mvc.get().uri("$PRODUCTS/$productId")).bodyJson()
        detail.extractingPath("$.data.likeCount").isEqualTo(1)

        val unlikeBody = assertThat(unlike(productId, userId)).hasStatusOk().bodyJson()
        unlikeBody.doesNotHavePath("$.data")
        entityManager.flushAndClear()

        val detailAfterUnlike = assertThat(mvc.get().uri("$PRODUCTS/$productId")).bodyJson()
        detailAfterUnlike.extractingPath("$.data.likeCount").isEqualTo(0)
    }

    @Test
    fun `liking shows in the product list item as well`() {
        val userId = registerUser()
        val otherUserId = registerUser()
        val productId = productRepository.save(createProduct(brandRepository.save(createBrand()))).id
        entityManager.flushAndClear()

        assertThat(like(productId, userId)).hasStatusOk()
        assertThat(like(productId, otherUserId)).hasStatusOk()
        entityManager.flushAndClear()

        val body = assertThat(mvc.get().uri(PRODUCTS)).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items[0].id").isEqualToLong(productId)
        body.extractingPath("$.data.items[0].likeCount").isEqualTo(2)
    }

    @Test
    fun `liking twice returns 200 both times and counts one like`() {
        val userId = registerUser()
        val productId = productRepository.save(createProduct(brandRepository.save(createBrand()))).id
        entityManager.flushAndClear()

        assertThat(like(productId, userId)).hasStatusOk()
        entityManager.flushAndClear()
        assertThat(like(productId, userId)).hasStatusOk()
        entityManager.flushAndClear()

        assertThat(entityManager.countLikes(userId, productId)).isOne()
        val body = assertThat(mvc.get().uri("$PRODUCTS/$productId")).bodyJson()
        body.extractingPath("$.data.likeCount").isEqualTo(1)
    }

    @Test
    fun `unliking without a like returns 200`() {
        val userId = registerUser()
        val productId = productRepository.save(createProduct(brandRepository.save(createBrand()))).id
        entityManager.flushAndClear()

        val body = assertThat(unlike(productId, userId)).hasStatusOk().bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
    }

    @Test
    fun `liking without the user header returns 401`() {
        val productId = productRepository.save(createProduct(brandRepository.save(createBrand()))).id
        entityManager.flushAndClear()

        val body = assertThat(mvc.post().uri("$PRODUCTS/$productId/likes")).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.UNAUTHORIZED.message)
    }

    @Test
    fun `unliking without the user header returns 401`() {
        val productId = productRepository.save(createProduct(brandRepository.save(createBrand()))).id
        entityManager.flushAndClear()

        val body = assertThat(mvc.delete().uri("$PRODUCTS/$productId/likes")).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")
    }

    /** 헤더가 있으나 숫자가 아니면 요청자가 없는 것이 아니라 요청이 잘못된 것이다. Spring의 타입 변환이 거절한다(설계 5.27). */
    @Test
    fun `liking with a user header that is not a number returns 400`() {
        val productId = productRepository.save(createProduct(brandRepository.save(createBrand()))).id
        entityManager.flushAndClear()

        val body = assertThat(
            mvc.post().uri("$PRODUCTS/$productId/likes").header(UserIdHeader.NAME, "abc"),
        ).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
    }

    @Test
    fun `liking as a user that does not exist returns 401 and saves nothing`() {
        val productId = productRepository.save(createProduct(brandRepository.save(createBrand()))).id
        entityManager.flushAndClear()

        val body = assertThat(like(productId, userId = 999L)).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.UNAUTHORIZED.message)

        assertThat(entityManager.countLikes(999L, productId)).isZero()
    }

    @Test
    fun `unliking as a user that does not exist returns 401`() {
        val productId = productRepository.save(createProduct(brandRepository.save(createBrand()))).id
        entityManager.flushAndClear()

        assertThat(unlike(productId, userId = 999L)).hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `liking a deleted product returns 404`() {
        val userId = registerUser()
        val product = productRepository.save(createProduct(brandRepository.save(createBrand())))
        product.delete()
        entityManager.flushAndClear()

        val body = assertThat(like(product.id, userId)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Not Found")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.PRODUCT_NOT_FOUND.message)
    }

    @Test
    fun `liking an unknown product returns 404`() {
        val userId = registerUser()

        assertThat(like(999L, userId)).hasStatus(HttpStatus.NOT_FOUND)
    }

    /** 상품이 삭제되어도 남은 좋아요는 취소된다. 취소는 상품을 보지 않는다. */
    @Test
    fun `unliking a deleted product returns 200 and lets the remaining like go`() {
        val userId = registerUser()
        val product = productRepository.save(createProduct(brandRepository.save(createBrand())))
        assertThat(like(product.id, userId)).hasStatusOk()
        product.delete()
        entityManager.flushAndClear()

        assertThat(unlike(product.id, userId)).hasStatusOk()
        entityManager.flushAndClear()

        assertThat(entityManager.countLikes(userId, product.id)).isZero()
    }

    private fun like(productId: Long, userId: Long): MvcTestResult =
        mvc.post().uri("$PRODUCTS/$productId/likes").header(UserIdHeader.NAME, userId).exchange()

    private fun unlike(productId: Long, userId: Long): MvcTestResult =
        mvc.delete().uri("$PRODUCTS/$productId/likes").header(UserIdHeader.NAME, userId).exchange()

    private fun registerUser(): Long = userRepository.save(User()).id
}
