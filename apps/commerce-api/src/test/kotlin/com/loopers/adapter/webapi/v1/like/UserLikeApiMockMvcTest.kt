package com.loopers.adapter.webapi.v1.like

import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.application.like.LikeService
import com.loopers.config.security.AdminSecurityConfig
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.brand.createBrand
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.product.Stock
import com.loopers.domain.product.createProduct
import com.loopers.domain.shared.Money
import com.loopers.domain.user.User
import com.loopers.domain.user.UserRepository
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
 * 내 좋아요 목록 API. 요청자는 `X-USER-ID` 헤더로 식별하며 관리자 경계 밖이라 principal은 싣지 않는다.
 * [AdminSecurityConfig]를 가져오는 까닭은 [com.loopers.adapter.webapi.v1.brand.BrandApiMockMvcTest]와 같다.
 *
 * 차례와 삭제 필터, `hasNext`는 [com.loopers.adapter.persistence.product.ProductRepositoryTest]가 SQL로,
 * 요청자 구분과 삭제된 상품은 [com.loopers.application.like.LikeServiceTest]가 MySQL 위에서 이미 고정한다.
 * 여기서는 경로와 헤더가 요청자로 이어지는지, 쿼리 문자열이 조각에 닿는지, 응답 JSON의 모양만 본다(설계 6).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, AdminSecurityConfig::class)
