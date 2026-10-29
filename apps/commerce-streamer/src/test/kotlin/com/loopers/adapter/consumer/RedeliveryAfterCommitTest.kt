package com.loopers.adapter.consumer

import ch.qos.logback.classic.Level
import com.loopers.adapter.consumer.OrderEventsListenerConfig.Companion.ORDER_EVENTS_LISTENER
import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecordRequest
import com.loopers.domain.purchaseactivity.createPurchaseActivityRecordRequest
import com.loopers.support.ExperimentBroker
import com.loopers.support.orderConfirmedValue
import com.loopers.support.test.BaseConsumerTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.kafka.clients.consumer.CloseOptions
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.kafka.support.converter.ByteArrayJacksonJsonMessageConverter
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * Step 3's redelivery experiment (issue 184, seam B): a record whose recording committed but whose offset did not is delivered
 * again to the group's next consumer, at the same partition and offset, and recording it again writes nothing (`CONTEXT.md`
 * 다시 전달되다). [OrderEventsListenerTest]'s `the same event published twice …` is the other way an event comes again: a new
 * record of the same event at the next offset (다시 발행하다).
 *
 * The scene has to hold the consumer's poll, close and commit, which a listener container hides, so it runs no container.
 * Plain consumers from the order events listener factory's consumer factory (`ByteArrayDeserializer`,
 * `enable.auto.commit=false`), each with a client ID of its own, read a topic and a group of the experiment's own, which
 * [ExperimentBroker] creates and deletes. The base's listener container reads the class's topic, so it never records the
 * experiment's record first. A record reaches the listener as its container would hand it over: the shared
 * `ByteArrayJacksonJsonMessageConverter` reads it into an [OrderEventMessage] with the record's key, partition and offset as
 * headers, and the scene calls the listener bean's [OrderEventsListener.listen] with them. The event type check, the schema
 * check, the recorder and the log lines are the real ones.
 *
 * The steps are the assignment's six. The event is published, and its partition and offset n come from the send metadata.
 * The first consumer joins the group, commits n as it is assigned the partition, and so reads from n. The listener records it
 * and acknowledges, and the scene's acknowledgment only notes the call. The first consumer closes, bounded. With auto-commit
 * off and no commit pending, the close commits nothing: kafka-clients 4.2.2's `ConsumerCoordinator.close` commits only for
 * auto-commit. A second consumer joins the same group with no seek and no reset and is given n again. The listener finds the
 * same fact and writes nothing, and this time the acknowledgment commits n + 1 through that consumer.
 *
 * Neither consumer seeks or resets: each reads from the offset the group committed. A record of another order goes in just
 * before the event, so the earliest offset is not n. A consumer that reset to the earliest offset (`auto.offset.reset`) would
 * be given that record instead, and a rewind would show the same way. The topic has one partition, so there is no other
 * partition on which the group has committed nothing and a consumer would reset.
 *
 * The evidence is what the broker and the tables say: both deliveries' partition and offset, the group's committed offset
 * read from the broker after each step (n, n, n, n + 1), both tables unchanged by the second delivery, and the listener's
 * `RECORDED` and `ALREADY_RECORDED` lines. A consumer's position is its own, not the group's, so it shows only that the close
 * had a position past n it could have committed.
 *
 * Every poll runs on the test thread (`pollInSameThread`). A Kafka consumer is not safe for use from two threads at once, and
 * Awaitility otherwise evaluates a condition on a thread of its own, which a timed-out wait can leave inside `poll` while the
 * test closes the consumer. Both consumers are closed before [ExperimentBroker] deletes the group, which must have no member.
 *
 * The scene logs what it observed in one INFO line. Run it with
 * `./gradlew :apps:commerce-streamer:test --tests 'com.loopers.adapter.consumer.RedeliveryAfterCommitTest'`.
 */
