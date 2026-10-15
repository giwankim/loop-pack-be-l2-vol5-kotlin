package com.loopers.application.product.provided

import com.loopers.domain.product.Product
import org.springframework.data.domain.Slice

/**
 * 고객 목록의 결과. 고객이 보는 항목과 조각의 위치만 싣는다. [ProductFinder.findAll]의 고객 목록이 돌려주고,
 * 관리자 목록과 내 좋아요 목록은 그대로 [ProductInfo]의 [Slice]를 돌려준다.
 *
 * [ProductInfo]를 그대로 쓰지 않는 까닭: 재고 수량과 등록·수정 시각이 들어 있다. 품절을 바꾸지 않는 재고 쓰기는
 * 목록을 바꾸지 않으므로(ADR 0020), 그 값을 들고 있는 결과는 캐시된 동안 DB와 어긋난다. 고객 응답은 그 필드를 싣지 않지만
 * 캐시된 값이 틀린 값을 들고 있게 된다. 그래서 이 읽기 경로에 자기 조회 모델을 둔다(설계 5.7의 다시 볼 조건).
 *
 * Spring Data의 [Slice]가 아니라 순수한 값이다. 캐시가 Jackson으로 그대로 쓰고 읽는다.
 * 모양을 바꾸면 캐시 키의 판도 올린다(ADR 0020).
 */
data class ProductListInfo(
    val items: List<ProductSummaryInfo>,
    val page: Int,
    val size: Int,
    val hasNext: Boolean,
) {
    companion object {
        /** 조각의 항목·위치·크기·다음 조각의 존재를 그대로 옮긴다. 항목은 부르는 쪽이 [Slice.map]으로 먼저 옮긴다. */
        fun from(slice: Slice<ProductSummaryInfo>): ProductListInfo {
            return ProductListInfo(
                items = slice.content,
                page = slice.number,
                size = slice.size,
                hasNext = slice.hasNext(),
            )
        }
    }
}

/**
 * 고객 목록의 항목. 고객 상품 응답이 내보내는 필드만 있다. 브랜드는 연관을 건너 읽고 좋아요 수는 다른 저장소에서 세어 오므로
 * [ProductInfo]처럼 트랜잭션 안에서 값으로 옮긴다.
 *
 * 품절 여부는 `Product`가 정한 값을 그대로 옮기며 여기서 세지 않는다. [likeCount]는 `Product`가 모르는 값이라 만드는 쪽이 넘긴다.
 */
data class ProductSummaryInfo(
    val id: Long,
    val name: String,
    val price: Long,
    val soldOut: Boolean,
    val brandId: Long,
    val brandName: String,
    val likeCount: Long,
) {
    companion object {
        fun from(product: Product, likeCount: Long): ProductSummaryInfo {
            return ProductSummaryInfo(
                id = product.id,
                name = product.name,
                price = product.price.amount,
                soldOut = product.isSoldOut(),
                brandId = product.brand.id,
                brandName = product.brand.name,
                likeCount = likeCount,
            )
        }
    }
}
