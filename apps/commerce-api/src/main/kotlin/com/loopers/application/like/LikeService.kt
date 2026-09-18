package com.loopers.application.like

import com.loopers.domain.like.Like
import com.loopers.domain.like.LikeRepository
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.user.UserRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 좋아요 누르기·취소. 관계 자체에는 규칙이 없고 유스케이스가 순서를 정한다(도메인 문서 좋아요).
 *
 * [userId]는 요청자, 곧 `X-USER-ID` 헤더가 실어 준 사용자 식별자다. 헤더가 없는 것은 interfaces가 401로 거절하고,
 * 그 사용자가 있는지는 여기서 본다. Controller를 거치지 않는 호출도 같은 검사를 받게 하려는 것이다(설계 5.25, 5.27).
 */
@Service
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

    /** 요청자가 가리키는 사용자가 없으면 요청자가 없는 것이다. */
    private fun checkUserExists(userId: Long) {
        if (!userRepository.existsById(userId)) {
            throw CoreException(ErrorType.UNAUTHORIZED)
        }
    }
}
