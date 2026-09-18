package com.loopers.domain.product

/**
 * 상품 목록의 정렬 기준. [apiValue]가 API에서 쓰는 유일한 철자이고 상수 이름은 밖으로 나가지 않는다.
 * 어느 기준이든 동률은 id 내림차순으로 깬다. 무엇을 읽어 그렇게 만드는지는 저장소가 안다.
 */
enum class ProductSort(val apiValue: String) {
    /** 늦게 등록된 상품이 앞선다. 아무것도 고르지 않았을 때의 기준이다. */
    LATEST("latest"),

    /** 싼 상품이 앞선다. */
    PRICE_ASC("price_asc"),

    /**
     * 좋아요가 많은 상품이 앞선다. 좋아요가 하나도 없는 상품도 0으로 목록에 있다.
     *
     * 다른 둘과 달리 정렬 키가 상품의 컬럼이 아니라 관계를 세어 나오는 값이다. 그래도 도메인에서는
     * 기준 하나일 뿐이고, 무엇을 세는지는 저장소가 안다(설계 5.29).
     */
    LIKES_DESC("likes_desc"),
    ;

    companion object {
        /**
         * 밖에서 온 철자를 기준으로 옮긴다. 모르는 철자면 null이다.
         *
         * 모르는 값이 400인지 아닌지는 부르는 쪽이 정한다. 도메인은 전송 방식을 모르므로
         * 여기서 예외를 던지지 않는다([com.loopers.application.product.ProductService]가
         * `INVALID_SORT`로 옮긴다).
         */
        fun from(value: String): ProductSort? = entries.find { it.apiValue == value }
    }
}
