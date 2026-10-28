package com.loopers.adapter.integration.kafka

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The topic order confirmed events are published to, under the top-level prefix `order-events` like `pg` (ADR 0032 rule 9).
 *
 * [partitions] is not a Kafka fact but a pairing: it matches the template listener's concurrency of 3, and the record listener
 * the streamer will consume with is to run 3 consumers too (order-events design 10). A test that reads the broker can set
 * [topic] to a name of its own to isolate itself on the shared broker.
 */
@ConfigurationProperties("order-events")
data class OrderEventsProperties(
    val topic: String = "order-events",
    val partitions: Int = 3,
)
