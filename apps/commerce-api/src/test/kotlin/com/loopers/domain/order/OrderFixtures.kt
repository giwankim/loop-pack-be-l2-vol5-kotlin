package com.loopers.domain.order

import com.loopers.application.order.provided.OrderConfirmRequest
import com.loopers.application.order.provided.OrderCreateRequest
import com.loopers.domain.product.Product
import com.loopers.domain.shared.Money
import org.instancio.kotlin.KInstancio
import org.instancio.kotlin.KInstancio.gen
import org.instancio.kotlin.KSelect.field
import java.time.Instant

/**
 * 저장된 상품의 ID·이름·가격을 스냅숏하는 주문 품목. 상품은 다른 애그리거트라 테스트가 넘긴다.
 * 수량은 1..10개라 기본 재고(100..1,000개)를 넘지 않는다.
 */
fun createOrderProduct(product: Product, quantity: Int? = null): OrderProduct {
    return OrderProduct(
        productId = product.id,
        productName = product.name,
        unitPrice = product.price,
        quantity = quantity ?: gen().ints().range(1, 10).get(),
    )
}

/**
 * 상품 없이 만드는 주문 품목. 상품이 필요 없는 Order 규칙 테스트가 쓴다. 상품 ID가 외래 키에 걸리므로 저장하지 않는다.
 * 상품 ID는 `Long` 전 범위의 양수라 따로 뽑은 품목끼리 겹치지 않는다. 이름 2..100자, 단가 1..1,000,000,000원(상품 가격 규칙),
 * 수량 1..10개다.
 */
fun createOrderProduct(
    productId: Long? = null,
    productName: String? = null,
    unitPrice: Money? = null,
    quantity: Int? = null,
): OrderProduct {
    return OrderProduct(
        productId = productId ?: gen().longs().range(1, Long.MAX_VALUE).get(),
        productName = productName ?: gen().string().minLength(2).maxLength(100).get(),
        unitPrice = unitPrice ?: Money(gen().longs().range(1, 1_000_000_000).get()),
        quantity = quantity ?: gen().ints().range(1, 10).get(),
    )
}

/**
 * 저장하지 않은 주문. 진짜 생성자를 불러 품목 정렬·합계·`DRAFT` 시작을 생성자에 맡긴다(ADR 0010). 사용자 ID는 다른
 * 애그리거트라 테스트가 넘긴다. 기본 품목은 상품 없이 만든 1..5개이고 상품이 서로 다르며, 품목 금액이
 * 10,000,000,000원 이하라 합계가 넘치지 않는다. 기본 품목은 상품 외래 키에 걸리므로 저장할 주문은 품목을 넘긴다.
 */
fun createOrder(userId: Long, products: List<OrderProduct>? = null): Order {
    return Order(
        userId = userId,
        products = products ?: generateSequence { createOrderProduct() }
            .distinctBy { it.productId }
            .take(gen().ints().range(1, 5).get())
            .toList(),
    )
}

/**
 * An unsaved order in [status], reached by calling the transitions that lead there rather than writing the status. A confirmed
 * order is one paid through the PG, confirmed at [confirmedAt] or a drawn past instant. No transition leads to an order
 * confirmed with points any more (ADR 0027): such orders exist only as stored rows, so the tests that read stored rows cover
 * them. User ID 1 and [products] go to [createOrder].
 */
fun createOrderIn(status: OrderStatus, products: List<OrderProduct>? = null, confirmedAt: Instant? = null): Order {
    return when (status) {
        OrderStatus.DRAFT -> createOrder(userId = 1, products = products)

        OrderStatus.PAYMENT_PENDING -> createOrderIn(OrderStatus.DRAFT, products).apply { startPayment() }

        OrderStatus.CONFIRMED -> createOrderIn(OrderStatus.PAYMENT_PENDING, products).apply {
            completePayment(confirmedAt ?: gen().temporal().instant().past().get())
        }

        OrderStatus.PAYMENT_FAILED -> createOrderIn(OrderStatus.PAYMENT_PENDING, products).apply { failPayment() }
    }
}

/**
 * 주문 생성 요청. 넘긴 상품마다 품목 하나를 그 차례대로 두고 수량을 1..10개로 뽑는다. 상품 ID는 다른 애그리거트라
 * 테스트가 넘긴다. `@Positive` 수량에는 상한이 없어 기본 재고(100..1,000개)를 넘지 않게 범위를 정한다.
 */
fun createOrderCreateRequest(productIds: List<Long>, quantity: Int? = null): OrderCreateRequest {
    return KInstancio.of<OrderCreateRequest>()
        .set(
            field(OrderCreateRequest::items),
            productIds.map { productId ->
                KInstancio.of<OrderCreateRequest.Item>()
                    .set(field(OrderCreateRequest.Item::productId), productId)
                    .set(field(OrderCreateRequest.Item::quantity), quantity ?: gen().ints().range(1, 10).get())
                    .create()
            },
        )
        .create()
}

/**
 * 상품마다 수량이 다른 주문 생성 요청. 넘긴 상품과 수량의 짝마다 품목 하나를 그 차례대로 둔다. 재고·합계 계산에 기대는
 * 테스트가 품목마다 수량을 적을 때 쓴다. 수량이 `null`인 품목은 1..10개를 뽑는다.
 */
fun createOrderCreateRequest(vararg items: Pair<Long, Int?>): OrderCreateRequest {
    return KInstancio.of<OrderCreateRequest>()
        .set(
            field(OrderCreateRequest::items),
            items.map { (productId, quantity) ->
                KInstancio.of<OrderCreateRequest.Item>()
                    .set(field(OrderCreateRequest.Item::productId), productId)
                    .set(field(OrderCreateRequest.Item::quantity), quantity ?: gen().ints().range(1, 10).get())
                    .create()
            },
        )
        .create()
}

/**
 * 주문 확정 요청. 카드 종류는 테스트 PG가 받는 셋 가운데 하나이고, 카드 번호는 Luhn 검사를 지나는 16자리 숫자다.
 * 확정이 PG에 그대로 넘기므로 테스트는 같은 카드로 생성 스텁을 맞춘다.
 */
fun createOrderConfirmRequest(cardType: String? = null, cardNo: String? = null): OrderConfirmRequest {
    return KInstancio.of<OrderConfirmRequest>()
        .set(field(OrderConfirmRequest::cardType), cardType ?: gen().oneOf("SAMSUNG", "KB", "HYUNDAI").get())
        .set(field(OrderConfirmRequest::cardNo), cardNo ?: gen().finance().creditCard().get())
        .create()
}
