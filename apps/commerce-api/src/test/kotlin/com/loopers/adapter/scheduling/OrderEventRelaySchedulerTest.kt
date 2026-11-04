package com.loopers.adapter.scheduling

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.integration.kafka.OrderEventsProperties
import com.loopers.application.order.required.OrderEventOutboxRepository
import com.loopers.domain.order.OrderEventOutboxStatus
import com.loopers.support.EnableTestPg
import com.loopers.support.TestPg
import com.loopers.support.TopicReader
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.wiremock.spring.InjectWireMock
import java.time.Duration

/**
 * With the property on, a committed row reaches the broker with no test calling the port. This is the one scene that waits on real
 * time: the rest of the relay's scenes run rounds by hand, with the trigger off.
 *
 * `@DirtiesContext` is what keeps those scenes honest. Spring caches a context after its class ends, and this one's scheduler would go
 * on claiming from the shared MySQL while later classes ran, publishing the rows they expect to find `PENDING`.
 */
@EnableTestPg
@TestPropertySource(properties = ["order-events.relay.enabled=true", "order-events.topic=order-events-relay-scheduled"])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderEventRelaySchedulerTest(
    private val orderEventOutboxRepository: OrderEventOutboxRepository,
    private val kafkaProperties: KafkaProperties,
    private val orderEventsProperties: OrderEventsProperties,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        /** A tick comes within a second of the commit. The rest is room for a slow machine, and the wait ends as soon as the row is saved. */
        private val PUBLISH_WAIT: Duration = Duration.ofSeconds(10)
    }

    /** The test PG's server. Like [com.loopers.application.order.provided.OrderEventRelayerTest], WireMock's listener injects it into a field. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    private lateinit var orderEvents: TopicReader

    @BeforeEach
    fun readTheTopicFromItsEnd() {
        orderEvents = TopicReader(kafkaProperties.bootstrapServers, orderEventsProperties.topic)
    }

    @AfterEach
    fun closeTheTopicReader() {
        orderEvents.close()
    }

    @Test
    fun `with the property on, a committed row becomes published without a test calling the port`() {
        prepareOrder()

        confirmOrder(testPg)

        await().atMost(PUBLISH_WAIT).until { committedOutboxStatus() == OrderEventOutboxStatus.PUBLISHED }
        val publishedRecord = orderEvents.publishedRecords(expected = 1).single()
        assertThat(publishedRecord.key()).isEqualTo(order.id.toString())
    }

    private fun committedOutboxStatus(): OrderEventOutboxStatus {
        return inNewTransaction { orderEventOutboxRepository.findByEventId("order-confirmed:v1:${order.id}")!!.status }
    }
}