@Transactional
class UserLikeApiMockMvcTest(
    private val mvc: MockMvcTester,
    private val brandRepository: BrandRepository,
    private val productRepository: ProductRepository,
    private val likeService: LikeService,
    private val userRepository: UserRepository,
    private val entityManager: EntityManager,
) {
    companion object {
        private const val USERS = "/api/v1/users"
    }

    /** 이 티켓의 인수 조건인 흐름. 항목은 고객 상품 목록의 항목과 같은 모양이고 최근에 누른 상품이 앞선다. */
    @Test
    fun `a customer reads their own like list, the most recently liked product first`() {
        val userId = registerUser()
        val brand = brandRepository.save(createBrand(name = "루퍼스"))
        val earlierId = productRepository.save(createProduct(brand)).id
        val socksId = productRepository.save(createProduct(brand, name = "양말", price = Money(3_000), stock = Stock(0))).id
        likeService.like(userId = userId, productId = earlierId)
        likeService.like(userId = userId, productId = socksId)
        entityManager.flushAndClear()

        val body = assertThat(getLikes(userId, userId)).hasStatusOk().bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.extractingPath("$.data.items.length()").isEqualTo(2)
        body.extractingPath("$.data.items[0].id").isEqualToLong(socksId)
        body.extractingPath("$.data.items[0].name").isEqualTo("양말")
        body.extractingPath("$.data.items[0].price").isEqualTo(3_000)
        body.extractingPath("$.data.items[0].soldOut").isEqualTo(true)
        body.extractingPath("$.data.items[0].brand.id").isEqualToLong(brand.id)
        body.extractingPath("$.data.items[0].brand.name").isEqualTo("루퍼스")
        body.extractingPath("$.data.items[0].likeCount").isEqualTo(1)
        body.doesNotHavePath("$.data.items[0].stock")
        body.doesNotHavePath("$.data.items[0].createdAt")
        body.extractingPath("$.data.items[1].id").isEqualToLong(earlierId)
        body.extractingPath("$.data.page").isEqualTo(0)
        body.extractingPath("$.data.size").isEqualTo(20)
        body.extractingPath("$.data.hasNext").isEqualTo(false)
    }

    @Test
    fun `an empty like list is a slice without items rather than not found`() {
        val userId = registerUser()
        entityManager.flushAndClear()

        val body = assertThat(getLikes(userId, userId)).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items").asArray().isEmpty()
        body.extractingPath("$.data.hasNext").isEqualTo(false)
    }

    /** 요청자는 자기 것만 다룰 수 있다. 다른 사용자의 목록은 없는 것이 아니라 볼 수 없는 것이라 403이다. */
    @Test
    fun `reading another user's like list returns 403`() {
        val userId = registerUser()
        val otherUserId = registerUser()
        val productId = productRepository.save(createProduct(brandRepository.save(createBrand()))).id
        likeService.like(userId = otherUserId, productId = productId)
        entityManager.flushAndClear()

        val body = assertThat(getLikes(userId = userId, pathUserId = otherUserId)).hasStatus(HttpStatus.FORBIDDEN).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Forbidden")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.FORBIDDEN.message)
    }

    /** 헤더가 없으면 요청자가 없으므로, 경로의 사용자와 견주어 볼 것도 없이 401이다(설계 5.27). */
    @Test
    fun `reading a like list without the user header returns 401`() {
        val userId = registerUser()
        entityManager.flushAndClear()

        val body = assertThat(mvc.get().uri("$USERS/$userId/likes")).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.UNAUTHORIZED.message)
    }

    /**
     * 헤더가 없고 경로마저 남의 것이면 401과 403이 둘 다 답할 수 있다. 401이 먼저인 것은 견줄 요청자가 없기 때문이고,
     * 자기 경로로만 확인하면 두 갈래가 같은 답을 내어 차례가 뒤바뀌어도 모른다(설계 5.30).
     */
    @Test
    fun `reading another user's like list without the user header returns 401 rather than 403`() {
        val otherUserId = registerUser()
        entityManager.flushAndClear()

        val body = assertThat(mvc.get().uri("$USERS/$otherUserId/likes")).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")
    }

    @Test
    fun `reading the like list of a user that does not exist returns 401`() {
        val body = assertThat(getLikes(userId = 999L, pathUserId = 999L)).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")
    }

    @Test
    fun `the page and size in the query string reach the slice`() {
        val userId = registerUser()
        val brand = brandRepository.save(createBrand())
        val earlierId = productRepository.save(createProduct(brand)).id
        val laterId = productRepository.save(createProduct(brand)).id
        likeService.like(userId = userId, productId = earlierId)
        likeService.like(userId = userId, productId = laterId)
        entityManager.flushAndClear()

        val body = assertThat(getLikes(userId, userId, "size" to "1")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items.length()").isEqualTo(1)
        body.extractingPath("$.data.items[0].id").isEqualToLong(laterId)
        body.extractingPath("$.data.size").isEqualTo(1)
        body.extractingPath("$.data.hasNext").isEqualTo(true)

        val list = assertThat(getLikes(userId, userId, "page" to "1", "size" to "1")).hasStatusOk().bodyJson()
        list.extractingPath("$.data.items[0].id").isEqualToLong(earlierId)
        list.extractingPath("$.data.page").isEqualTo(1)
        list.extractingPath("$.data.hasNext").isEqualTo(false)
    }

    @Test
    fun `listing likes outside the page and size bounds returns 400`() {
        val userId = registerUser()
        entityManager.flushAndClear()

        val body = assertThat(getLikes(userId, userId, "page" to "-1")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").asString().contains("page는 0 이상이어야 합니다")

        val error = assertThat(getLikes(userId, userId, "size" to "101")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        error.extractingPath("$.meta.message").asString().contains("size는 100 이하여야 합니다")
    }

    /** 파라미터의 차례는 [UserIdHeader.requireSelf]와 같게 둔다. 둘 다 `Long`이라 차례가 어긋나면 알아채기 어렵다. */
    private fun getLikes(userId: Long, pathUserId: Long, vararg query: Pair<String, String>): MvcTestResult =
        mvc.get().uri("$USERS/$pathUserId/likes")
            .header(UserIdHeader.NAME, userId)
            .apply { query.forEach { (name, value) -> param(name, value) } }
            .exchange()

    private fun registerUser(): Long = userRepository.save(User()).id
}
