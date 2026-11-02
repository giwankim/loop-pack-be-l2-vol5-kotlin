package com.loopers.application.order.provided

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.integration.kafka.OrderConfirmedMessage
import com.loopers.application.order.required.OrderEventOutboxRepository
import com.loopers.domain.order.OrderEventOutbox
import com.loopers.domain.order.OrderEventOutboxStatus
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.createOrderConfirmRequest
import com.loopers.domain.product.Product
import com.loopers.support.EnableTestPg
import com.loopers.support.TestClock
import com.loopers.support.TestPg
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.wiremock.spring.InjectWireMock
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * The order slice leaves the first confirmation of an order paid through the PG in one outbox row, in the transaction that
 * confirms the order (ADR 0034 rules 1 and 8). The scenes call the port on the real MySQL with the test PG, and read the row
 * in a new transaction, so only what committed answers. Every scene pins the clock, so the instants are literals.
 */
@EnableTestPg
class OrderEventOutboxTest(
    private val orderConfirmer: OrderConfirmer,
    private val orderEventOutboxRepository: OrderEventOutboxRepository,
    private val jsonMapper: JsonMapper,
    private val clock: TestClock,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        private const val IDEMPOTENCY_KEY = "confirm-key-1"

        /** Where a scene pins the clock when the form of the instant is not its subject. Already in microseconds. */
        private val CONFIRMED_AT: Instant = Instant.parse("2026-10-27T01:02:03.123456Z")
    }

    /** The test PG's server. Like [OrderPaymentIntegrationTest], WireMock's listener injects it into a field. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    /**
     * The event columns are the facts of order-outbox design 2. The payload is compared as a whole string, because the field
     * order is part of what the row keeps: the body of the wire contract, its items in ascending product ID order whichever
     * order the customer listed them in, and no envelope.
     */
    @Test
    fun `a confirmation whose create answers success leaves one row holding the event's columns and body`() {
        val cheaper = prepareProduct(price = 1_000)
        val pricier = prepareProduct(price = 2_000)

        val row = confirmLeavingARow(CONFIRMED_AT, pricier to 1, cheaper to 2)

        assertThat(row.eventId).isEqualTo("order-confirmed:v1:${order.id}")
        assertThat(row.orderId).isEqualTo(order.id)
        assertThat(row.eventType).isEqualTo("OrderConfirmed")
        assertThat(row.schemaVersion).isEqualTo(1)
        assertThat(row.messageKey).isEqualTo(order.id.toString())
        assertThat(row.payload).isEqualTo(
            """{"orderId":${order.id},"confirmedAt":"2026-10-27T01:02:03.123456Z",""" +
                """"items":[{"productId":${cheaper.id},"quantity":2},{"productId":${pricier.id},"quantity":1}]}""",
        )
    }

    /** The first state of every row (ADR 0034 rule 8): nothing has tried to publish it, and it is due at the step's clock. */
    @Test
    fun `the row starts pending with no attempts, due at the instant the clock is pinned to`() {
        val row = confirmLeavingARow(CONFIRMED_AT, prepareProduct(price = 1_000) to 2)

        assertThat(row.status).isEqualTo(OrderEventOutboxStatus.PENDING)
        assertThat(row.attemptCount).isZero()
        assertThat(row.nextAttemptAt).isEqualTo(Instant.parse("2026-10-27T01:02:03.123456Z"))
        assertThat(row.publishedAt).isNull()
        assertThat(row.lastError).isNull()
    }

    /**
     * ISO-8601 drops trailing zeros by threes, so a confirmation time has these three forms on the wire. The stored string must
     * read back as the same value in each, since step 2 rebuilds the message from it (ADR 0034 rule 9).
     */
    @ParameterizedTest
    @ValueSource(strings = ["2026-10-27T01:02:03.123456Z", "2026-10-27T01:02:03.100Z", "2026-10-27T01:02:03Z"])
    fun `the stored payload reads back as the body it was written from`(confirmedAt: String) {
        val bought = prepareProduct(price = 1_000)

        val row = confirmLeavingARow(Instant.parse(confirmedAt), bought to 2)

        assertThat(row.payload).isEqualTo(
            """{"orderId":${order.id},"confirmedAt":"$confirmedAt","items":[{"productId":${bought.id},"quantity":2}]}""",
        )
        assertThat(jsonMapper.readValue(row.payload, OrderConfirmedMessage.Payload::class.java)).isEqualTo(
            OrderConfirmedMessage.Payload(
                orderId = order.id,
                confirmedAt = Instant.parse(confirmedAt),
                items = listOf(OrderConfirmedMessage.Payload.Item(productId = bought.id, quantity = 2)),
            ),
        )
    }

    /** Orders [items] and confirms the order at [confirmedAt] with a create the PG answers with success, and reads the only row. */
    private fun confirmLeavingARow(confirmedAt: Instant, vararg items: Pair<Product, Int>): OrderEventOutbox {
        prepareOrder(*items)
        val request = createOrderConfirmRequest()
        acceptNextCreate(testPg, amount = order.totalAmount.amount.longValueExact(), card = request.toCreditCard())
        clock.pin(confirmedAt)

        val confirmed = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(confirmed.order.status).isEqualTo(OrderStatus.CONFIRMED)

        return inNewTransaction {
            assertThat(orderEventOutboxRepository.count()).isOne()

            orderEventOutboxRepository.findByEventId("order-confirmed:v1:${order.id}")!!
        }
    }
}
