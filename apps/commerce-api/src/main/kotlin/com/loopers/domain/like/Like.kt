package com.loopers.domain.like

import com.loopers.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * 사용자와 상품 사이의 관계. 같은 사용자–상품 쌍은 하나만 있고, DB 유일 제약 `(user_id, product_id)`이 그것을 지킨다.
 *
 * 사용자와 상품을 식별자로만 가리킨다(설계 2). 상품을 객체로 참조하면 상품의 삭제 필터가 좋아요 조회에도 붙어,
 * 삭제된 상품에 남은 좋아요를 취소할 수 없게 된다. 규칙은 관계 자체보다 유스케이스에 있다(`LikeService`).
 *
 * [BaseEntity]를 상속하지만 `delete()`를 쓰지 않는다. 취소는 저장소에서 행을 지운다(ADR 0001).
 * 테이블 이름이 `likes`인 까닭은 `like`가 SQL 예약어라서다.
 */
@Entity
@Table(
    name = "likes",
    uniqueConstraints = [UniqueConstraint(name = "uk_likes_user_id_product_id", columnNames = ["user_id", "product_id"])],
)
class Like(
    userId: Long,
    productId: Long,
) : BaseEntity() {
    /** 누른 사용자. */
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: Long = userId

    /** 대상 상품. */
    @Column(name = "product_id", nullable = false, updatable = false)
    val productId: Long = productId
}
