package com.loopers.application.like

import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.like.Like
import com.loopers.domain.like.LikeRepository
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.product.Stock
import com.loopers.domain.shared.Money
import com.loopers.domain.user.User
import com.loopers.domain.user.UserRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.utils.countLikes
import com.loopers.utils.flushAndClear
import com.loopers.utils.statistics
import jakarta.persistence.EntityManager
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

/**
 * [LikeService]를 실제 MySQL 위에서 확인한다. 정리와 flush/clear의 까닭은 [com.loopers.application.brand.BrandServiceTest]와 같다.
 *
 * 관계가 있는지는 저장 약속을 거치지 않고 `likes` 테이블을 SQL로 센다. 두 번 눌러도 행이 하나이고
 * 취소가 행을 지운다는 약속은 테이블에서 봐야 한다(ADR 0001).
 */
@SpringBootTest
@Transactional
class LikeServiceTest(
    private val likeService: LikeService,
    private val likeRepository: LikeRepository,
    private val userRepository: UserRepository,
    private val brandRepository: BrandRepository,
    private val productRepository: ProductRepository,
    private val entityManager: EntityManager,
) {
    @Test
    fun `liking an active product for the first time saves one like for the user and product`() {
        val user = userRepository.save(User())
        val product = registerProduct()
        entityManager.flushAndClear()

        likeService.like(userId = user.id, productId = product.id)
        entityManager.flushAndClear()

        assertThat(entityManager.countLikes(user.id, product.id)).isOne()
    }

    @Test
    fun `liking the same product again succeeds and keeps one like`() {
        val user = userRepository.save(User())
        val product = registerProduct()
        likeService.like(userId = user.id, productId = product.id)
        entityManager.flushAndClear()

        likeService.like(userId = user.id, productId = product.id)
        entityManager.flushAndClear()

        assertThat(entityManager.countLikes(user.id, product.id)).isOne()
    }

    @Test
    fun `liking as another user adds a second like for the product`() {
        val user = userRepository.save(User())
        val other = userRepository.save(User())
        val product = registerProduct()
        likeService.like(userId = user.id, productId = product.id)
        entityManager.flushAndClear()

        likeService.like(userId = other.id, productId = product.id)
        entityManager.flushAndClear()

        assertAll(
            { assertThat(entityManager.countLikes(user.id, product.id)).isOne() },
            { assertThat(entityManager.countLikes(other.id, product.id)).isOne() },
        )
    }

    @Test
    fun `unliking leaves the pair without a like`() {
        val user = userRepository.save(User())
        val product = registerProduct()
        likeService.like(userId = user.id, productId = product.id)
        entityManager.flushAndClear()

        likeService.unlike(userId = user.id, productId = product.id)
        entityManager.flushAndClear()

        assertThat(entityManager.countLikes(user.id, product.id)).isZero()
    }

    @Test
    fun `unliking without a like succeeds and changes nothing`() {
        val user = userRepository.save(User())
        val product = registerProduct()
        entityManager.flushAndClear()

        likeService.unlike(userId = user.id, productId = product.id)
        entityManager.flushAndClear()

        assertThat(entityManager.countLikes(user.id, product.id)).isZero()
    }

    @Test
    fun `unliking leaves the other user's like in place`() {
        val user = userRepository.save(User())
        val other = userRepository.save(User())
        val product = registerProduct()
        likeService.like(userId = user.id, productId = product.id)
        likeService.like(userId = other.id, productId = product.id)
        entityManager.flushAndClear()

        likeService.unlike(userId = user.id, productId = product.id)
        entityManager.flushAndClear()

        assertAll(
            { assertThat(entityManager.countLikes(user.id, product.id)).isZero() },
            { assertThat(entityManager.countLikes(other.id, product.id)).isOne() },
        )
    }

    @Test
    fun `liking a deleted product throws PRODUCT_NOT_FOUND and saves nothing`() {
        val user = userRepository.save(User())
        val product = registerProduct().apply { delete() }
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { likeService.like(userId = user.id, productId = product.id) }
        entityManager.flushAndClear()

        assertAll(
            { assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND) },
            { assertThat(entityManager.countLikes(user.id, product.id)).isZero() },
        )
    }

    @Test
    fun `liking an unknown product throws PRODUCT_NOT_FOUND`() {
        val user = userRepository.save(User())

        val exception = assertThrows<CoreException> { likeService.like(userId = user.id, productId = 999L) }

        assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
    }

    /** 삭제된 상품에 남은 좋아요는 그대로 두되 취소는 허용한다. 취소는 상품의 존재를 보지 않는다. */
    @Test
    fun `unliking a deleted product still lets the remaining like go`() {
        val user = userRepository.save(User())
        val product = registerProduct()
        likeService.like(userId = user.id, productId = product.id)
        product.delete()
        entityManager.flushAndClear()

        likeService.unlike(userId = user.id, productId = product.id)
        entityManager.flushAndClear()

        assertThat(entityManager.countLikes(user.id, product.id)).isZero()
    }

    @Test
    fun `liking as an unknown user throws UNAUTHORIZED and saves nothing`() {
        val product = registerProduct()
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { likeService.like(userId = 999L, productId = product.id) }
        entityManager.flushAndClear()

        assertAll(
            { assertThat(exception.errorType).isEqualTo(ErrorType.UNAUTHORIZED) },
            { assertThat(entityManager.countLikes(999L, product.id)).isZero() },
        )
    }

    @Test
    fun `unliking as an unknown user throws UNAUTHORIZED and leaves the like in place`() {
        val user = userRepository.save(User())
        val product = registerProduct()
        likeRepository.save(Like(userId = user.id, productId = product.id))
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { likeService.unlike(userId = 999L, productId = product.id) }
        entityManager.flushAndClear()

        assertAll(
            { assertThat(exception.errorType).isEqualTo(ErrorType.UNAUTHORIZED) },
            { assertThat(entityManager.countLikes(user.id, product.id)).isOne() },
        )
    }

    /**
     * 요청자 구분. 서비스가 받는 사용자 식별자는 요청자 하나뿐이라 남의 목록을 내줄 길이 없고,
     * 같은 상품을 둘이 눌러도 각자의 목록에는 자기 관계만 오른다.
     */
    @Test
    fun `the like list gives only the user's own likes`() {
        val user = userRepository.save(User())
        val other = userRepository.save(User())
        val mine = registerProduct()
        val theirs = registerProduct()
        likeService.like(userId = user.id, productId = mine.id)
        likeService.like(userId = other.id, productId = theirs.id)
        entityManager.flushAndClear()

        val slice = likeService.findLikedProducts(user.id, LikeListRequest())

        assertThat(slice.items.map { it.id }).containsExactly(mine.id)
    }

    /** 삭제된 상품은 없는 상품이라 목록에서 빠진다. 좋아요 행은 남아 있어 취소할 수 있다(ADR 0001). */
    @Test
    fun `the like list leaves out a product that was deleted after it was liked`() {
        val user = userRepository.save(User())
        val active = registerProduct()
        val deleted = registerProduct()
        likeService.like(userId = user.id, productId = active.id)
        likeService.like(userId = user.id, productId = deleted.id)
        deleted.delete()
        entityManager.flushAndClear()

        val slice = likeService.findLikedProducts(user.id, LikeListRequest())

        assertAll(
            { assertThat(slice.items.map { it.id }).containsExactly(active.id) },
            { assertThat(entityManager.countLikes(user.id, deleted.id)).isOne() },
        )
    }

    /**
     * 조각의 차례와 `hasNext`는 [com.loopers.infrastructure.product.ProductRepositoryTest]가 SQL로 이미 고정한다.
     * 여기서는 입력이 조각까지 이어지는지와, 트랜잭션 안에서만 읽을 수 있는 값이 항목에 실리는지를 본다(설계 6).
     * 좋아요 수는 상품에 걸린 관계의 개수이므로 요청자의 것만 세지 않는다.
     */
    @Test
    fun `the like list carries the default page and size into the slice and fills the brand name and like count`() {
        val user = userRepository.save(User())
        val other = userRepository.save(User())
        val product = registerProduct()
        likeService.like(userId = user.id, productId = product.id)
        likeService.like(userId = other.id, productId = product.id)
        entityManager.flushAndClear()

        val slice = likeService.findLikedProducts(user.id, LikeListRequest())

        assertAll(
            { assertThat(slice.page).isEqualTo(LikeListRequest.DEFAULT_PAGE) },
            { assertThat(slice.size).isEqualTo(LikeListRequest.DEFAULT_SIZE) },
            { assertThat(slice.hasNext).isFalse() },
            { assertThat(slice.items.single().brandName).isEqualTo("루퍼스") },
            { assertThat(slice.items.single().name).isEqualTo("티셔츠") },
            { assertThat(slice.items.single().likeCount).isEqualTo(2L) },
        )
    }

    /**
     * 조각에 몇 개가 담기든 조회는 셋이다. 요청자 확인 하나, 상품과 브랜드를 함께 읽는 조각 하나, 좋아요 수 집계 하나.
     * 항목마다 브랜드를 읽거나 좋아요를 세면 조각 크기만큼 늘어난다(설계 5.28, 5.29).
     * 통계를 실행 중에 켜고 끄는 까닭은 [com.loopers.application.product.ProductServiceTest]와 같다.
     */
    @Test
    fun `the like list reads a slice of any size in three queries`() {
        val user = userRepository.save(User())
        val products = List(3) { registerProduct() }
        products.forEach { likeService.like(userId = user.id, productId = it.id) }
        entityManager.flushAndClear()
        val statistics = entityManager.statistics
        statistics.isStatisticsEnabled = true
        statistics.clear()

        try {
            val slice = likeService.findLikedProducts(user.id, LikeListRequest())

            assertAll(
                { assertThat(slice.items).hasSize(3) },
                { assertThat(slice.items.map { it.brandName }).containsOnly("루퍼스") },
                { assertThat(slice.items.map { it.likeCount }).containsOnly(1L) },
                { assertThat(statistics.prepareStatementCount).isEqualTo(3L) },
            )
        } finally {
            statistics.isStatisticsEnabled = false
        }
    }

    /** 요청자가 없으면 목록도 볼 수 없다. 누르기·취소와 같은 검사다(설계 5.27). */
    @Test
    fun `listing likes as an unknown user throws UNAUTHORIZED`() {
        val exception = assertThrows<CoreException> { likeService.findLikedProducts(999L, LikeListRequest()) }

        assertThat(exception.errorType).isEqualTo(ErrorType.UNAUTHORIZED)
    }

    @Test
    fun `listing likes outside the page and size bounds is rejected by request validation`() {
        val user = userRepository.save(User())
        entityManager.flushAndClear()

        assertAll(
            {
                assertThat(
                    assertThrows<ConstraintViolationException> {
                        likeService.findLikedProducts(user.id, LikeListRequest(page = -1))
                    }.constraintViolations.map { it.message },
                ).containsExactly("page는 0 이상이어야 합니다.")
            },
            {
                assertThat(
                    assertThrows<ConstraintViolationException> {
                        likeService.findLikedProducts(user.id, LikeListRequest(size = 0))
                    }.constraintViolations.map { it.message },
                ).containsExactly("size는 1 이상이어야 합니다.")
            },
            {
                assertThat(
                    assertThrows<ConstraintViolationException> {
                        likeService.findLikedProducts(user.id, LikeListRequest(size = 101))
                    }.constraintViolations.map { it.message },
                ).containsExactly("size는 100 이하여야 합니다.")
            },
        )
    }

    private fun registerProduct(): Product {
        val brand = brandRepository.save(Brand("루퍼스"))
        return productRepository.save(Product(brand = brand, name = "티셔츠", price = Money(10_000), stock = Stock(1)))
    }
}
