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
import jakarta.persistence.EntityManager
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

    private fun registerProduct(): Product {
        val brand = brandRepository.save(Brand("루퍼스"))
        return productRepository.save(Product(brand = brand, name = "티셔츠", price = Money(10_000), stock = Stock(1)))
    }
}
