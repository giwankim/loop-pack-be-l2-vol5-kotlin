package com.loopers.application.product

import com.loopers.domain.like.LikeRepository
import com.loopers.domain.product.Product
import com.loopers.domain.shared.PageSlice
import org.springframework.stereotype.Component

/**
 * [ProductInfo]를 만드는 자리. 상품을 읽는 유스케이스가 둘로 갈렸고(상품 목록, 내 좋아요 목록) 옮기는 규칙은 하나라
 * 서비스마다 적지 않고 여기 모은다(설계 5.31).
 *
 * 도메인 서비스가 아니다. 돌려주는 [ProductInfo]는 층을 건너라고 있는 응답 모델이고, 조각의 좋아요 수를 한 번에
 * 세는 것은 읽는 쪽의 사정이다(설계 5.28). 좋아요 수를 묻는 일 자체는 domain의 [LikeRepository]에 있다.
 *
 * 브랜드 이름을 연관에서 건너 읽으므로 옮기는 일은 부르는 쪽의 트랜잭션 안에서 끝나야 한다(설계 5.7).
 */
@Component
class ProductInfoAssembler(
    private val likeRepository: LikeRepository,
) {
    /** 상품 하나. 좋아요 수는 관계를 세어 채운다. 관리자 응답도 이 count 쿼리 한 번을 치른다(설계 5.7). */
    fun toInfo(product: Product): ProductInfo =
        ProductInfo.from(product, likeCount = likeRepository.countByProductId(product.id))

    /**
     * 조각 하나. 좋아요 수는 조각의 식별자 목록에 대해 한 번에 센다. 항목마다 세면 조각 크기만큼 조회가 붙는다(설계 5.28).
     */
    fun toInfos(slice: PageSlice<Product>): PageSlice<ProductInfo> {
        val likeCounts = likeRepository.countByProductIds(slice.items.map { it.id })
        return slice.map { ProductInfo.from(it, likeCount = likeCounts.getValue(it.id)) }
    }
}
