package com.loopers.adapter.webapi.v1.product

import com.loopers.application.brand.required.BrandRepository
import com.loopers.application.like.required.LikeRepository
import com.loopers.application.product.provided.ProductRegister
import com.loopers.config.security.AdminSecurityConfig
import com.loopers.domain.brand.createBrand
import com.loopers.domain.like.Like
import com.loopers.domain.product.createProductAdminRegisterRequest
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
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.transaction.annotation.Transactional

/**
 * 고객 상품 API. 식별 없이 부를 수 있어야 하므로 요청에 아무 principal도 싣지 않는다.
 * [AdminSecurityConfig]를 가져오는 까닭은 [com.loopers.adapter.webapi.v1.brand.BrandApiMockMvcTest]와 같다.
 *
 * 정렬 자체와 동률, 삭제 필터는 [com.loopers.application.product.required.ProductRepositoryTest]가 SQL로 이미 고정한다.
 * 여기서는 그 위에서 쿼리 문자열이 실제로 기준까지 이어지는지와 응답 JSON의 모양만 본다(설계 6).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, AdminSecurityConfig::class)
@Transactional
class ProductApiMockMvcTest(
    private val mvc: MockMvcTester,
    private val brandRepository: BrandRepository,
    private val productRegister: ProductRegister,
    private val likeRepository: LikeRepository,
    private val entityManager: EntityManager,
) {
    companion object {
        private const val ENDPOINT = "/api/v1/products"
        private const val ADMIN_ENDPOINT = "/api-admin/v1/products"
        private val ADMIN = user("admin").roles("ADMIN")
    }

    @Test
    fun `a customer reads a product without any identification`() {
        val brand = brandRepository.save(createBrand(name = "루퍼스"))
        val productId =
            productRegister.register(createProductAdminRegisterRequest(brandId = brand.id, name = "티셔츠", price = 12_000)).id

        val body = assertThat(mvc.get().uri("$ENDPOINT/$productId")).hasStatusOk().bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.extractingPath("$.data.id").isEqualToLong(productId)
        body.extractingPath("$.data.name").isEqualTo("티셔츠")
        body.extractingPath("$.data.price").isEqualTo(12_000)
        body.extractingPath("$.data.soldOut").isEqualTo(false)
        body.extractingPath("$.data.brand.id").isEqualToLong(brand.id)
        body.extractingPath("$.data.brand.name").isEqualTo("루퍼스")
        body.extractingPath("$.data.likeCount").isEqualTo(0)
    }

    /** 고객은 남은 수량과 시각을 보지 않는다. 관리자 응답과 같은 [com.loopers.application.product.provided.ProductInfo]에서 온다. */
    @Test
    fun `the customer detail leaves out the stock count and the timestamps`() {
        val brand = brandRepository.save(createBrand())
        val productId = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id)).id

        val body = assertThat(mvc.get().uri("$ENDPOINT/$productId")).hasStatusOk().bodyJson()
        body.doesNotHavePath("$.data.stock")
        body.doesNotHavePath("$.data.createdAt")
        body.doesNotHavePath("$.data.updatedAt")
    }

    @Test
    fun `reading an unknown product returns 404`() {
        val body = assertThat(mvc.get().uri("$ENDPOINT/999")).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Not Found")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.PRODUCT_NOT_FOUND.message)
    }

    @Test
    fun `reading a deleted product returns 404`() {
        val brand = brandRepository.save(createBrand())
        val productId = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id)).id
        productRegister.delete(productId)
        entityManager.flushAndClear()

        assertThat(mvc.get().uri("$ENDPOINT/$productId")).hasStatus(HttpStatus.NOT_FOUND)
    }

    /**
     * 설계 문서의 대표 흐름이자 #7의 인수 조건. 관리자가 재고를 0으로 맞추면 고객 상세가 품절을 보인다.
     *
     * 두 요청 사이를 비우는 까닭은 [com.loopers.adapter.webapi.v1.brand.BrandApiMockMvcTest]와 같다.
     * 비우지 않으면 고객 조회가 방금 재고를 바꾼 객체를 1차 캐시에서 받아, 변경이 DB에 닿았는지와 무관하게 통과한다.
     */
    @Test
    fun `stock an admin set to zero shows up as sold out in the customer detail`() {
        val brand = brandRepository.save(createBrand())
        val productId = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id)).id

        val body = assertThat(mvc.get().uri("$ENDPOINT/$productId")).bodyJson()
        body.extractingPath("$.data.soldOut").isEqualTo(false)

        assertThat(
            mvc.put().uri("$ADMIN_ENDPOINT/$productId/stock")
                .with(ADMIN)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"quantity": 0}"""),
        ).hasStatusOk()
        entityManager.flushAndClear()

        val detail = assertThat(mvc.get().uri("$ENDPOINT/$productId")).hasStatusOk().bodyJson()
        detail.extractingPath("$.data.soldOut").isEqualTo(true)
        detail.doesNotHavePath("$.data.stock")
    }

    /**
     * 먼저 등록한 상품을 싸게 두어 기본값이 `latest`일 때와 `price_asc`일 때의 차례가 갈리게 한다.
     * 값이 같으면 두 기준이 같은 차례를 내놓아 기본값이 무엇이든 이 테스트가 지나간다.
     */
    @Test
    fun `a customer lists products latest registered first without asking for a sort`() {
        val brand = brandRepository.save(createBrand(name = "루퍼스"))
        val firstId = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id, price = 3_000)).id
        val secondId = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id, price = 30_000)).id
        entityManager.flushAndClear()

        val body = assertThat(getProducts()).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items[0].id").isEqualToLong(secondId)
        body.extractingPath("$.data.items[1].id").isEqualToLong(firstId)
        body.extractingPath("$.data.items[0].brand.name").isEqualTo("루퍼스")
        body.extractingPath("$.data.items[0].soldOut").isEqualTo(false)
        body.extractingPath("$.data.items[0].likeCount").isEqualTo(0)
        body.extractingPath("$.data.page").isEqualTo(0)
        body.extractingPath("$.data.size").isEqualTo(20)
        body.extractingPath("$.data.hasNext").isEqualTo(false)
    }

    /** 싼 상품을 먼저 등록해 두 기준이 서로 다른 차례를 내놓게 한다. 같은 차례라면 기준이 닿았는지 알 수 없다. */
    @Test
    fun `the sort in the query string reaches the list order`() {
        val brand = brandRepository.save(createBrand())
        val cheapId = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id, price = 3_000)).id
        val dearId = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id, price = 30_000)).id
        entityManager.flushAndClear()

        val body = assertThat(getProducts("sort" to "price_asc")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items[0].id").isEqualToLong(cheapId)
        body.extractingPath("$.data.items[1].id").isEqualToLong(dearId)

        val list = assertThat(getProducts("sort" to "latest")).hasStatusOk().bodyJson()
        list.extractingPath("$.data.items[0].id").isEqualToLong(dearId)
        list.extractingPath("$.data.items[1].id").isEqualToLong(cheapId)
    }

    /**
     * 좋아요를 먼저 등록한 상품에만 눌러 두어 `latest`와 차례가 갈리게 한다. 정렬과 동률 자체는 저장소 테스트가 지키므로
     * 여기서는 쿼리 문자열의 `likes_desc`가 기준까지 닿는지와, 항목마다 자기 `likeCount`가 실리는지만 본다.
     */
    @Test
    fun `the likes_desc sort in the query string reaches the list order`() {
        val brand = brandRepository.save(createBrand())
        val likedId = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id)).id
        val unlikedId = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id)).id
        likeRepository.save(Like(userId = 1L, productId = likedId))
        entityManager.flushAndClear()

        val body = assertThat(getProducts("sort" to "likes_desc")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items[0].id").isEqualToLong(likedId)
        body.extractingPath("$.data.items[0].likeCount").isEqualTo(1)
        body.extractingPath("$.data.items[1].id").isEqualToLong(unlikedId)
        body.extractingPath("$.data.items[1].likeCount").isEqualTo(0)
    }

    @Test
    fun `listing with a sort no product sort answers to returns 400`() {
        val body = assertThat(getProducts("sort" to "likes")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.INVALID_SORT.message)
    }

    @Test
    fun `listing outside the page and size bounds returns 400`() {
        val body = assertThat(getProducts("page" to "-1")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").asString().contains("page는 0 이상이어야 합니다")

        val error = assertThat(getProducts("size" to "101")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        error.extractingPath("$.meta.message").asString().contains("size는 100 이하여야 합니다")
    }

    @Test
    fun `listing under an unknown brand is empty rather than not found`() {
        val brand = brandRepository.save(createBrand())
        productRegister.register(createProductAdminRegisterRequest(brandId = brand.id))
        entityManager.flushAndClear()

        val body = assertThat(getProducts("brandId" to "999")).hasStatusOk().bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.extractingPath("$.data.items").asArray().isEmpty()
        body.extractingPath("$.data.hasNext").isEqualTo(false)
    }

    @Test
    fun `listing keeps only the asked brand's products`() {
        val brand = brandRepository.save(createBrand())
        val other = brandRepository.save(createBrand())
        val mineId = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id)).id
        productRegister.register(createProductAdminRegisterRequest(brandId = other.id))
        entityManager.flushAndClear()

        val body = assertThat(getProducts("brandId" to brand.id.toString())).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items.length()").isEqualTo(1)
        body.extractingPath("$.data.items[0].id").isEqualToLong(mineId)
    }

    private fun getProducts(vararg query: Pair<String, String>): MvcTestResult =
        mvc.get().uri(ENDPOINT)
            .apply { query.forEach { (name, value) -> param(name, value) } }
            .exchange()
}
