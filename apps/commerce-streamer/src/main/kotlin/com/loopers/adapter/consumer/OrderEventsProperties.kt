package com.loopers.adapter.consumer

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The topic the streamer consumes order confirmed events from, under the prefix `order-events` (purchase activity design 5).
 * It is the streamer's own copy of commerce-api's property of the same prefix: commerce-api declares and creates the topic,
 * and the streamer only reads it. A consumer test sets [topic] to a name of its own to isolate itself on the shared broker.
 */
@ConfigurationProperties("order-events")
data class OrderEventsProperties(
    val topic: String = DEFAULT_TOPIC,
) {
    companion object {
        /**
         * The default of [topic]. [OrderEventsListener] names its topic with a placeholder, which reads the `Environment`, and
         * a Kotlin default exists only in the binder, so the placeholder repeats it from here.
         */
        const val DEFAULT_TOPIC = "order-events"
    }
}
