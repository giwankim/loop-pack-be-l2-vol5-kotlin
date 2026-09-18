package com.loopers.interfaces.api.v1.order

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.loopers.application.order.OrderCreateRequest
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.boot.jackson.JsonComponent

/**
 * 이 입력 타입의 JSON 토큰만 검사한다. 다른 타입의 숫자·배열 바인딩 설정은 바꾸지 않는다(설계 5.10).
 *
 * 거절은 [CoreException]을 원인으로 실은 [JsonMappingException]이다. Spring이 `HttpMessageNotReadableException`으로
 * 감싸면 공용 [com.loopers.interfaces.api.ApiControllerAdvice]가 가장 안쪽 원인을 풀어 그 [ErrorType]으로 답한다(설계 13.1).
 * 스스로 `JsonProcessingException`을 만드는 까닭은 이 역직렬화기가 타입 전체를 맡아 Jackson이 감싸 줄 bean 경계가 없기 때문이다.
 */
@JsonComponent
class OrderCreateRequestDeserializer : JsonDeserializer<OrderCreateRequest>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): OrderCreateRequest {
        val root = parser.codec.readTree<JsonNode>(parser)
        val items = root.get("items")
        if (!root.isObject || items == null || !items.isArray) invalid(parser)
        return OrderCreateRequest(
            items.map { item ->
                val productId = item.get("productId")
                val quantity = item.get("quantity")
                if (!item.isObject || productId == null || !productId.isIntegralNumber || !productId.canConvertToLong() ||
                    quantity == null || !quantity.isIntegralNumber || !quantity.canConvertToInt()
                ) {
                    invalid(parser)
                }
                OrderCreateRequest.Item(productId.longValue(), quantity.intValue())
            },
        )
    }

    private fun invalid(parser: JsonParser): Nothing = throw JsonMappingException.from(
        parser,
        "상품 ID와 수량은 정수 숫자이며 items는 배열이어야 합니다.",
        CoreException(ErrorType.INVALID_POINT_ORDER_REQUEST),
    )
}
