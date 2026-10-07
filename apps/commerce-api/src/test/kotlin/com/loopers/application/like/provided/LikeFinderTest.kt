package com.loopers.application.like.provided

import com.loopers.application.brand.required.BrandRepository
import com.loopers.application.product.required.ProductRepository
import com.loopers.application.user.required.UserRepository
import com.loopers.domain.brand.createBrand
import com.loopers.domain.product.createProduct
import com.loopers.domain.user.User
import com.loopers.support.countLikes
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.flushAndClear
import com.loopers.support.stereotype.ApplicationServiceTest
import com.loopers.support.withStatistics
import jakarta.persistence.EntityManager
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [LikeFinder]를 실제 MySQL 위에서 확인한다. 목록에 오를 좋아요는 같은 조각의 [Liker]로 만든다.
 * 정리와 flush/clear, 관계를 `likes` 테이블에서 세는 까닭은 [LikerTest]와 같다.
 */
@ApplicationServiceTest
class LikeFinderTest(
    private val likeFinder: LikeFinder,
    private val liker: Liker,
    private val userRepository: UserRepository,
    private val brandRepository: BrandRepository,
    private val productRepository: ProductRepository,
    private val entityManager: EntityManager,
) {
    /**
     * 요청자 구분. 포트가 받는 사용자 식별자는 요청자 하나뿐이라 남의 목록을 내줄 길이 없고,
     * 같은 상품을 둘이 눌러도 각자의 목록에는 자기 관계만 오른다.
     */
    @Test
    fun `the like list gives only the user's own likes`() {
        val user = userRepository.save(User())
        val other = userRepository.save(User())
        val brand = brandRepository.save(createBrand())
        val mine = productRepository.save(createProduct(brand))
        val theirs = productRepository.save(createProduct(brand))
        liker.like(userId = user.id, productId = mine.id)
        liker.like(userId = other.id, productId = theirs.id)
        entityManager.flushAndClear()

        val slice = likeFinder.findLikedProducts(user.id, LikeListRequest())

        assertThat(slice.content.map { it.id }).containsExactly(mine.id)
    }

    /** 삭제된 상품은 없는 상품이라 목록에서 빠진다. 좋아요 행은 남아 있어 취소할 수 있다(ADR 0001). */
    @Test
    fun `the like list leaves out a product that was deleted after it was liked`() {
        val user = userRepository.save(User())
        val brand = brandRepository.save(createBrand())
        val active = productRepository.save(createProduct(brand))
        val deleted = productRepository.save(createProduct(brand))
        liker.like(userId = user.id, productId = active.id)
        liker.like(userId = user.id, productId = deleted.id)
        deleted.delete()
        entityManager.flushAndClear()

        val slice = likeFinder.findLikedProducts(user.id, LikeListRequest())

        assertThat(slice.content.map { it.id }).containsExactly(active.id)
        assertThat(entityManager.countLikes(user.id, deleted.id)).isOne()
    }

    /**
     * 조각의 차례와 `hasNext`는 [com.loopers.application.product.required.ProductRepositoryTest]가 SQL로 이미 고정한다.
     * 여기서는 입력이 조각까지 이어지는지와, 트랜잭션 안에서만 읽을 수 있는 값이 항목에 실리는지를 본다(설계 6).
     * 좋아요 수는 상품에 걸린 관계의 개수이므로 요청자의 것만 세지 않는다.
     */
    @Test
    fun `the like list carries the default page and size into the slice and fills the brand name and like count`() {
        val user = userRepository.save(User())
        val other = userRepository.save(User())
        val brand = brandRepository.save(createBrand(name = "루퍼스"))
        val product = productRepository.save(createProduct(brand, name = "티셔츠"))
        liker.like(userId = user.id, productId = product.id)
        liker.like(userId = other.id, productId = product.id)
        entityManager.flushAndClear()

        val slice = likeFinder.findLikedProducts(user.id, LikeListRequest())

        assertThat(slice.number).isEqualTo(LikeListRequest.DEFAULT_PAGE)
        assertThat(slice.size).isEqualTo(LikeListRequest.DEFAULT_SIZE)
        assertThat(slice.hasNext()).isFalse()
        assertThat(slice.content.single().brandName).isEqualTo("루퍼스")
        assertThat(slice.content.single().name).isEqualTo("티셔츠")
        assertThat(slice.content.single().likeCount).isEqualTo(2L)
    }

    /**
     * 조각에 몇 개가 담기든 조회는 셋이다. 요청자 확인 하나, 상품과 브랜드를 함께 읽는 조각 하나, 좋아요 수 집계 하나.
     * 항목마다 브랜드를 읽거나 좋아요를 세면 조각 크기만큼 늘어난다(설계 5.28, 5.29).
     */
    @Test
    fun `the like list reads a slice of any size in three queries`() {
        val user = userRepository.save(User())
        val products = List(3) { productRepository.save(createProduct(brandRepository.save(createBrand()))) }
        products.forEach { liker.like(userId = user.id, productId = it.id) }
        entityManager.flushAndClear()

        entityManager.withStatistics { statistics ->
            val slice = likeFinder.findLikedProducts(user.id, LikeListRequest())

            assertThat(slice.content).hasSize(3)
            assertThat(slice.content.map { it.brandName })
                .containsExactlyInAnyOrderElementsOf(products.map { it.brand.name })
            assertThat(slice.content.map { it.likeCount }).containsOnly(1L)
            assertThat(statistics.prepareStatementCount).isEqualTo(3L)
        }
    }

    /** 요청자가 없으면 목록도 볼 수 없다. 누르기·취소와 같은 검사다(설계 5.27). */
    @Test
    fun `listing likes as an unknown user throws UNAUTHORIZED`() {
        val exception = assertThrows<CoreException> { likeFinder.findLikedProducts(999L, LikeListRequest()) }

        assertThat(exception.errorType).isEqualTo(ErrorType.UNAUTHORIZED)
    }

    @Test
    fun `listing likes outside the page and size bounds is rejected by request validation`() {
        val user = userRepository.save(User())
        entityManager.flushAndClear()

        assertThat(
            assertThrows<ConstraintViolationException> {
                likeFinder.findLikedProducts(user.id, LikeListRequest(page = -1))
            }.constraintViolations.map { it.message },
        ).containsExactly("page는 0 이상이어야 합니다.")
        assertThat(
            assertThrows<ConstraintViolationException> {
                likeFinder.findLikedProducts(user.id, LikeListRequest(size = 0))
            }.constraintViolations.map { it.message },
        ).containsExactly("size는 1 이상이어야 합니다.")
        assertThat(
            assertThrows<ConstraintViolationException> {
                likeFinder.findLikedProducts(user.id, LikeListRequest(size = 101))
            }.constraintViolations.map { it.message },
        ).containsExactly("size는 100 이하여야 합니다.")
    }
}
