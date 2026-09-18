package com.loopers.interfaces.api.v1.like

import com.loopers.application.brand.BrandAdminRegisterRequest
import com.loopers.application.brand.BrandService
import com.loopers.application.product.ProductAdminRegisterRequest
import com.loopers.application.product.ProductService
import com.loopers.config.security.AdminSecurityConfig
import com.loopers.domain.user.User
import com.loopers.domain.user.UserRepository
import com.loopers.interfaces.api.UserIdHeader
import com.loopers.support.error.ErrorType
import com.loopers.utils.flushAndClear
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional

/**
 * 고객 좋아요 API. 요청자는 `X-USER-ID` 헤더로 식별하며 관리자 경계 밖이라 principal은 싣지 않는다.
 * [AdminSecurityConfig]를 가져오는 까닭은 [com.loopers.interfaces.api.v1.brand.BrandApiMockMvcTest]와 같다.
 *
 * 멱등과 삭제된 상품의 규칙은 [com.loopers.application.like.LikeServiceTest]가 MySQL 위에서 이미 고정한다.
 * 여기서는 헤더가 요청자로 이어지는지, 오류가 어느 status로 내려가는지, 좋아요 수가 상품 상세에 닿는지를 본다(설계 6).
 * 요청 사이를 비우는 까닭은 [com.loopers.interfaces.api.v1.product.ProductApiMockMvcTest]와 같다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminSecurityConfig::class)
@Transactional
class LikeApiMockMvcTest(
    private val mockMvc: MockMvc,
    private val brandService: BrandService,
    private val productService: ProductService,
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
        val productId = registerProduct()
        entityManager.flushAndClear()

        like(productId, userId).andExpect {
            status { isOk() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data") { doesNotExist() }
        }
        entityManager.flushAndClear()

        mockMvc.get("$PRODUCTS/$productId").andExpect { jsonPath("$.data.likeCount") { value(1) } }

        unlike(productId, userId).andExpect {
            status { isOk() }
            jsonPath("$.data") { doesNotExist() }
        }
        entityManager.flushAndClear()

        mockMvc.get("$PRODUCTS/$productId").andExpect { jsonPath("$.data.likeCount") { value(0) } }
    }

    @Test
    fun `liking shows in the product list item as well`() {
        val userId = registerUser()
        val otherUserId = registerUser()
        val productId = registerProduct()
        entityManager.flushAndClear()

        like(productId, userId).andExpect { status { isOk() } }
        like(productId, otherUserId).andExpect { status { isOk() } }
        entityManager.flushAndClear()

        mockMvc.get(PRODUCTS).andExpect {
            status { isOk() }
            jsonPath("$.data.items[0].id") { value(productId) }
            jsonPath("$.data.items[0].likeCount") { value(2) }
        }
    }

    @Test
    fun `liking twice returns 200 both times and counts one like`() {
        val userId = registerUser()
        val productId = registerProduct()
        entityManager.flushAndClear()

        like(productId, userId).andExpect { status { isOk() } }
        entityManager.flushAndClear()
        like(productId, userId).andExpect { status { isOk() } }
        entityManager.flushAndClear()

        assertThat(countLikes(userId, productId)).isOne()
        mockMvc.get("$PRODUCTS/$productId").andExpect { jsonPath("$.data.likeCount") { value(1) } }
    }

    @Test
    fun `unliking without a like returns 200`() {
        val userId = registerUser()
        val productId = registerProduct()
        entityManager.flushAndClear()

        unlike(productId, userId).andExpect {
            status { isOk() }
            jsonPath("$.meta.result") { value("SUCCESS") }
        }
    }

    @Test
    fun `liking without the user header returns 401`() {
        val productId = registerProduct()
        entityManager.flushAndClear()

        mockMvc.post("$PRODUCTS/$productId/likes").andExpect {
            status { isUnauthorized() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("Unauthorized") }
            jsonPath("$.meta.message") { value(ErrorType.UNAUTHORIZED.message) }
        }
    }

    @Test
    fun `unliking without the user header returns 401`() {
        val productId = registerProduct()
        entityManager.flushAndClear()

        mockMvc.delete("$PRODUCTS/$productId/likes").andExpect {
            status { isUnauthorized() }
            jsonPath("$.meta.errorCode") { value("Unauthorized") }
        }
    }

    @Test
    fun `liking as a user that does not exist returns 401 and saves nothing`() {
        val productId = registerProduct()
        entityManager.flushAndClear()

        like(productId, userId = 999L).andExpect {
            status { isUnauthorized() }
            jsonPath("$.meta.errorCode") { value("Unauthorized") }
            jsonPath("$.meta.message") { value(ErrorType.UNAUTHORIZED.message) }
        }

        assertThat(countLikes(999L, productId)).isZero()
    }

    @Test
    fun `unliking as a user that does not exist returns 401`() {
        val productId = registerProduct()
        entityManager.flushAndClear()

        unlike(productId, userId = 999L).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `liking a deleted product returns 404`() {
        val userId = registerUser()
        val productId = registerProduct()
        productService.delete(productId)
        entityManager.flushAndClear()

        like(productId, userId).andExpect {
            status { isNotFound() }
            jsonPath("$.meta.errorCode") { value("Not Found") }
            jsonPath("$.meta.message") { value(ErrorType.PRODUCT_NOT_FOUND.message) }
        }
    }

    @Test
    fun `liking an unknown product returns 404`() {
        val userId = registerUser()

        like(999L, userId).andExpect { status { isNotFound() } }
    }

    /** 상품이 삭제되어도 남은 좋아요는 취소된다. 취소는 상품을 보지 않는다. */
    @Test
    fun `unliking a deleted product returns 200 and removes the like that remained`() {
        val userId = registerUser()
        val productId = registerProduct()
        like(productId, userId).andExpect { status { isOk() } }
        productService.delete(productId)
        entityManager.flushAndClear()

        unlike(productId, userId).andExpect { status { isOk() } }
        entityManager.flushAndClear()

        assertThat(countLikes(userId, productId)).isZero()
    }

    private fun like(productId: Long, userId: Long): ResultActionsDsl =
        mockMvc.post("$PRODUCTS/$productId/likes") { header(UserIdHeader.NAME, userId) }

    private fun unlike(productId: Long, userId: Long): ResultActionsDsl =
        mockMvc.delete("$PRODUCTS/$productId/likes") { header(UserIdHeader.NAME, userId) }

    private fun registerUser(): Long = userRepository.save(User()).id

    private fun registerProduct(): Long {
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))
        return productService
            .register(ProductAdminRegisterRequest(brandId = brand.id, name = "티셔츠", price = 10_000, stock = 1))
            .id
    }

    private fun countLikes(userId: Long, productId: Long): Long =
        (
            entityManager
                .createNativeQuery("select count(*) from likes where user_id = :userId and product_id = :productId")
                .setParameter("userId", userId)
                .setParameter("productId", productId)
                .singleResult as Number
        ).toLong()
}