@TestPropertySource(properties = ["order-events.topic=order-events-redelivery-after-commit"])
class RedeliveryAfterCommitTest(
    private val kafkaAdmin: KafkaAdmin,
    private val kafkaTemplate: KafkaTemplate<Any, Any>,
    @Qualifier(ORDER_EVENTS_LISTENER)
    private val factory: ConcurrentKafkaListenerContainerFactory<*, *>,
    private val converter: ByteArrayJacksonJsonMessageConverter,
    private val listener: OrderEventsListener,
) : BaseConsumerTest() {
    companion object {
        /**
         * One, so the consumers are assigned the record's partition and nothing else. On any other partition the group would
         * have committed nothing, and a consumer would reset to the earliest offset there.
         */
        private const val PARTITIONS = 1

        /** How long a send waits for the broker, and a consumer polls for its assignment or a record. */
        private val RECORD_WAIT: Duration = Duration.ofSeconds(10)

        /** How long one poll blocks, so a wait checks often. */
        private val POLL_TIMEOUT: Duration = Duration.ofMillis(100)

        /** How long a close may take to leave the group. */
        private val CLOSE_WAIT: Duration = Duration.ofSeconds(10)
    }

    private lateinit var broker: ExperimentBroker

    private lateinit var topic: String

    private lateinit var group: String

    private val consumers = mutableListOf<GroupConsumer>()

    @BeforeEach
    fun openTheBroker() {
        broker = ExperimentBroker(kafkaAdmin)
        topic = broker.createTopic("redelivery-after-commit", PARTITIONS)
        group = broker.nameGroup("redelivery-after-commit")
    }

    /** Closes every consumer before the broker deletes the group, which it refuses while the group has a member. */
    @AfterEach
    fun closeTheConsumersAndTheBroker() {
        broker.use {
            consumers.forEach { it.close() }
        }
    }

    @Test
    fun `a record recorded but not acknowledged is redelivered at its offset to the group's next consumer and writes nothing`() {
        val earlier = send(createPurchaseActivityRecordRequest())
        val event = createPurchaseActivityRecordRequest()
        val sent = send(event)
        val partition = TopicPartition(topic, sent.partition())
        val committed = linkedMapOf<String, Long?>()

        val first = joinTheGroup("redelivery-first", partition) { it.commit(partition, sent.offset()) }
        committed["before the first read"] = experimentCommittedOffset(partition)
        val firstDelivery = first.take()
        var acknowledgeCalled = false
        callTheListener(firstDelivery, first) { acknowledgeCalled = true }
        committed["after the first read"] = experimentCommittedOffset(partition)
        val rowsAfterTheFirstRead = storedRows()
        val positionBeforeTheClose = first.position(partition)
        first.close()
        committed["after the first close"] = experimentCommittedOffset(partition)

        val second = joinTheGroup("redelivery-second", partition)
        val secondDelivery = second.take()
        callTheListener(secondDelivery, second) { second.commit(partition, secondDelivery.offset() + 1) }
        committed["after the second read"] = experimentCommittedOffset(partition)

        assertThat(earlier.offset()).isLessThan(sent.offset())
        assertThat(listOf(firstDelivery, secondDelivery).map { it.partition() to it.offset() })
            .containsExactly(sent.partition() to sent.offset(), sent.partition() to sent.offset())
        assertThat(acknowledgeCalled).isTrue()
        assertThat(positionBeforeTheClose).isEqualTo(sent.offset() + 1)
        assertThat(committed).containsExactly(
            entry("before the first read", sent.offset()),
            entry("after the first read", sent.offset()),
            entry("after the first close", sent.offset()),
            entry("after the second read", sent.offset() + 1),
        )
        assertOnlyTheRowsOf(event)
        assertThat(storedRows()).isEqualTo(rowsAfterTheFirstRead)
        assertThat(listenerLines(Level.INFO)).containsExactly(
            "Recorded the order confirmed event. eventId=${event.eventId}, orderId=${event.orderId}, " +
                "partition=${sent.partition()}, offset=${sent.offset()}, result=RECORDED",
            "Recorded the order confirmed event. eventId=${event.eventId}, orderId=${event.orderId}, " +
                "partition=${sent.partition()}, offset=${sent.offset()}, result=ALREADY_RECORDED",
        )
        logTheScene(earlier, sent, positionBeforeTheClose, committed)
    }

    /** Publishes [event] to the experiment's topic keyed by its order ID, as commerce-api's relay would, and returns where. */
    private fun send(event: PurchaseActivityRecordRequest): RecordMetadata {
        return kafkaTemplate.send(topic, event.orderId.toString(), orderConfirmedValue(event))
            .get(RECORD_WAIT.toSeconds(), TimeUnit.SECONDS)
            .recordMetadata
    }

    /**
     * A new consumer of the experiment's group under [clientId], polled until the broker has assigned it [partition].
     * [onAssigned] runs on the assignment, inside the poll, before the consumer looks up the group's committed offset to read
     * from.
     */
    private fun joinTheGroup(
        clientId: String,
        partition: TopicPartition,
        onAssigned: (GroupConsumer) -> Unit = {},
    ): GroupConsumer {
        val consumer = GroupConsumer(clientId)
        consumers += consumer
        consumer.joinUntilAssigned(partition) { onAssigned(consumer) }

        return consumer
    }

    /**
     * Hands [record], read by [consumer], to the listener as its container would. The shared converter reads it into a message
     * of the listener's payload type with the record's key, partition and offset among the headers, and the listener bean is
     * called with those and [acknowledgment].
     */
    private fun callTheListener(record: ConsumerRecord<*, *>, consumer: GroupConsumer, acknowledgment: Acknowledgment) {
        val message = converter.toMessage(record, acknowledgment, consumer.client, OrderEventMessage::class.java)
        val headers = message.headers

        listener.listen(
            message.payload as OrderEventMessage,
            headers[KafkaHeaders.RECEIVED_KEY] as String?,
            headers[KafkaHeaders.RECEIVED_PARTITION] as Int,
            headers[KafkaHeaders.OFFSET] as Long,
            acknowledgment,
        )
    }

    /**
     * The experiment's group's committed offset on [partition], as the broker answers it. Not the base's `committedOffset`,
     * which reads the streamer's group on the class's topic.
     */
    private fun experimentCommittedOffset(partition: TopicPartition): Long? {
        return broker.committedOffset(group, partition)
    }

    /**
     * Both tables as stored, a line per row with its ID and update time, so a second read shows whether anything was inserted or
     * updated in between.
     */
    private fun storedRows(): List<String> {
        val confirmations = storedConfirmations().map {
            "processed_order_confirmation id=${it.id}, event_id=${it.eventId}, order_id=${it.orderId}, " +
                "confirmed_at=${it.confirmedAt}, updated_at=${it.updatedAt}"
        }
        val activities = storedActivities().map {
            "purchase_activity id=${it.id}, order_id=${it.orderId}, product_id=${it.productId}, quantity=${it.quantity}, " +
                "confirmed_at=${it.confirmedAt}, updated_at=${it.updatedAt}"
        }

        return confirmations + activities
    }

    /**
     * Logs what the scene observed: the two records, each consumer and what it was given, the committed offsets, the listener's
     * lines and the rows. The class shares the listener's package, so its own line reaches the base's appender too, and the
     * listener's are read first.
     */
    private fun logTheScene(
        earlier: RecordMetadata,
        sent: RecordMetadata,
        positionBeforeTheClose: Long,
        committed: Map<String, Long?>,
    ) {
        val listenerLines = listenerLines(Level.INFO).joinToString("\n") { "|listener: $it" }
        val deliveries = consumers.joinToString("\n") {
            "|${it.clientId} (member ${it.memberId}): given partition=${it.taken.partition()}, offset=${it.taken.offset()}"
        }
        val offsets = committed.entries.joinToString { "${it.key}=${it.value}" }
        val rows = storedRows().joinToString("\n") { "|$it" }

        log.info {
            """
            |E3 redelivery after commit
            |topic=${sent.topic()}, partitions=$PARTITIONS, group=$group
            |earlier record of another order: partition=${earlier.partition()}, offset=${earlier.offset()}
            |the event's record: partition=${sent.partition()}, offset=${sent.offset()}
            $deliveries
            |position of ${consumers.first().clientId} before its close=$positionBeforeTheClose
            |committed offset of the group on partition ${sent.partition()}: $offsets
            $listenerLines
            $rows
            """.trimMargin()
        }
    }

    /**
     * A plain consumer of the experiment's group, built by the listener factory's consumer factory under [clientId]. A poll
     * that completes the join can fetch the record as well, so what each poll fetches is kept until the scene takes it.
     */
    private inner class GroupConsumer(val clientId: String) : AutoCloseable {
        /** The Kafka client itself, which the converter also puts in the message's headers, as a container's does. */
        val client: Consumer<*, *> = checkNotNull(factory.consumerFactory).createConsumer(group, clientId, null)

        /** The member ID the broker gave this consumer when it joined, as the client holds it. Logged, not asserted. */
        lateinit var memberId: String

        /** The record the scene took from this consumer. */
        lateinit var taken: ConsumerRecord<*, *>

        private val fetched = ArrayDeque<ConsumerRecord<*, *>>()

        /**
         * Subscribes to the experiment's topic and polls until the broker assigns this consumer [partition]. The client calls
         * [onAssigned] on its polling thread once the group has assigned it partitions, and only then fetches the committed
         * offsets it reads from.
         */
        fun joinUntilAssigned(partition: TopicPartition, onAssigned: () -> Unit) {
            client.subscribe(
                listOf(topic),
                object : ConsumerRebalanceListener {
                    override fun onPartitionsAssigned(partitions: Collection<TopicPartition>) {
                        onAssigned()
                    }

                    override fun onPartitionsRevoked(partitions: Collection<TopicPartition>) {
                    }
                },
            )
            pollUntil("assigned $partition") { partition in client.assignment() }
            memberId = client.groupMetadata().memberId()
        }

        /** Polls until a record has been fetched, and takes it. */
        fun take(): ConsumerRecord<*, *> {
            pollUntil("given a record") { fetched.isNotEmpty() }
            taken = fetched.removeFirst()

            return taken
        }

        fun commit(partition: TopicPartition, offset: Long) {
            client.commitSync(mapOf(partition to OffsetAndMetadata(offset)))
        }

        fun position(partition: TopicPartition): Long {
            return client.position(partition)
        }

        /** Leaves the group within [CLOSE_WAIT]. Closing again does nothing. */
        override fun close() {
            client.close(CloseOptions.timeout(CLOSE_WAIT))
        }

        /** Polls until [condition] holds, keeping what each poll fetched. A timeout names this consumer and [what] it awaited. */
        private fun pollUntil(what: String, condition: () -> Boolean) {
            await("$clientId $what").atMost(RECORD_WAIT).pollInSameThread().until {
                client.poll(POLL_TIMEOUT).forEach { fetched += it }
                condition()
            }
        }
    }
}
