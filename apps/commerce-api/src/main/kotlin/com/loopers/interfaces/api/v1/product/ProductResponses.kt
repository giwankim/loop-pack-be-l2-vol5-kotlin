package com.loopers.interfaces.api.v1.product

import com.loopers.application.product.ProductInfo

/**
 * 고객 상품 응답. 같은 [ProductInfo]를 읽지만 관리자와 달리 재고 수량과 등록·수정 시각은 내보내지 않고,
 * 남은 수량 대신 품절 여부만 준다(설계 5.7). 품절 여부는 `Product`가 정한 값을 그대로 옮기며 여기서 세지 않는다.
 *
 * 고객이 상품을 볼 때 브랜드 이름을 함께 보므로 브랜드를 [ProductBrandResponse]로 안에 담는다.
 * 좋아요 수도 고객만 본다. 관계를 세어 `ProductInfo`에 실린 값을 그대로 옮기며 여기서 세지 않는다.
 */
data class ProductResponse(
    val id: Long,
    val name: String,
    val price: Long,
    val soldOut: Boolean,
    val brand: ProductBrandResponse,
    val likeCount: Long,
) {
    companion object {
        fun from(info: ProductInfo): ProductResponse =
            ProductResponse(
                id = info.id,
                name = info.name,
                price = info.price,
                soldOut = info.soldOut,
                brand = ProductBrandResponse(id = info.brandId, name = info.brandName),
                likeCount = info.likeCount,
            )
    }
}

/**
 * 상품 응답 안의 브랜드. 브랜드 상세의 [com.loopers.interfaces.api.v1.brand.BrandResponse]와 지금은 필드가 같지만
 * 나누어 둔다. 브랜드 상세가 나중에 필드를 더해도 상품 목록의 항목이 따라 커지지 않게 하려는 것이다.
 *
 * 중첩 클래스가 아니라 옆에 두는 것은 한 트리 안에서 이름이 겹치지 않게 하려는 것이다(설계 5.21과 같은 까닭).
 * `Brand`라는 이름은 `domain.brand.Brand`와, `BrandResponse`는 고객 브랜드 응답과 겹쳐 KDoc 링크가 어디를
 * 가리키는지 import에 따라 달라진다.
 */
data class ProductBrandResponse(
    val id: Long,
    val name: String,
)
