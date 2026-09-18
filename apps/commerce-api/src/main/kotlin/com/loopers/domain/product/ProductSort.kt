package com.loopers.domain.product

/**
 * 상품 목록의 정렬 기준. [apiValue]가 API에서 쓰는 유일한 철자이고 상수 이름은 밖으로 나가지 않는다.
 * 어느 기준이든 동률은 id 내림차순으로 깬다. 어느 컬럼을 읽어 그렇게 만드는지는 저장소가 안다.
 *
 * 좋아요 수 정렬(`likes_desc`)은 좋아요가 생기는 티켓에서 더한다.
 */
enum class ProductSort(val apiValue: String) {
    /** 늦게 등록된 상품이 앞선다. 아무것도 고르지 않았을 때의 기준이다. */
    LATEST("latest"),

    /** 싼 상품이 앞선다. */
    PRICE_ASC("price_asc"),
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
