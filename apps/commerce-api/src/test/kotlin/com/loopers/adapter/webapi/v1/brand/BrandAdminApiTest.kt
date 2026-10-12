package com.loopers.adapter.webapi.v1.brand

import com.loopers.application.brand.provided.BrandFinder
import com.loopers.support.error.ErrorType
import com.loopers.support.flushAndClear
import com.loopers.support.isEqualToLong
import com.loopers.support.test.BaseWebApiAdapterTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.test.web.servlet.request.RequestPostProcessor

class BrandAdminApiTest(
    private val brandFinder: BrandFinder,
) : BaseWebApiAdapterTest() {
    companion object {
        private const val ENDPOINT = "/api-admin/v1/brands"
        private const val PRODUCT_ENDPOINT = "/api-admin/v1/products"
        private val ADMIN = user("admin").roles("ADMIN")
        private val USER = user("user").roles("USER")
    }

    @Test
    fun `admin registers a brand with surrounding spaces and reads the name back as sent`() {
        val body = assertThat(requestPostBrand(name = " 루퍼스 ")).hasStatus(HttpStatus.CREATED).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        val id = body.extractingPath("$.data.id").asNumber().actual().toLong()
        body.extractingPath("$.data.name").isEqualTo(" 루퍼스 ")
        body.extractingPath("$.data.createdAt").isNotNull()
        body.extractingPath("$.data.updatedAt").isNotNull()

        val detail = assertThat(mvc.get().uri("$ENDPOINT/$id").with(ADMIN)).hasStatusOk().bodyJson()
        detail.extractingPath("$.data.id").isEqualToLong(id)
        detail.extractingPath("$.data.name").isEqualTo(" 루퍼스 ")
    }

    @Test
    fun `registering a blank name returns 400 and saves nothing`() {
        val body = assertThat(requestPostBrand(name = "   ")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").asString().contains("이름은 공백일 수 없습니다")

        assertThat(countBrands()).isZero()
    }

    @Test
    fun `registering a name taken by an active brand returns 409 and saves nothing`() {
        prepareBrand(name = "루퍼스")

        val body = assertThat(requestPostBrand(name = "루퍼스")).hasStatus(HttpStatus.CONFLICT).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Conflict")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.BRAND_NAME_DUPLICATED.message)

        assertThat(countBrands()).isOne()
    }

    /** 앞 공백은 collation이 가리지 않으므로 앞 공백만 다른 이름은 다른 브랜드다(설계 5.13). */
    @Test
    fun `registering a name that differs from an existing brand only by a leading space returns 201`() {
        prepareBrand(name = "루퍼스")

        val body = assertThat(requestPostBrand(name = " 루퍼스")).hasStatus(HttpStatus.CREATED).bodyJson()
        body.extractingPath("$.data.name").isEqualTo(" 루퍼스")

        assertThat(countBrands()).isEqualTo(2)
    }

    /** `utf8mb4_general_ci`는 대소문자를 가리지 않고, PAD SPACE라 뒤 공백을 무시한다(설계 5.13). */
    @ParameterizedTest
    @ValueSource(strings = ["Loopers ", "Loopers   ", "LOOPERS", "loopers "])
    fun `registering a name that differs from an existing brand only by trailing spaces or case returns 409 and saves nothing`(
        name: String,
    ) {
        prepareBrand(name = "Loopers")

        val body = assertThat(requestPostBrand(name = name)).hasStatus(HttpStatus.CONFLICT).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Conflict")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.BRAND_NAME_DUPLICATED.message)

        assertThat(countBrands()).isOne()
    }

    @Test
    fun `registering as a user returns 403 and saves nothing`() {
        assertThat(requestPostBrand(name = "루퍼스", principal = USER)).hasStatus(HttpStatus.FORBIDDEN)

        assertThat(countBrands()).isZero()
    }

    @Test
    fun `registering anonymously returns 403 and saves nothing`() {
        assertThat(requestPostBrand(name = "루퍼스", principal = null)).hasStatus(HttpStatus.FORBIDDEN)

        assertThat(countBrands()).isZero()
    }

    @Test
    fun `getting an unknown brand returns 404`() {
        val body = assertThat(mvc.get().uri("$ENDPOINT/999").with(ADMIN)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Not Found")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.BRAND_NOT_FOUND.message)
    }

    @Test
    fun `getting a brand as a user returns 403`() {
        prepareBrand()

        assertThat(mvc.get().uri("$ENDPOINT/${brand.id}").with(USER)).hasStatus(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `getting a brand anonymously returns 403`() {
        prepareBrand()

        assertThat(mvc.get().uri("$ENDPOINT/${brand.id}")).hasStatus(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `admin lists active brands newest first as a slice`() {
        prepareBrand()
        prepareBrand(name = "둘째")

        val body = assertThat(
            mvc.get().uri(ENDPOINT)
                .with(ADMIN)
                .param("page", "0")
                .param("size", "1"),
        ).hasStatusOk().bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.extractingPath("$.data.items.length()").isEqualTo(1)
        body.extractingPath("$.data.items[0].name").isEqualTo("둘째")
        body.extractingPath("$.data.page").isEqualTo(0)
        body.extractingPath("$.data.size").isEqualTo(1)
        body.extractingPath("$.data.hasNext").isEqualTo(true)
    }

    @Test
    fun `listing without paging parameters uses the first page of twenty`() {
        prepareBrand()

        val body = assertThat(mvc.get().uri(ENDPOINT).with(ADMIN)).hasStatusOk().bodyJson()
        body.extractingPath("$.data.page").isEqualTo(0)
        body.extractingPath("$.data.size").isEqualTo(20)
        body.extractingPath("$.data.hasNext").isEqualTo(false)
    }

    @Test
    fun `listing a deleted brand leaves it out`() {
        val deleted = prepareBrand()
        prepareBrand(name = "루퍼스")
        deleteBrand(deleted)

        val body = assertThat(mvc.get().uri(ENDPOINT).with(ADMIN)).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items.length()").isEqualTo(1)
        body.extractingPath("$.data.items[0].name").isEqualTo("루퍼스")
    }

    /**
     * 범위는 `BrandAdminListRequest`의 제약이 거르고 메시지는 그 애노테이션에서 온다(설계 5.22).
     * 어긴 값마다 다른 메시지가 나오므로 어느 경계가 걸렸는지까지 확인한다.
     */
    @ParameterizedTest
    @CsvSource(
        "-1, 20, page는 0 이상이어야 합니다",
        "0, 0, size는 1 이상이어야 합니다",
        "0, 101, size는 100 이하여야 합니다",
    )
    fun `listing with a page or size outside the allowed range returns 400`(page: Int, size: Int, message: String) {
        val body = assertThat(
            mvc.get().uri(ENDPOINT)
                .with(ADMIN)
                .param("page", page.toString())
                .param("size", size.toString()),
        ).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").asString().contains(message)
    }

    @Test
    fun `listing as a user returns 403`() {
        assertThat(mvc.get().uri(ENDPOINT).with(USER)).hasStatus(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `admin renames a brand with surrounding spaces and reads the new name back as sent`() {
        prepareBrand()

        val body = assertThat(requestPutBrand(brand.id, name = " 무신사 ")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.id").isEqualToLong(brand.id)
        body.extractingPath("$.data.name").isEqualTo(" 무신사 ")

        val detail = assertThat(mvc.get().uri("$ENDPOINT/${brand.id}").with(ADMIN)).bodyJson()
        detail.extractingPath("$.data.name").isEqualTo(" 무신사 ")
    }

    @Test
    fun `renaming to a name an active brand uses returns 409 and keeps the old name`() {
        prepareBrand(name = "루퍼스")
        val renamed = prepareBrand(name = "무신사")

        val body = assertThat(requestPutBrand(renamed.id, name = "루퍼스")).hasStatus(HttpStatus.CONFLICT).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Conflict")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.BRAND_NAME_DUPLICATED.message)

        val detail = assertThat(mvc.get().uri("$ENDPOINT/${renamed.id}").with(ADMIN)).bodyJson()
        detail.extractingPath("$.data.name").isEqualTo("무신사")
    }

    /** 자기 행은 중복 조회에서 빠지므로, 바꾸지 않은 이름을 그대로 저장해도 충돌이 아니다. */
    @Test
    fun `renaming a brand to its own current name returns 200`() {
        prepareBrand(name = "루퍼스")

        val body = assertThat(requestPutBrand(brand.id, name = "루퍼스")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.name").isEqualTo("루퍼스")
    }

    @Test
    fun `renaming to a blank name returns 400 and keeps the old name`() {
        prepareBrand(name = "루퍼스")

        val body = assertThat(requestPutBrand(brand.id, name = "   ")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.message").asString().contains("이름은 공백일 수 없습니다")

        val detail = assertThat(mvc.get().uri("$ENDPOINT/${brand.id}").with(ADMIN)).bodyJson()
        detail.extractingPath("$.data.name").isEqualTo("루퍼스")
    }

    @Test
    fun `renaming to a name over a hundred chars returns 400 and keeps the old name`() {
        prepareBrand(name = "루퍼스")

        val body = assertThat(requestPutBrand(brand.id, name = "가".repeat(101))).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.message").asString().contains("100자 이하여야")

        val detail = assertThat(mvc.get().uri("$ENDPOINT/${brand.id}").with(ADMIN)).bodyJson()
        detail.extractingPath("$.data.name").isEqualTo("루퍼스")
    }

    @Test
    fun `renaming an unknown brand returns 404`() {
        val body = assertThat(requestPutBrand(999L, name = "루퍼스")).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.BRAND_NOT_FOUND.message)
    }

    @Test
    fun `renaming a deleted brand returns 404`() {
        prepareBrand()
        deleteBrand()
        entityManager.flushAndClear()

        assertThat(requestPutBrand(brand.id, name = "무신사")).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `renaming as a user returns 403 and keeps the old name`() {
        prepareBrand(name = "루퍼스")

        assertThat(requestPutBrand(brand.id, name = "무신사", principal = USER)).hasStatus(HttpStatus.FORBIDDEN)

        assertThat(brandFinder.find(brand.id).name).isEqualTo("루퍼스")
    }

    @Test
    fun `admin deletes a brand and it disappears from the detail and the list`() {
        prepareBrand()

        val body = assertThat(requestDeleteBrand(brand.id)).hasStatusOk().bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.doesNotHavePath("$.data")
        entityManager.flushAndClear()

        assertThat(mvc.get().uri("$ENDPOINT/${brand.id}").with(ADMIN)).hasStatus(HttpStatus.NOT_FOUND)
        val list = assertThat(mvc.get().uri(ENDPOINT).with(ADMIN)).bodyJson()
        list.extractingPath("$.data.items").asArray().isEmpty()
        assertThat(countBrands()).isZero()
    }

    @Test
    fun `deleting a brand stamps its row and its product's row instead of erasing them`() {
        prepareProduct()

        assertThat(requestDeleteBrand(brand.id)).hasStatusOk()
        entityManager.flushAndClear()

        assertThat(brandRowExists(brand.id)).isTrue()
        assertThat(countStampedBrands(brand.id)).isOne()
        assertThat(countProductRows(brand.id)).isOne()
        assertThat(countStampedProducts(brand.id)).isOne()
    }

    @Test
    fun `deleting a brand twice returns 404 the second time`() {
        prepareBrand()
        assertThat(requestDeleteBrand(brand.id)).hasStatusOk()
        entityManager.flushAndClear()

        val body = assertThat(requestDeleteBrand(brand.id)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.BRAND_NOT_FOUND.message)
    }

    @Test
    fun `deleting an unknown brand returns 404`() {
        assertThat(requestDeleteBrand(999L)).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `deleting as a user returns 403 and keeps the brand and its product`() {
        prepareProduct()

        assertThat(requestDeleteBrand(brand.id, principal = USER)).hasStatus(HttpStatus.FORBIDDEN)
        entityManager.flushAndClear()

        assertThat(countBrands()).isOne()
        assertThat(mvc.get().uri("$PRODUCT_ENDPOINT/${product.id}").with(ADMIN)).hasStatusOk()
    }

    @Test
    fun `deleting anonymously returns 403 and keeps the brand and its product`() {
        prepareProduct()

        assertThat(requestDeleteBrand(brand.id, principal = null)).hasStatus(HttpStatus.FORBIDDEN)
        entityManager.flushAndClear()

        assertThat(countBrands()).isOne()
        assertThat(mvc.get().uri("$PRODUCT_ENDPOINT/${product.id}").with(ADMIN)).hasStatusOk()
    }

    /** 삭제되지 않은 상품이 남아도 거절하지 않고 함께 삭제한다. 재고가 0인 상품도 그렇다(ADR 0017). */
    @Test
    fun `deleting a brand that still has products returns 200 and deletes them with it`() {
        prepareBrand()
        val inStock = prepareProduct(brand)
        val soldOut = prepareProduct(brand, stock = 0)
        entityManager.flushAndClear()

        val body = assertThat(requestDeleteBrand(brand.id)).hasStatusOk().bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.doesNotHavePath("$.data")
        entityManager.flushAndClear()

        assertThat(mvc.get().uri("$ENDPOINT/${brand.id}").with(ADMIN)).hasStatus(HttpStatus.NOT_FOUND)
        listOf(inStock, soldOut).forEach { product ->
            assertThat(mvc.get().uri("$PRODUCT_ENDPOINT/${product.id}").with(ADMIN)).hasStatus(HttpStatus.NOT_FOUND)
        }
    }

    @Test
    fun `deleting a brand whose only product was already deleted returns 200`() {
        prepareProduct()
        deleteProduct()
        entityManager.flushAndClear()

        assertThat(requestDeleteBrand(brand.id)).hasStatusOk()
        entityManager.flushAndClear()

        assertThat(mvc.get().uri("$ENDPOINT/${brand.id}").with(ADMIN)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(countStampedBrands(brand.id)).isOne()
    }

    /** 쓰기 요청이므로 거절 경로에서도 csrf 토큰을 넣는다. [principal]이 null이면 식별 없는 요청이다. */
    private fun requestPostBrand(name: String, principal: RequestPostProcessor? = ADMIN): MvcTestResult {
        return mvc.post().uri(ENDPOINT)
            .apply { principal?.let { with(it) } }
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"name": "$name"}""")
            .exchange()
    }

    private fun requestPutBrand(brandId: Long, name: String, principal: RequestPostProcessor? = ADMIN): MvcTestResult {
        return mvc.put().uri("$ENDPOINT/$brandId")
            .apply { principal?.let { with(it) } }
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"name": "$name"}""")
            .exchange()
    }

    private fun requestDeleteBrand(brandId: Long, principal: RequestPostProcessor? = ADMIN): MvcTestResult {
        return mvc.delete().uri("$ENDPOINT/$brandId")
            .apply { principal?.let { with(it) } }
            .with(csrf())
            .exchange()
    }

    /** 삭제 시각과 상관없이 브랜드 행이 남아 있는지. 물리 삭제와 논리 삭제를 가른다. */
    private fun brandRowExists(brandId: Long): Boolean {
        return countRawRows("select count(*) from brand where id = :id", brandId) == 1L
    }

    /** 삭제 시각이 찍힌 브랜드 행 수. */
    private fun countStampedBrands(brandId: Long): Long {
        return countRawRows("select count(*) from brand where id = :id and deleted_at is not null", brandId)
    }

    /** 삭제 시각과 상관없이 브랜드에 달린 상품 행 수. */
    private fun countProductRows(brandId: Long): Long {
        return countRawRows("select count(*) from product where brand_id = :id", brandId)
    }

    /** 브랜드에 달린 상품 가운데 삭제 시각이 찍힌 행 수. */
    private fun countStampedProducts(brandId: Long): Long {
        return countRawRows("select count(*) from product where brand_id = :id and deleted_at is not null", brandId)
    }

    /** 엔티티의 SQL 제한이 붙으면 삭제된 행이 보이지 않으므로, 삭제 여부를 직접 묻는 조회는 네이티브여야 한다. */
    private fun countRawRows(sql: String, brandId: Long): Long {
        return (
            entityManager
                .createNativeQuery(sql)
                .setParameter("id", brandId)
                .singleResult as Number
            ).toLong()
    }

    /** 삭제되지 않은 브랜드 행 수. 엔티티의 SQL 제한이 JPQL에도 붙는다. */
    private fun countBrands(): Long {
        return entityManager
            .createQuery("select count(b) from Brand b", Long::class.java)
            .singleResult
    }
}
