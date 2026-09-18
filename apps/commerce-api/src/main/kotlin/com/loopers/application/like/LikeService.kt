package com.loopers.application.like

import com.loopers.application.product.ProductInfo
import com.loopers.domain.like.Like
import com.loopers.domain.like.LikeRepository
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.shared.PageSlice
import com.loopers.domain.user.UserRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.validation.Valid
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

/**
 * 좋아요 누르기·취소. 관계 자체에는 규칙이 없고 유스케이스가 순서를 정한다(도메인 문서 좋아요).
 *
 * [userId]는 요청자, 곧 `X-USER-ID` 헤더가 실어 준 사용자 식별자다. 헤더가 없는 것은 interfaces가 401로 거절하고,
 * 그 사용자가 있는지는 여기서 본다. Controller를 거치지 않는 호출도 같은 검사를 받게 하려는 것이다(설계 5.25, 5.27).
 */
@Service
@Validated
class LikeService(
    private val likeRepository: LikeRepository,
    private val productRepository: ProductRepository,
    private val userRepository: UserRepository,
) {
    /**
     * 삭제되지 않은 상품에 요청자의 관계를 만든다. 이미 있으면 그대로 두고 성공으로 답한다(설계 5.6).
     * 삭제된 상품은 없는 상품이므로 저장소가 이미 걸러 주고, 없으면 여기서 거절한다.
     */
    @Transactional
    fun like(userId: Long, productId: Long) {
        checkUserExists(userId)
        productRepository.findById(productId) ?: throw CoreException(ErrorType.PRODUCT_NOT_FOUND)
        if (likeRepository.existsByUserIdAndProductId(userId, productId)) return

        likeRepository.save(Like(userId = userId, productId = productId))
    }

    /**
     * 요청자의 관계를 없앤다. 없어도 성공으로 답한다(설계 5.6). 행을 지우며 논리 삭제를 쓰지 않는다(ADR 0001).
     * 상품의 존재는 보지 않는다. 삭제된 상품에 남은 좋아요도 취소되어야 하기 때문이다.
     */
    @Transactional
    fun unlike(userId: Long, productId: Long) {
        checkUserExists(userId)
        likeRepository.findByUserIdAndProductId(userId, productId)?.let { likeRepository.delete(it) }
    }

    /**
     * 요청자가 좋아요를 누른, 삭제되지 않은 상품 한 조각. 최근에 누른 상품이 앞선다.
     *
     * 받는 사용자 식별자는 요청자 하나뿐이라 남의 목록을 내줄 길이 없다. 경로가 가리키는 사용자와 요청자가
     * 같은지는 둘 다 HTTP가 실은 값이라 interfaces가 본다(설계 5.30).
     *
     * 항목은 고객 상품 목록의 항목과 같은 [ProductInfo]다. 브랜드 이름을 연관에서 건너 읽으므로 옮기는 일이
     * 트랜잭션 안에서 끝나야 한다(설계 5.7). 좋아요 수는 조각의 식별자 목록에 대해 한 번에 센다(설계 5.28).
     */
    @Transactional(readOnly = true)
    fun findLikedProducts(userId: Long, @Valid request: LikeListRequest): PageSlice<ProductInfo> {
        checkUserExists(userId)
        val slice = productRepository.findAllLikedBy(userId = userId, page = request.page, size = request.size)
        val likeCounts = likeRepository.countByProductIds(slice.items.map { it.id })
        return slice.map { ProductInfo.from(it, likeCount = likeCounts.getValue(it.id)) }
    }

    /** 요청자가 가리키는 사용자가 없으면 요청자가 없는 것이다. */
    private fun checkUserExists(userId: Long) {
        if (!userRepository.existsById(userId)) {
            throw CoreException(ErrorType.UNAUTHORIZED)
        }
    }
}
