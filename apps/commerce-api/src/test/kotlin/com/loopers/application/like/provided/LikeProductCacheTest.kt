package com.loopers.application.like.provided

import com.loopers.application.product.provided.ProductFinder
import com.loopers.application.product.provided.ProductListRequest
import com.loopers.application.product.required.ProductListRevisionRepository
import com.loopers.domain.like.createLikeRequest
import com.loopers.support.Pause
import com.loopers.support.captureSql
import com.loopers.support.countLikes
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.loopers.support.withStatistics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * 좋아요가 고객 목록의 캐시를 무효로 만드는지 실제 MySQL·Redis 위에서 확인한다(ADR 0020).
 * 좋아요 수는 관계에서 세므로(`CONTEXT.md` 좋아요 수) 누르기와 취소가 목록의 좋아요 수와 `likes_desc` 차례를 바꾼다.
 *
 * 무효화는 커밋 앞의 훅이 revision을 올려야 보이므로 커밋하는 기반을 쓴다. `prepareLike`도 커밋하며 revision을 올리므로,
 * 캐시는 준비를 마친 뒤에 채운다.
 */
class LikeProductCacheTest(
    private val liker: Liker,
    private val productFinder: ProductFinder,
    private val productListRevisionRepository: ProductListRevisionRepository,
) : BaseCommittingApplicationServiceTest() {
    private val pause = Pause()

    /** 테스트가 풀기 전에 실패해도 revision을 쥔 쪽을 풀어, 기반의 정리가 그 트랜잭션이 끝나기를 오래 기다리지 않게 한다. */
    @AfterEach
    fun releasePause() {
        pause.release()
    }

    /**
     * 두 상품의 좋아요가 하나씩 같아 나중에 등록된 양말이 앞선다. 티셔츠에 좋아요가 하나 더 커밋되면 다시 읽은 목록에서
     * 티셔츠의 좋아요 수가 하나 늘고 차례가 뒤집힌다.
     */
    @Test
    fun `after a like commits the list shows the count up by one and the new likes_desc order`() {
        prepareBrand()
        val shirt = prepareProduct(brand)
        val socks = prepareProduct(brand)
        prepareLike(product = shirt)
        prepareLike(product = socks)
        val request = ProductListRequest(sort = "likes_desc")
        val cached = productFinder.findAll(request)
        val fan = prepareUser()

        liker.like(userId = fan.id, request = createLikeRequest(productId = shirt.id))

        val reread = productFinder.findAll(request)
        assertThat(cached.items.map { it.id to it.likeCount }).containsExactly(socks.id to 1L, shirt.id to 1L)
        assertThat(reread.items.map { it.id to it.likeCount }).containsExactly(shirt.id to 2L, socks.id to 1L)
    }

    @Test
    fun `after an unlike commits the list shows the count down by one`() {
        prepareProduct()
        prepareLike(product = product)
        val liked = prepareLike(product = product)
        val cached = productFinder.findAll(ProductListRequest())

        liker.unlike(userId = liked.userId, productId = product.id)

        val reread = productFinder.findAll(ProductListRequest())
        assertThat(cached.items.single().likeCount).isEqualTo(2L)
        assertThat(reread.items.single().likeCount).isOne()
    }

    /**
     * 이미 누른 상품을 다시 눌러 관계가 그대로여도 revision이 오른다. 앞의 읽기가 hit의 한 문장이라 캐시가 차 있었는데도,
     * 다음 읽기는 miss의 세 문장이다.
     */
    @Test
    fun `liking an already liked product still moves the revision so the next read misses`() {
        prepareLike()
        productFinder.findAll(ProductListRequest())
        assertThatTheNextListReadSends(statements = 1L)
        val before = currentListRevision()

        liker.like(userId = user.id, request = createLikeRequest(productId = product.id))

        assertThat(currentListRevision()).isEqualTo(before + 1)
        assertThatTheNextListReadSends(statements = 3L)
    }

    /** 관계가 없는 취소도 revision을 올린다. 앞의 읽기가 hit의 한 문장이라 캐시가 차 있었는데도, 다음 읽기는 miss의 세 문장이다. */
    @Test
    fun `unliking without a like still moves the revision so the next read misses`() {
        prepareUser()
        prepareProduct()
        productFinder.findAll(ProductListRequest())
        assertThatTheNextListReadSends(statements = 1L)
        val before = currentListRevision()

        liker.unlike(userId = user.id, productId = product.id)

        assertThat(currentListRevision()).isEqualTo(before + 1)
        assertThatTheNextListReadSends(statements = 3L)
    }

    /**
     * 취소는 좋아요 행을 잠그지 않고 읽으므로 DELETE가 flush에서 처음 그 행을 잠근다. 훅이 먼저 flush하므로 DELETE가 올리기보다
     * 앞서고, 올리기가 마지막 문장이다. 그래서 revision을 쥔 트랜잭션은 좋아요 행을 기다리지 않는다(ADR 0020 규칙 3).
     */
    @Test
    fun `the delete of the like comes before the upsert and the upsert is the last statement of an unlike`() {
        prepareLike()

        val captured = captureSql { liker.unlike(userId = user.id, productId = product.id) }

        assertThat(captured.statements.takeLast(2)).satisfiesExactly(
            { assertThat(it).startsWith("delete from likes ") },
            { assertThat(it).startsWith("insert into product_list_revision") },
        )
    }

    /**
     * 다른 트랜잭션이 revision 행을 올린 채 커밋 앞에서 멈춰 있으면, 누르기는 커밋 앞의 올리기에서 그 행을 기다린다.
     * 쥔 쪽이 커밋하면 누르기도 이어 가 좋아요를 남긴다. 쥔 쪽은 목록을 바꾸는 쓰기 대신 revision을 직접 올린다.
     * 그 잠금은 Service의 단계가 아니라 커밋 앞의 훅에서 잡히므로 Service를 spy해 멈출 자리가 없다.
     */
    @Test
    fun `a like waits while another transaction holds the revision row and succeeds after release`() {
        prepareUser()
        prepareProduct()
        inAnotherThread {
            inNewTransaction {
                productListRevisionRepository.raise()
                pause.hold()
            }
        }
        pause.awaitHeld()

        val liking = inAnotherThread { liker.like(userId = user.id, request = createLikeRequest(productId = product.id)) }

        assertThat(liking.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        liking.await()
        assertThat(inNewTransaction { entityManager.countLikes(user.id, product.id) }).isOne()
    }

    /** hit는 revision 하나, miss는 revision·페이지·좋아요 수의 세 문장이다([com.loopers.application.product.provided.ProductCacheTest]). */
    private fun assertThatTheNextListReadSends(statements: Long) {
        entityManager.withStatistics { statistics ->
            productFinder.findAll(ProductListRequest())

            assertThat(statistics.prepareStatementCount).isEqualTo(statements)
        }
    }
}
