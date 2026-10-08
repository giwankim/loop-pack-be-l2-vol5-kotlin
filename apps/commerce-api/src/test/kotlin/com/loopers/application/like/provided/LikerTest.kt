package com.loopers.application.like.provided

import com.loopers.support.countLikes
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseApplicationServiceTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [Liker]를 실제 MySQL 위에서 확인한다. 정리와 flush/clear의 까닭은 [com.loopers.application.brand.provided.BrandRegisterTest]와 같다.
 *
 * 관계가 있는지는 저장 약속을 거치지 않고 `likes` 테이블을 SQL로 센다. 두 번 눌러도 행이 하나이고
 * 취소가 행을 지운다는 약속은 테이블에서 봐야 한다(ADR 0001).
 */
class LikerTest(
    private val liker: Liker,
) : BaseApplicationServiceTest() {
    @Test
    fun `liking an active product for the first time saves one like for the user and product`() {
        prepareUser()
        prepareProduct()
        entityManager.flushAndClear()

        liker.like(userId = user.id, productId = product.id)
        entityManager.flushAndClear()

        assertThat(entityManager.countLikes(user.id, product.id)).isOne()
    }

    @Test
    fun `liking the same product again succeeds and keeps one like`() {
        prepareLike()
        entityManager.flushAndClear()

        liker.like(userId = user.id, productId = product.id)
        entityManager.flushAndClear()

        assertThat(entityManager.countLikes(user.id, product.id)).isOne()
    }

    @Test
    fun `liking as another user adds a second like for the product`() {
        val me = prepareUser()
        val other = prepareUser()
        prepareProduct()
        prepareLike(me, product)
        entityManager.flushAndClear()

        liker.like(userId = other.id, productId = product.id)
        entityManager.flushAndClear()

        assertThat(entityManager.countLikes(me.id, product.id)).isOne()
        assertThat(entityManager.countLikes(other.id, product.id)).isOne()
    }

    @Test
    fun `unliking leaves the pair without a like`() {
        prepareLike()
        entityManager.flushAndClear()

        liker.unlike(userId = user.id, productId = product.id)
        entityManager.flushAndClear()

        assertThat(entityManager.countLikes(user.id, product.id)).isZero()
    }

    @Test
    fun `unliking without a like succeeds and changes nothing`() {
        prepareUser()
        prepareProduct()
        entityManager.flushAndClear()

        liker.unlike(userId = user.id, productId = product.id)
        entityManager.flushAndClear()

        assertThat(entityManager.countLikes(user.id, product.id)).isZero()
    }

    @Test
    fun `unliking leaves the other user's like in place`() {
        val me = prepareUser()
        val other = prepareUser()
        prepareProduct()
        prepareLike(me, product)
        prepareLike(other, product)
        entityManager.flushAndClear()

        liker.unlike(userId = me.id, productId = product.id)
        entityManager.flushAndClear()

        assertThat(entityManager.countLikes(me.id, product.id)).isZero()
        assertThat(entityManager.countLikes(other.id, product.id)).isOne()
    }

    @Test
    fun `liking a deleted product throws PRODUCT_NOT_FOUND and saves nothing`() {
        prepareUser()
        prepareProduct()
        deleteProduct()
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { liker.like(userId = user.id, productId = product.id) }
        entityManager.flushAndClear()

        assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
        assertThat(entityManager.countLikes(user.id, product.id)).isZero()
    }

    @Test
    fun `liking an unknown product throws PRODUCT_NOT_FOUND`() {
        prepareUser()

        val exception = assertThrows<CoreException> { liker.like(userId = user.id, productId = 999L) }

        assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
    }

    /** 삭제된 상품에 남은 좋아요는 그대로 두되 취소는 허용한다. 취소는 상품의 존재를 보지 않는다. */
    @Test
    fun `unliking a deleted product still lets the remaining like go`() {
        prepareLike()
        deleteProduct()
        entityManager.flushAndClear()

        liker.unlike(userId = user.id, productId = product.id)
        entityManager.flushAndClear()

        assertThat(entityManager.countLikes(user.id, product.id)).isZero()
    }
}
