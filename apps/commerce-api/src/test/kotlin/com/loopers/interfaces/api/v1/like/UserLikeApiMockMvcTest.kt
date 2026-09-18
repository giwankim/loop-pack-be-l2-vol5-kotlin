package com.loopers.interfaces.api.v1.like

import com.loopers.application.brand.BrandAdminRegisterRequest
import com.loopers.application.brand.BrandService
import com.loopers.application.like.LikeService
import com.loopers.application.product.ProductAdminRegisterRequest
import com.loopers.application.product.ProductService
import com.loopers.config.security.AdminSecurityConfig
import com.loopers.domain.user.User
import com.loopers.domain.user.UserRepository
import com.loopers.interfaces.api.UserIdHeader
import com.loopers.support.error.ErrorType
import com.loopers.utils.flushAndClear
import jakarta.persistence.EntityManager
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional

/**
 * 내 좋아요 목록 API. 요청자는 `X-USER-ID` 헤더로 식별하며 관리자 경계 밖이라 principal은 싣지 않는다.
 * [AdminSecurityConfig]를 가져오는 까닭은 [com.loopers.interfaces.api.v1.brand.BrandApiMockMvcTest]와 같다.
 *
 * 차례와 삭제 필터, `hasNext`는 [com.loopers.infrastructure.product.ProductRepositoryTest]가 SQL로,
 * 요청자 구분과 삭제된 상품은 [com.loopers.application.like.LikeServiceTest]가 MySQL 위에서 이미 고정한다.
 * 여기서는 경로와 헤더가 요청자로 이어지는지, 쿼리 문자열이 조각에 닿는지, 응답 JSON의 모양만 본다(설계 6).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminSecurityConfig::class)
@Transactional
class UserLikeApiMockMvcTest(
    private val mockMvc: MockMvc,
    private val brandService: BrandService,
    private val productService: ProductService,
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
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))
        val shirtId = registerProduct(brand.id, name = "티셔츠", price = 12_000, stock = 7)
        val socksId = registerProduct(brand.id, name = "양말", price = 3_000, stock = 0)
        likeService.like(userId = userId, productId = shirtId)
        likeService.like(userId = userId, productId = socksId)
        entityManager.flushAndClear()

        getLikes(userId, userId).andExpect {
            status { isOk() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data.items.length()") { value(2) }
            jsonPath("$.data.items[0].id") { value(socksId) }
            jsonPath("$.data.items[0].name") { value("양말") }
            jsonPath("$.data.items[0].price") { value(3_000) }
            jsonPath("$.data.items[0].soldOut") { value(true) }
            jsonPath("$.data.items[0].brand.id") { value(brand.id) }
            jsonPath("$.data.items[0].brand.name") { value("루퍼스") }
            jsonPath("$.data.items[0].likeCount") { value(1) }
            jsonPath("$.data.items[0].stock") { doesNotExist() }
            jsonPath("$.data.items[0].createdAt") { doesNotExist() }
            jsonPath("$.data.items[1].id") { value(shirtId) }
            jsonPath("$.data.page") { value(0) }
            jsonPath("$.data.size") { value(20) }
            jsonPath("$.data.hasNext") { value(false) }
        }
    }

    @Test
    fun `an empty like list is a slice without items rather than not found`() {
        val userId = registerUser()
        entityManager.flushAndClear()

        getLikes(userId, userId).andExpect {
            status { isOk() }
            jsonPath("$.data.items") { isEmpty() }
            jsonPath("$.data.hasNext") { value(false) }
        }
    }

    /** 요청자는 자기 것만 다룰 수 있다. 다른 사용자의 목록은 없는 것이 아니라 볼 수 없는 것이라 403이다. */
    @Test
    fun `reading another user's like list returns 403`() {
        val userId = registerUser()
        val otherUserId = registerUser()
        val productId = registerProduct()
        likeService.like(userId = otherUserId, productId = productId)
        entityManager.flushAndClear()

        getLikes(userId = userId, pathUserId = otherUserId).andExpect {
            status { isForbidden() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("Forbidden") }
            jsonPath("$.meta.message") { value(ErrorType.FORBIDDEN.message) }
        }
    }

    /** 헤더가 없으면 요청자가 없으므로, 경로의 사용자와 견주어 볼 것도 없이 401이다(설계 5.27). */
    @Test
    fun `reading a like list without the user header returns 401`() {
        val userId = registerUser()
        entityManager.flushAndClear()

        mockMvc.get("$USERS/$userId/likes").andExpect {
            status { isUnauthorized() }
            jsonPath("$.meta.errorCode") { value("Unauthorized") }
            jsonPath("$.meta.message") { value(ErrorType.UNAUTHORIZED.message) }
        }
    }

    /**
     * 헤더가 없고 경로마저 남의 것이면 401과 403이 둘 다 답할 수 있다. 401이 먼저인 것은 견줄 요청자가 없기 때문이고,
     * 자기 경로로만 확인하면 두 갈래가 같은 답을 내어 차례가 뒤바뀌어도 모른다(설계 5.30).
     */
    @Test
    fun `reading another user's like list without the user header returns 401 rather than 403`() {
        val otherUserId = registerUser()
        entityManager.flushAndClear()

        mockMvc.get("$USERS/$otherUserId/likes").andExpect {
            status { isUnauthorized() }
            jsonPath("$.meta.errorCode") { value("Unauthorized") }
        }
    }

    @Test
    fun `reading the like list of a user that does not exist returns 401`() {
        getLikes(userId = 999L, pathUserId = 999L).andExpect {
            status { isUnauthorized() }
            jsonPath("$.meta.errorCode") { value("Unauthorized") }
        }
    }

    @Test
    fun `the page and size in the query string reach the slice`() {
        val userId = registerUser()
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))
        val shirtId = registerProduct(brand.id, name = "티셔츠")
        val socksId = registerProduct(brand.id, name = "양말")
        likeService.like(userId = userId, productId = shirtId)
        likeService.like(userId = userId, productId = socksId)
        entityManager.flushAndClear()

        getLikes(userId, userId, "size" to "1").andExpect {
            status { isOk() }
            jsonPath("$.data.items.length()") { value(1) }
            jsonPath("$.data.items[0].id") { value(socksId) }
            jsonPath("$.data.size") { value(1) }
            jsonPath("$.data.hasNext") { value(true) }
        }

        getLikes(userId, userId, "page" to "1", "size" to "1").andExpect {
            status { isOk() }
            jsonPath("$.data.items[0].id") { value(shirtId) }
            jsonPath("$.data.page") { value(1) }
            jsonPath("$.data.hasNext") { value(false) }
        }
    }

    @Test
    fun `listing likes outside the page and size bounds returns 400`() {
        val userId = registerUser()
        entityManager.flushAndClear()

        getLikes(userId, userId, "page" to "-1").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value(containsString("page는 0 이상이어야 합니다")) }
        }

        getLikes(userId, userId, "size" to "101").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.message") { value(containsString("size는 100 이하여야 합니다")) }
        }
    }

    /** 파라미터의 차례는 [UserIdHeader.requireSelf]와 같게 둔다. 둘 다 `Long`이라 차례가 어긋나면 알아채기 어렵다. */
    private fun getLikes(userId: Long, pathUserId: Long, vararg query: Pair<String, String>): ResultActionsDsl =
        mockMvc.get("$USERS/$pathUserId/likes") {
            header(UserIdHeader.NAME, userId)
            query.forEach { (name, value) -> param(name, value) }
        }

    private fun registerUser(): Long = userRepository.save(User()).id

    private fun registerProduct(
        brandId: Long = brandService.register(BrandAdminRegisterRequest("루퍼스")).id,
        name: String = "티셔츠",
        price: Long = 10_000,
        stock: Int = 1,
    ): Long =
        productService.register(
            ProductAdminRegisterRequest(brandId = brandId, name = name, price = price, stock = stock),
        ).id
}
