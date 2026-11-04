# Week 7 outbox relay: the measurements

The measurements behind [ADR 0035](../adr/0035-the-relay-claims-rows-with-skip-locked-and-a-lease-and-keeps-a-result-per-row.md), taken from 2026-11-02 to 2026-11-04 during step 2's interview, on `e6a11ac`. They settled how the relay sends, claims and waits, and they overturned several of the first proposals.

## How it ran

Four throwaway runs, none of them in the suite. The test sources are at the end of this file. Issue 204 added a fifth on its own commit, described with its results in [the plan by table size](#plan-by-table-size). Issue 206 added a sixth, on the real jar, described in [through the real jar](#through-the-real-jar).

| Run | Stack | What it answers |
| --- | --- | --- |
| Timing | Testcontainers: `apache/kafka-native:4.4.0`, `mysql:8.4` | Ack and commit latency, relay shapes, how timeouts behave against a paused broker |
| Restart | Docker: `bitnamilegacy/kafka:3.5` (the local compose image), timed with `kafka-topics.sh --list` | How long a broker restart takes |
| Batch | Docker: `bitnamilegacy/kafka:3.5`, `mysql:8.4` | The retry budget, sends during real restarts, per-record results, `SKIP LOCKED` |
| First send | Docker: `bitnamilegacy/kafka:3.5`, three fresh JVMs | Whether `max.block.ms: 1000` fits a healthy first send, and the delivery timeout's floor |

**Limits.**

- One run per configuration on a laptop through Docker, so differences of a few percent mean nothing.
- `kafka-clients` 4.2.2 directly, not `KafkaTemplate`, and bare JDBC, not Hibernate. Throughput through the real stack will be lower, and that Hibernate emits `SKIP LOCKED` from the lock hint isn't shown here. (Both answered later: issue 200 pinned the statement, and [through the real jar](#through-the-real-jar) has the throughput.)
- A paused container stands in for a hung broker. The restart logs were nearly empty, so a real log may take longer to recover.
- The restart timings include about 1 s of CLI overhead.

## What it found

| Question | Finding | What it decided |
| --- | --- | --- |
| What does `linger.ms`'s default cost? | Ack p50 8.42 ms at 5 ms, 0.35 ms at 0, when sends wait one at a time (the default changed from 0 to 5 in Kafka 4.0) | A reason not to send one at a time. In batches it barely matters (480 vs 538 rows/s), so no override |
| One write per row or two? | 701 vs 417 rows/s, one commit's p50 being 0.94 ms | Two: the attempt's record is worth about one commit per row |
| How do shapes compare? | One at a time 417, batch with per-row marks 1,160, batch with a bulk mark 6,057 rows/s | Batches with per-row results. The bulk mark's speed comes with the hard part, partial failure |
| What ends a hung send? | The future failed at 10.0 s, 30.0 s and 120.0 s, as `delivery.timeout.ms` was set, within 2 ms. `kafka.yml` as it stands took 120 s despite `retries: 3` | One wait, with no local timeout |
| Can a local give-up be followed by success? | A 2 s local wait gave up, and the record was acked at 8.0 s | Another reason for no local timeout |
| How long does a restart take? | Graceful about 4 s, `kill -9` about 16 s | The delivery timeout must outlast about 16 s |
| Does a send survive a restart? | Acked at 2–3 s and 15–16 s, with `retries=3` and with the default | `retries: 3` can stay |
| What does `retries: 3` cost? | A fast retriable error gives up at 2.6 s, against 20.0 s with the default | Accepted: it reaches the relay's own retry, visibly |
| What does an outage cost a batch? | Send phase 0.001 s, then all 20 failed together at 20.0 s | An outage costs one delivery timeout per batch, not per row |
| What if metadata is missing? | Each `send()` blocks for the full `max.block.ms`: 5 × 2.0 s, returning a failed future | `max.block.ms` must be short, or a batch stalls for 20 × 60 s |
| Does 1 s fit a healthy first send? | 16–22 ms for a JVM's first send, at most 22 ms with the CPU saturated | `max.block.ms: 1000` |
| What is the floor for the delivery timeout? | With `request.timeout.ms` 20,000: 20,000 refused, 20,005 and 21,000 accepted | 21 s |
| Are results per record? | With one oversized record among 20, 19 acked and 1 failed with a non-retriable `RecordTooLargeException` | Row-by-row results |
| Do `SKIP LOCKED` claims overlap? | Two open claims saw 1..20 and 21..40, overlap 0 | `SKIP LOCKED` |
| What does the index do for it? | Without it the first claim locks everything it scans, and the second gets 0 rows | The index is required for correct behaviour |
| Does the index alone give the split? | No. Added 2026-11-04 by [issue 204](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/204): MySQL scanned the table up to 150 rows and used the index from 200, and at 45 rows a filesort left the second claim empty with the index in place. See [the plan by table size](#plan-by-table-size) | Necessary, not sufficient. Scenes about the split use 500 rows |
| Do two relays share the work? | 1,106 rows/s against 538 for one, each of 3,000 rows attempted once | The coordination works, and scales where `@Version` would collide |

## The failed run

In the batch run's section B, one graceful restart with the default retries failed at 20.3 s with `Topic probe not present in metadata after 20000 ms`. In that version `docker start` ran only after `send()` returned, so the broker stayed down for the whole wait, and the send's metadata wait was what timed out. Starting the broker in another thread gave 6 of 6 acked at 2.2–2.9 s. Why the warm producer had lost the topic's metadata wasn't established: a metadata response during shutdown is a guess.

## Outside sources

- [Kafka 4.2 `ProducerConfig`](https://raw.githubusercontent.com/apache/kafka/4.2/clients/src/main/java/org/apache/kafka/clients/producer/ProducerConfig.java): the defaults (`delivery.timeout.ms` 120 s, `max.block.ms` 60 s, `linger.ms` 5, `retries` `MAX_VALUE`), prefer `delivery.timeout.ms` over `retries`, and `request.timeout.ms` should exceed `replica.lag.time.max.ms`.
- [Kafka 4.2 `CommonClientConfigs`](https://raw.githubusercontent.com/apache/kafka/4.2/clients/src/main/java/org/apache/kafka/clients/CommonClientConfigs.java): `retry.backoff.ms` 100, `retry.backoff.max.ms` 1000.
- [gruelbox/transaction-outbox](https://github.com/gruelbox/transaction-outbox): selects a batch and handles entries individually, `blockAfterAttempts`, `unblock`, `SKIP LOCKED` for concurrent flushing on MySQL 8+.
- [namastack-outbox](https://github.com/namastack/namastack-outbox): `OutboxRecord`, `markCompleted`, `markFailed`, `retriesExhausted`. The JPA repository's search showed no `SKIP LOCKED`, and its files point to partition assignment.
- [Spring Modulith `EventPublicationRepository`](https://raw.githubusercontent.com/spring-projects/spring-modulith/main/spring-modulith-events/spring-modulith-events-core/src/main/java/org/springframework/modulith/events/core/EventPublicationRepository.java): `markCompleted`, `markFailed`, `markResubmitted`.
- [AxonOps outbox pattern](https://axonops.com/docs/data-platforms/kafka/application-development/patterns/outbox/): shows one-at-a-time with a break, and an all-or-nothing batch. Vendor documentation.

## Raw results

### Timing

```text
== 1. ack latency, send then await one record (1000 sends, first 100 discarded)
linger.ms=5  p50=8.42 p99=10.48 p99.9=11.71 max=19.13 ms
linger.ms=0  p50=0.35 p99=0.46 p99.9=0.72 max=4.62 ms
== 2. commit latency of one small UPDATE (1000 commits)
p50=0.94 p99=2.64 p99.9=4.16 max=7.49 ms
== 3. relay shapes over a 3000 row backlog
A one at a time, 1 write per row, linger 0                  3000 rows in   4.28 s =     701 rows/s
B one at a time, 2 writes per row, linger 0                 3000 rows in   7.19 s =     417 rows/s
B' one at a time, 2 writes per row, linger 5 (client default)  3000 rows in  26.90 s =     112 rows/s
C batch 20: bulk begin, await all, bulk publish             3000 rows in   0.50 s =    6057 rows/s
D batch 20: bulk begin, await all, publish per row          3000 rows in   2.59 s =    1160 rows/s
C100 batch 100: bulk begin, await all, bulk publish         3000 rows in   0.17 s =   17474 rows/s
D100 batch 100: bulk begin, await all, publish per row      3000 rows in   2.39 s =    1253 rows/s
== 4. hung broker (container paused), metadata already known: time until the future fails
request=5s delivery=10s: 10.0 s  send() returned in 0 ms; TimeoutException: Expiring 1 record(s) for probe-2:10002 ms has passed since batch creation
    next send after unpause: 0.00 s, ok=true
request=5s delivery=30s: 30.0 s  send() returned in 0 ms; TimeoutException: Expiring 1 record(s) for probe-2:30002 ms has passed since batch creation
    next send after unpause: 0.00 s, ok=true
request=20s delivery=30s: 30.0 s  send() returned in 0 ms; TimeoutException: Expiring 1 record(s) for probe-2:30001 ms has passed since batch creation
    next send after unpause: 0.00 s, ok=true
kafka.yml as is (request=20s retries=3, delivery default 120s): 120.0 s  send() returned in 0 ms; TimeoutException: Expiring 1 record(s) for probe-2:120001 ms has passed since batch creation
    next send after unpause: 0.01 s, ok=true
== 5. a pause shorter than delivery: rides through, or local give-up followed by late success
local await 2 s: TimeoutException, future.isDone=false
8 s pause, delivery=30s: acked 8.0 s after send, offset=7741 (the local wait had already given up)
== 6. no metadata yet (new producer) and hung broker: send() itself blocks
max.block=5s: 5.0 s  TimeoutException
max.block default 60s: 60.0 s  TimeoutException
== 7. delivery < request + linger, both explicit
request=5s delivery=4s: KafkaException delivery.timeout.ms should be equal to or larger than linger.ms + request.timeout.ms
== 8. broker gone (container stopped), metadata known: connection refused rather than hung
request=5s delivery=10s: 10.0 s  TimeoutException: Expiring 1 record(s) for probe-1:10001 ms has passed since batch creation
```

### Restart

```text
first start: 4.3 s
CLI overhead when already up: 1.0 s
graceful restart 1: stop+start 0.8 s, then ready after 3.0 s
graceful restart 2: stop+start 1.0 s, then ready after 3.2 s
graceful restart 3: stop+start 0.9 s, then ready after 3.1 s
kill -9 restart 1: ready after 15.7 s
kill -9 restart 2: ready after 15.7 s
kill -9 restart 3: ready after 16.0 s
```

### Batch, first run

It stopped in section F on a probe bug: the driver sent seed timestamps in local time and MySQL runs in UTC, so no rows were due.

```text
== A. a fast retriable error (NotEnoughReplicas, min.insync=2 on one broker), T=20 s
retries=3: 2.6 s  FAILED NotEnoughReplicasException retriable=true: Messages are rejected since there are fewer in-sync replicas than required.
retries=default(MAX): 20.0 s  FAILED TimeoutException retriable=true: Expiring 1 record(s) for probe-nir-0:20001 ms has passed since batch creation
== B. real restarts with a warm producer, T=20 s, send right after the broker goes down
retries=3 graceful #1: 2.2 s  ACKED
retries=3 graceful #2: 2.3 s  ACKED
retries=3 kill -9 #1: 15.2 s  ACKED
retries=3 kill -9 #2: 14.8 s  ACKED
retries=default(MAX) graceful #1: 2.4 s  ACKED
retries=default(MAX) graceful #2: 20.3 s  FAILED TimeoutException retriable=true: Topic probe not present in metadata after 20000 ms.
retries=default(MAX) kill -9 #1: 15.0 s  ACKED
retries=default(MAX) kill -9 #2: 15.7 s  ACKED
== C. a batch of 20 per-row results: one oversized record among healthy ones
acked=19 of 20 in 0.040 s; failed: [#7 FAILED RecordTooLargeException retriable=false: The message is 2097241 bytes when serialized which is larger than 1048576, which]
== D. a batch of 20 while the broker hangs (paused), metadata known: total time
send phase 0.001 s, then all awaited at 20.0 s, failed=20 of 20
== E. new producer, no metadata, broker stopped: does each send() in a batch block for max.block?
max.block=2000ms, 5 sends: [2.0s/failed-future, 2.0s/failed-future, 2.0s/failed-future, 2.0s/failed-future, 2.0s/failed-future]
== F. SKIP LOCKED and the index
with index: key=ix_due type=range rows=1 extra=Using index condition; Using where
```

### Batch, corrected run

```text
== B2. graceful restart, broker start racing the send, retries default, T=20 s
graceful #1: 2.3 s  ACKED
graceful #2: 2.2 s  ACKED
graceful #3: 2.9 s  ACKED
graceful #4: 2.9 s  ACKED
graceful #5: 2.2 s  ACKED
graceful #6: 2.6 s  ACKED
== F. SKIP LOCKED and the index
with index: key=ix_due type=range rows=2000 extra=Using index condition; Using where
two open claims: first=1..20 second=21..40 overlap=0
no index: key=null type=ALL rows=2000 extra=Using where; Using filesort
no index, two open claims: first=20 rows, second=0 rows (0 means the first claim locked everything it scanned)
== G. full shape: SKIP LOCKED claim of 20, send all, record each result in its own locked TX, 3000 rows
one relay, linger=0: 3000 rows in 5.58 s = 538 rows/s
one relay, linger=5: 3000 rows in 6.25 s = 480 rows/s
two relays: 1500 + 1500 rows in 2.71 s = 1106 rows/s; rows=3000 attempted once=3000 published=3000
```

### Plan by table size

Added by issue 204, after its first two-claims scene, at 45 rows, got an empty second claim with the index in place. This run differs from the ones above: a throwaway `BaseRepositoryTest` on `mysql:8.4`, on the real repository's `findDueForUpdate`, one run per line, all rows due at one instant. Its source wasn't saved. `EXPLAIN` ran on a hand-written copy of Hibernate's statement, with `deleted_at is null` and a literal time. `first` and `second` are row counts from the real query with the first claim held open.

```text
-- run 1, 45 rows first
rows=45  analyze=false first=20 second=0   key=null type=ALL   extra=Using where; Using filesort
rows=45  analyze=false first=20 second=20  key=null type=ALL   extra=Using where                   (each row due at its own instant, in reverse ID order)
rows=45  analyze=true  first=20 second=0   key=null type=ALL   extra=Using where; Using filesort
-- run 2, 500 rows first
rows=45  analyze=false first=20 second=20  key=null type=ALL   extra=Using where
rows=45  analyze=true  first=20 second=0   key=null type=ALL   extra=Using where; Using filesort
rows=60  both          first=20 second=20  key=null type=ALL   extra=Using where
rows=80  both          first=20 second=20  key=null type=ALL   extra=Using where
rows=100 both          first=20 second=20  key=null type=ALL   extra=Using where
rows=120 both          first=20 second=20  key=null type=ALL   extra=Using where
rows=150 both          first=20 second=20  key=null type=ALL   extra=Using where
rows=200 both          first=20 second=20  key=IDX  type=range extra=Using index condition; Using where
rows=300 both          first=20 second=20  key=IDX  type=range extra=Using index condition; Using where
rows=500 both          first=20 second=20  key=IDX  type=range extra=Using index condition; Using where
rows=2000 analyze=false first=20 second=20 key=IDX  type=range extra=Using index condition; Using where
rows=2000 analyze=true  first=20 second=20 key=IDX  type=index rows=20 extra=Using where
```

Below the flip, the second claim's rows owe nothing to the index. A table scan without a filesort walks the primary key, which here happens to be the sort order, and stops at the limit, so it leaves later rows unlocked. With a filesort it reads and locks them all. Which of the two the optimizer picks at 45 rows changed with the statistics, so the result there isn't something to rely on. The statement without `deleted_at is null` flipped elsewhere (a table scan at 200 rows, and at 2,000 until `ANALYZE`), so these numbers belong to this statement and this table.

By the mechanism, this failure is safe, though this run counted rows rather than checking for overlap: a claim that gets nothing takes nothing. The second relay's round ends early, and the rows wait for the first relay or the next trigger. The single relay never has a second claim to starve.

### First send

```text
-- JVM
very first send in this JVM blocked 22 ms
idle, 20 new producers: median 4 max 6 ms
CPU saturated, 20 new producers: median 6 max 15 ms
delivery=20000 request=20000 linger=default: delivery.timeout.ms should be equal to or larger than linger.ms + request.timeout.ms
delivery=20005 request=20000 linger=default: accepted
delivery=21000 request=20000 linger=default: accepted
-- JVM
very first send in this JVM blocked 17 ms
idle, 20 new producers: median 3 max 4 ms
CPU saturated, 20 new producers: median 6 max 10 ms
delivery=20000 request=20000 linger=default: delivery.timeout.ms should be equal to or larger than linger.ms + request.timeout.ms
delivery=20005 request=20000 linger=default: accepted
delivery=21000 request=20000 linger=default: accepted
-- JVM
very first send in this JVM blocked 16 ms
idle, 20 new producers: median 3 max 4 ms
CPU saturated, 20 new producers: median 6 max 22 ms
delivery=20000 request=20000 linger=default: delivery.timeout.ms should be equal to or larger than linger.ms + request.timeout.ms
delivery=20005 request=20000 linger=default: accepted
delivery=21000 request=20000 linger=default: accepted
```

## Through the real jar

Added 2026-11-04 by issue 206, which owed what the spec left to implementation and what issues 205 and 204 left open. `apps/commerce-api` as a bootJar at `dc6b59e` under `local`, with `bitnamilegacy/kafka:3.5`, `mysql:8.4` and both Redis containers from `docker/infra-compose.yml`, in a compose project of its own so that no local volume was touched. One run per line, on a laptop through Docker, load average about 4. The stack matches the batch run's section G, which gave 480 rows/s with `linger.ms` 5. Rows were committed with `INSERT`, not by confirmations. Each scene uses its own topic. Logs went to a file. What these numbers decided is in [design 13.11](../design/order-outbox.md#1311-구현이-재-본-것).

```text
tput-quiet: 3000 rows, first to last published_at 11.0842 s = 271 rows/s; records 3000; PUBLISHED 3000 3000 0 0
tput-local: 3000 rows, first to last published_at 10.2428 s = 293 rows/s; records 3000; PUBLISHED 3000 3000 0 0
hung: exit 143 after 40.1 s; row at exit: PENDING	1	NULL	2026-11-04 05:57:30.270178	2026-11-04 05:57:45.439353	org.apache.kafka.common.errors.TimeoutException: Expiring 1 record(s) for probe-hung-2:21002 ms has passed since batch creation
hung: after restart (0.04933087504468858 s): PUBLISHED	2	2026-11-04 05:57:52.694046; records for 2 rows: 3
hung-resume: exit 143 after 3.1 s; row at exit: PUBLISHED	1	2026-11-04 05:58:07.476012	2026-11-04 05:59:03.310513	2026-11-04 05:58:07.584259	NULL
hung-resume: after restart (0.05326420802157372 s): PUBLISHED	1	2026-11-04 05:58:07.476012; records for 2 rows: 2
drain-term: 60000 rows; SIGTERM with 1034 published; exit 143 after 30.1 s; PENDING 48948 8 0 0 | PUBLISHED 11052 11052 0 0; pending with an attempt 8; records 11060
drain-term: restart drained in 145.5921835419722 s; PUBLISHED 60000 60008 8 0; records 60008 for 60000 rows
drain-kill: 3000 rows; SIGKILL with 1020 published; exit -9 after 0.0 s; PENDING 1980 20 0 0 | PUBLISHED 1020 1020 0 0; pending with an attempt 20; records 1020
drain-kill: restart drained in 51.96740100008901 s; PUBLISHED 3000 3020 20 0; records 3000 for 3000 rows
```

Reading the lines:

- `tput-*` measures first to last `published_at`, from a JVM that just started. The sustained figure comes from `drain-term`'s restart: its log runs 14:59:07.771 to 15:01:33.527 for 48,948 rows, 336 rows/s. Before the cut, the first process did about 334 rows/s (1,034 to 11,052 in 30 s).
- A status summary reads `status, rows, sum(attempt_count), rows with more than one attempt, rows with last_error`.
- `hung`'s three records for two rows are the warm row, the one that expired while still in the socket, and the republish. Its log has the failure at 14:57:25.253, SIGTERM 20 s before that, and producer close from 14:57:25.307 to 14:57:45.327.
- `drain-term`'s log at the 30 s limit: `Shutdown phase 1073741823 ends with 1 bean still running after timeout of 30000ms: [taskScheduler]`, then producer, JPA and pool closing, then `Unexpected error occurred in scheduled task` on `scheduling-1` from a transaction that could no longer begin.
- `drain-kill`'s kill fell after the claim and before the batch reached the broker, so its records don't grow. Its restart waited for the lease. That timing is luck: nothing here makes it the usual case.

`TopicReader`, from a temporary line in `publishedRecords`, reverted, over `OrderEventRelayerTest`, `OrderEventRelayFailedSendTest` and `OrderEventRelaySchedulerTest`:

```text
reads 22 quiet total s 22.042 further records 0
awaited n 17 median 2 max 20
quiet min/max 1000 1004
```

The awaited figures are milliseconds to read records already on the broker.

## The probes

Each ran under `apps/commerce-api/src/test`, and was removed afterwards, except the real jar's, which drives the app from outside. The timing run also needed `testImplementation` lines for `testcontainers-kafka` and `testcontainers-mysql`, reverted the same way.

### Timing

```kotlin
package com.loopers.probe

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Throwaway: not in the suite. Times the relay's candidate shapes and its failure timings on the real stack. */
class RelayTimingProbeTest {
    private val out = File(System.getProperty("probe.out") ?: "build/relay-probe.txt").also { it.writeText("") }
    private val topic = "probe"
    private val value = """{"eventId":"order-confirmed:v1:12345","eventType":"OrderConfirmed","schemaVersion":1,"payload":{"orderId":12345,"confirmedAt":"2026-10-27T01:02:03.123456Z","items":[{"productId":1001,"quantity":1},{"productId":1002,"quantity":2}]}}"""

    private fun say(line: String) {
        out.appendText(line + "\n")
        println(line)
    }

    /** The shared kafka.yml's producer settings. */
    private fun sharedYml(bootstrap: String): MutableMap<String, Any> {
        return mutableMapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.RETRIES_CONFIG to 3,
            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to 20000,
            ProducerConfig.RETRY_BACKOFF_MS_CONFIG to 500,
        )
    }

    private fun producer(config: Map<String, Any>): KafkaProducer<String, String> {
        return KafkaProducer(config)
    }

    private fun millisSince(startNanos: Long): Double {
        return (System.nanoTime() - startNanos) / 1_000_000.0
    }

    private fun percentiles(samples: List<Double>): String {
        val sorted = samples.sorted()
        fun at(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
        return "p50=%.2f p99=%.2f p99.9=%.2f max=%.2f ms".format(at(0.5), at(0.99), at(0.999), sorted.last())
    }

    private fun resetRows(connection: Connection, rows: Int) {
        connection.createStatement().use {
            it.execute("drop table if exists outbox")
            it.execute(
                """
                create table outbox (
                  id bigint primary key auto_increment, event_id varchar(255) not null unique, payload text not null,
                  status varchar(16) not null, attempt_count int not null, next_attempt_at datetime(6) not null,
                  published_at datetime(6), last_error text, updated_at datetime(6) not null,
                  index ix_due (status, next_attempt_at, id)
                )
                """.trimIndent(),
            )
        }
        connection.prepareStatement(
            "insert into outbox (event_id, payload, status, attempt_count, next_attempt_at, updated_at) values (?, ?, 'PENDING', 0, ?, ?)",
        ).use { insert ->
            val now = Timestamp.from(Instant.now())
            for (i in 1..rows) {
                insert.setString(1, "order-confirmed:v1:$i")
                insert.setString(2, value)
                insert.setTimestamp(3, now)
                insert.setTimestamp(4, now)
                insert.addBatch()
                if (i % 500 == 0) insert.executeBatch()
            }
            insert.executeBatch()
        }
        connection.commit()
    }

    private data class Row(val id: Long, val eventId: String, val payload: String)

    private fun due(connection: Connection, batch: Int): List<Row> {
        val rows = connection.prepareStatement(
            "select id, event_id, payload from outbox where status = 'PENDING' and next_attempt_at <= ? order by next_attempt_at, id limit ?",
        ).use { select ->
            select.setTimestamp(1, Timestamp.from(Instant.now().plusSeconds(3600)))
            select.setInt(2, batch)
            select.executeQuery().use { result ->
                buildList { while (result.next()) add(Row(result.getLong(1), result.getString(2), result.getString(3))) }
            }
        }
        connection.commit()
        return rows
    }

    private fun write(connection: Connection, sql: String, ids: List<Long>) {
        val marks = ids.joinToString(",") { "?" }
        connection.prepareStatement(sql.replace("#", marks)).use { update ->
            ids.forEachIndexed { index, id -> update.setLong(index + 1, id) }
            update.executeUpdate()
        }
        connection.commit()
    }

    private val begin = "update outbox set attempt_count = attempt_count + 1, next_attempt_at = date_add(now(6), interval 5 second), updated_at = now(6) where id in (#)"
    private val publish = "update outbox set status = 'PUBLISHED', published_at = now(6), updated_at = now(6) where id in (#)"

    /** Runs a whole backlog through one shape and reports rows per second. */
    private fun runShape(
        name: String,
        connection: Connection,
        producer: KafkaProducer<String, String>,
        rows: Int,
        batch: Int,
        body: (List<Row>, KafkaProducer<String, String>, Connection) -> Unit,
    ) {
        resetRows(connection, rows)
        val start = System.nanoTime()
        var total = 0
        while (true) {
            val due = due(connection, batch)
            if (due.isEmpty()) break
            body(due, producer, connection)
            total += due.size
        }
        val seconds = millisSince(start) / 1000
        say("%-58s %5d rows in %6.2f s = %7.0f rows/s".format(name, total, seconds, total / seconds))
    }

    @Test
    fun probe() {
        val mysql = MySQLContainer(DockerImageName.parse("mysql:8.4")).apply {
            withDatabaseName("probe")
            withUsername("test")
            withPassword("test")
            start()
        }
        val kafka = KafkaContainer(DockerImageName.parse("apache/kafka-native:4.4.0")).apply { start() }
        val bootstrap = kafka.bootstrapServers
        AdminClient.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap)).use {
            it.createTopics(listOf(NewTopic(topic, 3, 1.toShort()))).all().get(30, TimeUnit.SECONDS)
        }
        val connection = DriverManager.getConnection(mysql.jdbcUrl, "test", "test").apply { autoCommit = false }

        say("== 1. ack latency, send then await one record (1000 sends, first 100 discarded)")
        for (linger in listOf(5, 0)) {
            producer(sharedYml(bootstrap) + (ProducerConfig.LINGER_MS_CONFIG to linger)).use { p ->
                val samples = (1..1100).map { i ->
                    val start = System.nanoTime()
                    p.send(ProducerRecord(topic, i.toString(), value)).get()
                    millisSince(start)
                }.drop(100)
                say("linger.ms=$linger  ${percentiles(samples)}")
            }
        }

        say("== 2. commit latency of one small UPDATE (1000 commits)")
        resetRows(connection, 1000)
        val commitSamples = (1L..1000L).map { id ->
            val start = System.nanoTime()
            write(connection, begin, listOf(id))
            millisSince(start)
        }
        say(percentiles(commitSamples))

        say("== 3. relay shapes over a 3000 row backlog")
        val rows = 3000
        fun record(row: Row) = ProducerRecord(topic, row.id.toString(), row.payload)
        producer(sharedYml(bootstrap) + (ProducerConfig.LINGER_MS_CONFIG to 0)).use { p0 ->
            producer(sharedYml(bootstrap) + (ProducerConfig.LINGER_MS_CONFIG to 5)).use { p5 ->
                val oneWrite = { due: List<Row>, p: KafkaProducer<String, String>, c: Connection ->
                    due.forEach { row ->
                        p.send(record(row)).get()
                        write(c, publish, listOf(row.id))
                    }
                }
                val twoWrites = { due: List<Row>, p: KafkaProducer<String, String>, c: Connection ->
                    due.forEach { row ->
                        write(c, begin, listOf(row.id))
                        p.send(record(row)).get()
                        write(c, publish, listOf(row.id))
                    }
                }
                val batched = { due: List<Row>, p: KafkaProducer<String, String>, c: Connection ->
                    write(c, begin, due.map { it.id })
                    val futures: List<Future<*>> = due.map { p.send(record(it)) }
                    p.flush()
                    futures.forEach { it.get() }
                    write(c, publish, due.map { it.id })
                }
                val batchedRowWrites = { due: List<Row>, p: KafkaProducer<String, String>, c: Connection ->
                    write(c, begin, due.map { it.id })
                    val futures = due.map { it to p.send(record(it)) }
                    p.flush()
                    futures.forEach { (row, future) ->
                        future.get()
                        write(c, publish, listOf(row.id))
                    }
                }
                runShape("A one at a time, 1 write per row, linger 0", connection, p0, rows, 20, oneWrite)
                runShape("B one at a time, 2 writes per row, linger 0", connection, p0, rows, 20, twoWrites)
                runShape("B' one at a time, 2 writes per row, linger 5 (client default)", connection, p5, rows, 20, twoWrites)
                runShape("C batch 20: bulk begin, await all, bulk publish", connection, p0, rows, 20, batched)
                runShape("D batch 20: bulk begin, await all, publish per row", connection, p0, rows, 20, batchedRowWrites)
                runShape("C100 batch 100: bulk begin, await all, bulk publish", connection, p0, rows, 100, batched)
                runShape("D100 batch 100: bulk begin, await all, publish per row", connection, p0, rows, 100, batchedRowWrites)
            }
        }

        val docker = kafka.dockerClient
        val id = kafka.containerId
        fun pause() = docker.pauseContainerCmd(id).exec()
        fun unpause() = docker.unpauseContainerCmd(id).exec()

        fun timedFailure(label: String, config: Map<String, Any>) {
            producer(config).use { p ->
                p.send(ProducerRecord(topic, "warm", value)).get()
                pause()
                val start = System.nanoTime()
                val outcome = runCatching {
                    val future = p.send(ProducerRecord(topic, "down", value))
                    val returned = millisSince(start)
                    runCatching { future.get() }.fold(
                        { "acked" },
                        { "send() returned in %.0f ms; ${it.cause?.javaClass?.simpleName}: ${it.cause?.message?.take(110)}".format(returned) },
                    )
                }.getOrElse { "send threw ${it.javaClass.simpleName}" }
                say("$label: %.1f s  %s".format(millisSince(start) / 1000, outcome))
                unpause()
                val recovery = System.nanoTime()
                val again = runCatching { p.send(ProducerRecord(topic, "after", value)).get(30, TimeUnit.SECONDS) }
                say("    next send after unpause: %.2f s, ok=%s".format(millisSince(recovery) / 1000, again.isSuccess))
            }
        }

        fun override(request: Int, delivery: Int, block: Long): Map<String, Any> {
            return sharedYml(bootstrap) + mapOf(
                ProducerConfig.LINGER_MS_CONFIG to 0,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to request,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to delivery,
                ProducerConfig.MAX_BLOCK_MS_CONFIG to block,
            )
        }

        say("== 4. hung broker (container paused), metadata already known: time until the future fails")
        timedFailure("request=5s delivery=10s", override(5000, 10000, 5000))
        timedFailure("request=5s delivery=30s", override(5000, 30000, 5000))
        timedFailure("request=20s delivery=30s", override(20000, 30000, 5000))
        timedFailure("kafka.yml as is (request=20s retries=3, delivery default 120s)", sharedYml(bootstrap))

        say("== 5. a pause shorter than delivery: rides through, or local give-up followed by late success")
        producer(override(5000, 30000, 5000)).use { p ->
            p.send(ProducerRecord(topic, "warm", value)).get()
            pause()
            val start = System.nanoTime()
            val future = p.send(ProducerRecord(topic, "blip", value))
            val localWait = runCatching { future.get(2, TimeUnit.SECONDS) }
            say("local await 2 s: ${localWait.exceptionOrNull()?.javaClass?.simpleName}, future.isDone=${future.isDone}")
            Thread.sleep(6000)
            unpause()
            val metadata = future.get(60, TimeUnit.SECONDS)
            say("8 s pause, delivery=30s: acked %.1f s after send, offset=${metadata.offset()} (the local wait had already given up)".format(millisSince(start) / 1000))
        }

        say("== 6. no metadata yet (new producer) and hung broker: send() itself blocks")
        pause()
        for ((label, config) in listOf(
            "max.block=5s" to override(5000, 10000, 5000),
            "max.block default 60s" to sharedYml(bootstrap),
        )) {
            producer(config).use { p ->
                val start = System.nanoTime()
                val outcome = runCatching { p.send(ProducerRecord(topic, "fresh", value)).get() }
                say("$label: %.1f s  ${(outcome.exceptionOrNull()?.cause ?: outcome.exceptionOrNull())?.javaClass?.simpleName}".format(millisSince(start) / 1000))
            }
        }
        unpause()

        say("== 7. delivery < request + linger, both explicit")
        val invalid = runCatching { producer(override(5000, 4000, 5000)).close() }
        say("request=5s delivery=4s: ${invalid.exceptionOrNull()?.javaClass?.simpleName ?: "accepted"} ${invalid.exceptionOrNull()?.cause?.message ?: invalid.exceptionOrNull()?.message ?: ""}")

        say("== 8. broker gone (container stopped), metadata known: connection refused rather than hung")
        producer(override(5000, 10000, 5000)).use { p ->
            p.send(ProducerRecord(topic, "warm", value)).get()
            kafka.stop()
            val start = System.nanoTime()
            val outcome = runCatching { p.send(ProducerRecord(topic, "gone", value)).get() }
            say("request=5s delivery=10s: %.1f s  ${(outcome.exceptionOrNull()?.cause)?.javaClass?.simpleName}: ${outcome.exceptionOrNull()?.cause?.message?.take(110)}".format(millisSince(start) / 1000))
        }
        connection.close()
        mysql.stop()
    }
}
```

### Batch

This is the first version. The corrected run replaced sections A to E with B2, which starts the broker in another thread racing the send, repeated six times with the default retries. It also seeded `next_attempt_at` and `updated_at` with `date_sub(now(6), interval 1 hour)` and `now(6)` in the `INSERT`, not with parameters from the JVM, so the rows were due by MySQL's own clock.

```kotlin
package com.loopers.probe

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.RetriableException
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Throwaway: not in the suite. Producer retry budget, restarts, batch failure shapes and SKIP LOCKED on the real images. */
class RelayBatchProbeTest {
    private val out = File("build/relay-batch-probe.txt").also { it.writeText("") }
    private val bootstrap = "localhost:29092"
    private val broker = "probe-relay-kafka"
    private val db = "probe-relay-mysql"
    private val value = """{"eventId":"order-confirmed:v1:12345","eventType":"OrderConfirmed","schemaVersion":1,"payload":{"orderId":12345,"confirmedAt":"2026-10-27T01:02:03.123456Z","items":[{"productId":1001,"quantity":1},{"productId":1002,"quantity":2}]}}"""

    private fun say(line: String) {
        out.appendText(line + "\n")
        println(line)
    }

    private fun docker(vararg args: String): String {
        val process = ProcessBuilder(listOf("docker") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(120, TimeUnit.SECONDS)
        return output.trim()
    }

    private fun seconds(start: Long): Double {
        return (System.nanoTime() - start) / 1_000_000_000.0
    }

    private fun relayConfig(retries: Int?, deliveryMs: Int = 20000, requestMs: Int = 10000, blockMs: Long = 20000): Map<String, Any> {
        val config = mutableMapOf<String, Any>(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.LINGER_MS_CONFIG to 0,
            ProducerConfig.RETRY_BACKOFF_MS_CONFIG to 500,
            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to requestMs,
            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to deliveryMs,
            ProducerConfig.MAX_BLOCK_MS_CONFIG to blockMs,
        )
        if (retries != null) config[ProducerConfig.RETRIES_CONFIG] = retries
        return config
    }

    private fun label(retries: Int?) = if (retries == null) "retries=default(MAX)" else "retries=$retries"

    private fun waitForBroker() {
        val start = System.nanoTime()
        while (seconds(start) < 90) {
            val ok = runCatching {
                AdminClient.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap, AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG to 2000, AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG to 2000)).use {
                    it.listTopics().names().get(3, TimeUnit.SECONDS)
                }
            }.isSuccess
            if (ok) return
            Thread.sleep(200)
        }
        error("broker not ready")
    }

    private fun outcome(failure: Throwable?): String {
        val cause = failure?.cause ?: failure ?: return "ACKED"
        return "FAILED ${cause.javaClass.simpleName} retriable=${cause is RetriableException}: ${cause.message?.take(80)}"
    }

    private fun connectDb(): Connection {
        val start = System.nanoTime()
        while (seconds(start) < 120) {
            val connection = runCatching { DriverManager.getConnection("jdbc:mysql://127.0.0.1:23306/probe", "test", "test") }.getOrNull()
            if (connection != null) return connection.apply { autoCommit = false }
            Thread.sleep(500)
        }
        error("mysql not ready")
    }

    private fun resetRows(connection: Connection, rows: Int, index: Boolean = true) {
        connection.createStatement().use {
            it.execute("drop table if exists outbox")
            val ix = if (index) ", index ix_due (status, next_attempt_at, id)" else ""
            it.execute(
                """
                create table outbox (
                  id bigint primary key auto_increment, event_id varchar(255) not null unique, payload text not null,
                  status varchar(16) not null, attempt_count int not null, next_attempt_at datetime(6) not null,
                  published_at datetime(6), last_error text, updated_at datetime(6) not null, deleted_at datetime(6)$ix
                )
                """.trimIndent(),
            )
        }
        connection.prepareStatement(
            "insert into outbox (event_id, payload, status, attempt_count, next_attempt_at, updated_at) values (?, ?, 'PENDING', 0, ?, ?)",
        ).use { insert ->
            val base = Instant.now().minusSeconds(3600)
            for (i in 1..rows) {
                insert.setString(1, "order-confirmed:v1:$i")
                insert.setString(2, value)
                insert.setTimestamp(3, Timestamp.from(base.plusMillis(i.toLong())))
                insert.setTimestamp(4, Timestamp.from(base))
                insert.addBatch()
                if (i % 500 == 0) insert.executeBatch()
            }
            insert.executeBatch()
        }
        connection.commit()
    }

    private val dueSql = "select id, payload from outbox where status = 'PENDING' and next_attempt_at <= now(6) and deleted_at is null order by next_attempt_at, id limit ? for update skip locked"

    private fun claim(connection: Connection, batch: Int): List<Pair<Long, String>> {
        val rows = connection.prepareStatement(dueSql).use { select ->
            select.setInt(1, batch)
            select.executeQuery().use { result ->
                buildList { while (result.next()) add(result.getLong(1) to result.getString(2)) }
            }
        }
        if (rows.isNotEmpty()) {
            val marks = rows.joinToString(",") { "?" }
            connection.prepareStatement("update outbox set attempt_count = attempt_count + 1, next_attempt_at = date_add(now(6), interval 150 second), updated_at = now(6) where id in ($marks)").use { update ->
                rows.forEachIndexed { index, row -> update.setLong(index + 1, row.first) }
                update.executeUpdate()
            }
        }
        connection.commit()
        return rows
    }

    private fun recordPublished(connection: Connection, id: Long) {
        connection.prepareStatement("select id from outbox where id = ? for update").use { lock ->
            lock.setLong(1, id)
            lock.executeQuery().close()
        }
        connection.prepareStatement("update outbox set status = 'PUBLISHED', published_at = now(6), updated_at = now(6) where id = ?").use { update ->
            update.setLong(1, id)
            update.executeUpdate()
        }
        connection.commit()
    }

    private fun drain(connection: Connection, producer: KafkaProducer<String, String>, topic: String, batch: Int): Int {
        var total = 0
        while (true) {
            val claimed = claim(connection, batch)
            if (claimed.isEmpty()) return total
            val futures = claimed.map { (id, payload) -> id to producer.send(ProducerRecord(topic, id.toString(), payload)) }
            futures.forEach { (id, future) ->
                future.get(21, TimeUnit.SECONDS)
                recordPublished(connection, id)
            }
            total += claimed.size
        }
    }

    @Test
    fun probe() {
        docker("rm", "-f", broker, db)
        docker(
            "run", "-d", "--name", broker, "-p", "127.0.0.1:29092:29092",
            "-e", "KAFKA_CFG_NODE_ID=1", "-e", "KAFKA_CFG_PROCESS_ROLES=broker,controller",
            "-e", "KAFKA_CFG_LISTENERS=PLAINTEXT://:29092,CONTROLLER://:9093",
            "-e", "KAFKA_CFG_ADVERTISED_LISTENERS=PLAINTEXT://localhost:29092",
            "-e", "KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT",
            "-e", "KAFKA_CFG_CONTROLLER_LISTENER_NAMES=CONTROLLER", "-e", "KAFKA_CFG_CONTROLLER_QUORUM_VOTERS=1@localhost:9093",
            "-e", "KAFKA_CFG_OFFSETS_TOPIC_REPLICATION_FACTOR=1", "-e", "KAFKA_CFG_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1",
            "-e", "KAFKA_CFG_TRANSACTION_STATE_LOG_MIN_ISR=1", "bitnamilegacy/kafka:3.5",
        )
        docker(
            "run", "-d", "--name", db, "-p", "127.0.0.1:23306:3306", "-e", "MYSQL_ROOT_PASSWORD=root", "-e", "MYSQL_DATABASE=probe",
            "-e", "MYSQL_USER=test", "-e", "MYSQL_PASSWORD=test", "mysql:8.4",
        )
        try {
            waitForBroker()
            AdminClient.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap)).use { admin ->
                admin.createTopics(
                    listOf(
                        NewTopic("probe", 3, 1.toShort()),
                        NewTopic("probe-nir", 1, 1.toShort()).configs(mapOf("min.insync.replicas" to "2")),
                    ),
                ).all().get(30, TimeUnit.SECONDS)
            }

            say("== A. a fast retriable error (NotEnoughReplicas, min.insync=2 on one broker), T=20 s")
            for (retries in listOf(3, null)) {
                KafkaProducer<String, String>(relayConfig(retries)).use { p ->
                    val start = System.nanoTime()
                    val failure = runCatching { p.send(ProducerRecord("probe-nir", "k", value)).get() }.exceptionOrNull()
                    say("${label(retries)}: %.1f s  ${outcome(failure)}".format(seconds(start)))
                }
            }

            say("== B. real restarts with a warm producer, T=20 s, send right after the broker goes down")
            for (retries in listOf(3, null)) {
                for ((kind, stop) in listOf<Pair<String, () -> Unit>>(
                    "graceful" to { docker("stop", "-t", "30", broker) },
                    "kill -9" to { docker("kill", broker) },
                )) {
                    repeat(2) { round ->
                        KafkaProducer<String, String>(relayConfig(retries)).use { p ->
                            p.send(ProducerRecord("probe", "warm", value)).get()
                            stop()
                            val start = System.nanoTime()
                            val future = p.send(ProducerRecord("probe", "restart", value))
                            docker("start", broker)
                            val failure = runCatching { future.get(40, TimeUnit.SECONDS) }.exceptionOrNull()
                            say("${label(retries)} $kind #${round + 1}: %.1f s  ${outcome(failure)}".format(seconds(start)))
                        }
                        waitForBroker()
                    }
                }
            }

            say("== C. a batch of 20 per-row results: one oversized record among healthy ones")
            KafkaProducer<String, String>(relayConfig(null)).use { p ->
                val big = "x".repeat(2 * 1024 * 1024)
                val start = System.nanoTime()
                val futures = (1..20).map { i -> i to runCatching { p.send(ProducerRecord("probe", i.toString(), if (i == 7) big else value)) } }
                val results = futures.map { (i, sent) ->
                    i to (sent.exceptionOrNull() ?: runCatching { sent.getOrThrow().get(21, TimeUnit.SECONDS) }.exceptionOrNull())
                }
                val acked = results.count { it.second == null }
                say("acked=$acked of 20 in %.3f s; failed: ${results.filter { it.second != null }.map { "#${it.first} ${outcome(it.second)}" }}".format(seconds(start)))
            }

            say("== D. a batch of 20 while the broker hangs (paused), metadata known: total time")
            KafkaProducer<String, String>(relayConfig(null)).use { p ->
                p.send(ProducerRecord("probe", "warm", value)).get()
                docker("pause", broker)
                val start = System.nanoTime()
                val futures = (1..20).map { i -> p.send(ProducerRecord("probe", i.toString(), value)) }
                val sendPhase = seconds(start)
                val failed = futures.count { runCatching { it.get(40, TimeUnit.SECONDS) }.isFailure }
                say("send phase %.3f s, then all awaited at %.1f s, failed=$failed of 20".format(sendPhase, seconds(start)))
                docker("unpause", broker)
            }

            say("== E. new producer, no metadata, broker stopped: does each send() in a batch block for max.block?")
            docker("stop", "-t", "30", broker)
            for (block in listOf(2000L)) {
                KafkaProducer<String, String>(relayConfig(null, blockMs = block)).use { p ->
                    val samples = (1..5).map {
                        val start = System.nanoTime()
                        val threw = runCatching { p.send(ProducerRecord("probe", "k", value)) }
                        "%.1fs/%s".format(seconds(start), if (threw.isFailure) "threw" else "failed-future")
                    }
                    say("max.block=${block}ms, 5 sends: $samples")
                }
            }
            docker("start", broker)
            waitForBroker()

            say("== F. SKIP LOCKED and the index")
            val first = connectDb()
            val second = connectDb()
            resetRows(first, 2000)
            first.createStatement().use { s ->
                s.executeQuery("explain select id, payload from outbox where status = 'PENDING' and next_attempt_at <= now(6) and deleted_at is null order by next_attempt_at, id limit 20 for update skip locked").use { r ->
                    r.next()
                    say("with index: key=${r.getString("key")} type=${r.getString("type")} rows=${r.getString("rows")} extra=${r.getString("Extra")}")
                }
            }
            first.commit()
            val idsOf = { c: Connection -> c.prepareStatement(dueSql).use { st -> st.setInt(1, 20); st.executeQuery().use { r -> buildList { while (r.next()) add(r.getLong(1)) } } } }
            val heldByFirst = idsOf(first)
            val seenBySecond = idsOf(second)
            say("two open claims: first=${heldByFirst.first()}..${heldByFirst.last()} second=${seenBySecond.first()}..${seenBySecond.last()} overlap=${heldByFirst.intersect(seenBySecond.toSet()).size}")
            second.prepareStatement("select count(*) from performance_schema.data_locks").use { runCatching { it.executeQuery().close() } }
            first.commit()
            second.commit()
            resetRows(first, 2000, index = false)
            first.createStatement().use { s ->
                s.executeQuery("explain select id, payload from outbox where status = 'PENDING' and next_attempt_at <= now(6) and deleted_at is null order by next_attempt_at, id limit 20 for update skip locked").use { r ->
                    r.next()
                    say("no index: key=${r.getString("key")} type=${r.getString("type")} rows=${r.getString("rows")} extra=${r.getString("Extra")}")
                }
            }
            first.commit()
            val heldNoIndex = idsOf(first)
            val seenNoIndex = idsOf(second)
            say("no index, two open claims: first=${heldNoIndex.size} rows, second=${seenNoIndex.size} rows (0 means the first claim locked everything it scanned)")
            first.commit()
            second.commit()

            say("== G. full shape: SKIP LOCKED claim of 20, send all, record each result in its own locked TX, 3000 rows")
            val rows = 3000
            for (linger in listOf(0, 5)) {
                KafkaProducer<String, String>(relayConfig(null) + (ProducerConfig.LINGER_MS_CONFIG to linger)).use { p ->
                    resetRows(first, rows)
                    val start = System.nanoTime()
                    val total = drain(first, p, "probe", 20)
                    say("one relay, linger=$linger: $total rows in %.2f s = %.0f rows/s".format(seconds(start), total / seconds(start)))
                }
            }
            KafkaProducer<String, String>(relayConfig(null)).use { p ->
                resetRows(first, rows)
                val start = System.nanoTime()
                val counts = IntArray(2)
                listOf(0, 1).map { n ->
                    thread {
                        val connection = connectDb()
                        counts[n] = drain(connection, p, "probe", 20)
                        connection.close()
                    }
                }.forEach { it.join() }
                val elapsed = seconds(start)
                first.commit()
                first.createStatement().use { s ->
                    s.executeQuery("select count(*), sum(attempt_count = 1), sum(status = 'PUBLISHED') from outbox").use { r ->
                        r.next()
                        say("two relays: ${counts[0]} + ${counts[1]} rows in %.2f s = %.0f rows/s; rows=${r.getInt(1)} attempted once=${r.getInt(2)} published=${r.getInt(3)}".format(elapsed, rows / elapsed))
                    }
                }
                first.commit()
            }
            first.close()
            second.close()
        } finally {
            docker("rm", "-f", broker, db)
        }
    }
}
```

### First send

```kotlin
package com.loopers.probe

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class FirstSendProbeTest {
    private val out = File("build/first-send-probe.txt")

    private fun config(delivery: Int?, block: Long): Map<String, Any> {
        val c = mutableMapOf<String, Any>(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:29092",
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.ACKS_CONFIG to "all", ProducerConfig.RETRIES_CONFIG to 3,
            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to 20000, ProducerConfig.RETRY_BACKOFF_MS_CONFIG to 500,
            ProducerConfig.MAX_BLOCK_MS_CONFIG to block,
        )
        if (delivery != null) c[ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG] = delivery
        return c
    }

    private fun firstSendMillis(): Double {
        KafkaProducer<String, String>(config(null, 60000)).use { p ->
            val start = System.nanoTime()
            val future = p.send(ProducerRecord("first", "k", "v"))
            val blocked = (System.nanoTime() - start) / 1_000_000.0
            future.get(30, TimeUnit.SECONDS)
            return blocked
        }
    }

    @Test
    fun probe() {
        AdminClient.create(mapOf<String, Any>("bootstrap.servers" to "localhost:29092")).use {
            runCatching { it.createTopics(listOf(NewTopic("first", 3, 1.toShort()))).all().get(30, TimeUnit.SECONDS) }
        }
        out.appendText("-- JVM\n")
        out.appendText("very first send in this JVM blocked %.0f ms\n".format(firstSendMillis()))
        val idle = (1..20).map { firstSendMillis() }.sorted()
        out.appendText("idle, 20 new producers: median %.0f max %.0f ms\n".format(idle[10], idle.last()))
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val hogs = (1..Runtime.getRuntime().availableProcessors() * 2).map { thread { while (!stop.get()) Math.sqrt(Math.random()) } }
        val busy = (1..20).map { firstSendMillis() }.sorted()
        stop.set(true); hogs.forEach { it.join() }
        out.appendText("CPU saturated, 20 new producers: median %.0f max %.0f ms\n".format(busy[10], busy.last()))
        for (delivery in listOf(20000, 20005, 21000)) {
            val r = runCatching { KafkaProducer<String, String>(config(delivery, 1000)).close() }
            out.appendText("delivery=$delivery request=20000 linger=default: ${r.exceptionOrNull()?.let { (it.cause ?: it).message } ?: "accepted"}\n")
        }
    }
}
```

### Real jar

Driven from outside the app. Run as `probe.py JAR SCENE …`, with `schema` first to create the tables. Its first runs had a wrong jar path, which failed at once and measured nothing. The source is as it ran, so its docstring's usage line omits `JAR`.

```python
#!/usr/bin/env python3
"""Throwaway for issue 206: throughput through the real jar, and what stopping the app does to a round.

Runs commerce-api's bootJar under `local` against the `relay206` compose project.
Usage: probe.py SCENE [SCENE ...]
"""
import json
import os
import signal
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
JAR = sys.argv[1]
MYSQL = "relay206-mysql-1"
OUT = HERE / "results.txt"
BODY = '{"orderId":%d,"confirmedAt":"2026-10-27T01:02:03.123456Z","items":[{"productId":1001,"quantity":1},{"productId":1002,"quantity":2}]}'


def say(line):
    print(line, flush=True)
    with OUT.open("a") as out:
        out.write(line + "\n")


def sql(statement):
    done = subprocess.run(
        ["docker", "exec", "-i", MYSQL, "mysql", "-uapplication", "-papplication", "-N", "-B", "loopers"],
        input=statement, capture_output=True, text=True,
    )
    if done.returncode:
        raise RuntimeError(done.stderr)
    return done.stdout.strip()


def seed(start, count):
    rows = []
    for i in range(start, start + count):
        rows.append(
            f"('order-confirmed:v1:{i}',{i},'OrderConfirmed',1,'{i}','{BODY % i}','PENDING',0,"
            "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))"
        )
    for offset in range(0, len(rows), 2000):
        sql(
            "INSERT INTO order_event_outbox (event_id,order_id,event_type,schema_version,message_key,payload,status,"
            "attempt_count,next_attempt_at,created_at,updated_at) VALUES " + ",".join(rows[offset:offset + 2000])
        )


def counts():
    return sql(
        "SELECT status, COUNT(*), SUM(attempt_count), SUM(attempt_count>1), SUM(last_error IS NOT NULL) "
        "FROM order_event_outbox GROUP BY status"
    ).replace("\n", " | ").replace("\t", " ")


def start(log, topic, ddl="none", extra=()):
    command = [
        "java", "-jar", JAR, f"--spring.jpa.hibernate.ddl-auto={ddl}", f"--order-events.topic={topic}",
        "--spring.kafka.admin.properties.bootstrap.servers=localhost:19092", "--server.port=18080",
        "--management.server.port=18081", *extra,
    ]
    out = (HERE / log).open("w")
    process = subprocess.Popen(command, stdout=out, stderr=subprocess.STDOUT)
    for _ in range(240):
        try:
            with urllib.request.urlopen("http://localhost:18081/actuator/health", timeout=1) as answer:
                if answer.status == 200:
                    return process
        except Exception:
            pass
        if process.poll() is not None:
            raise RuntimeError(f"app exited {process.returncode}, see {log}")
        time.sleep(0.25)
    raise RuntimeError("app not ready")


def stop_and_time(process, sig=signal.SIGTERM):
    began = time.monotonic()
    process.send_signal(sig)
    code = process.wait(timeout=300)
    return time.monotonic() - began, code


def records(topic):
    """The topic's total record count, from its end offsets."""
    done = subprocess.run(
        ["docker", "exec", "kafka", "kafka-run-class.sh", "kafka.tools.GetOffsetShell", "--bootstrap-server",
         "localhost:9092", "--topic", topic], capture_output=True, text=True,
    )
    return sum(int(line.rsplit(":", 1)[1]) for line in done.stdout.split() if ":" in line)


def wait_for(condition, limit):
    began = time.monotonic()
    while time.monotonic() - began < limit:
        if condition():
            return time.monotonic() - began
        time.sleep(0.05)
    return None


def reset():
    subprocess.run(["docker", "unpause", "kafka"], capture_output=True)
    sql("TRUNCATE TABLE order_event_outbox")


def throughput(name, rows, extra):
    reset()
    topic = f"probe-{name}"
    seed(1, rows)
    process = start(f"{name}.log", topic, extra=extra)
    took = wait_for(lambda: sql("SELECT COUNT(*) FROM order_event_outbox WHERE status='PENDING'") == "0", 300)
    span = sql(
        "SELECT TIMESTAMPDIFF(MICROSECOND, MIN(published_at), MAX(published_at))/1000000 FROM order_event_outbox"
    )
    stop_and_time(process)
    say(f"{name}: {rows} rows, first to last published_at {span} s = {rows / float(span):.0f} rows/s; "
        f"records {records(topic)}; {counts()}")


def hung_stop(name, resume_after):
    """Broker paused with a row in flight, SIGTERM 1 s after the claim. As issue 205's table, after issue 201."""
    reset()
    topic = f"probe-{name}"
    process = start(f"{name}.log", topic)
    seed(1, 1)
    wait_for(lambda: sql("SELECT COUNT(*) FROM order_event_outbox WHERE status='PUBLISHED'") == "1", 30)
    subprocess.run(["docker", "pause", "kafka"], check=True)
    seed(2, 1)
    wait_for(lambda: sql("SELECT attempt_count FROM order_event_outbox WHERE order_id=2") == "1", 30)
    time.sleep(1)
    began = time.monotonic()
    process.send_signal(signal.SIGTERM)
    if resume_after is not None:
        time.sleep(resume_after)
        subprocess.run(["docker", "unpause", "kafka"], check=True)
    code = process.wait(timeout=300)
    took = time.monotonic() - began
    row = sql("SELECT status,attempt_count,published_at,next_attempt_at,UTC_TIMESTAMP(6),last_error FROM order_event_outbox WHERE order_id=2")
    say(f"{name}: exit {code} after {took:.1f} s; row at exit: {row}")
    subprocess.run(["docker", "unpause", "kafka"], capture_output=True)
    process = start(f"{name}-restart.log", topic)
    waited = wait_for(lambda: sql("SELECT status FROM order_event_outbox WHERE order_id=2") == "PUBLISHED", 120)
    row = sql("SELECT status,attempt_count,published_at FROM order_event_outbox WHERE order_id=2")
    stop_and_time(process)
    say(f"{name}: after restart ({waited} s): {row}; records for 2 rows: {records(topic)}")


def drain_stop(name, rows, sig, extra=()):
    """A stop in the middle of a backlog, then a restart that finishes it."""
    reset()
    topic = f"probe-{name}"
    seed(1, rows)
    process = start(f"{name}.log", topic, extra=extra)
    wait_for(lambda: int(sql("SELECT COUNT(*) FROM order_event_outbox WHERE status='PUBLISHED'")) >= 1000, 60)
    before = sql("SELECT COUNT(*) FROM order_event_outbox WHERE status='PUBLISHED'")
    took, code = stop_and_time(process, sig)
    open_rows = sql("SELECT COUNT(*) FROM order_event_outbox WHERE status='PENDING' AND attempt_count>0")
    say(f"{name}: {rows} rows; {sig.name} with {before} published; exit {code} after {took:.1f} s; {counts()}; "
        f"pending with an attempt {open_rows}; records {records(topic)}")
    process = start(f"{name}-restart.log", topic, extra=extra)
    waited = wait_for(lambda: sql("SELECT COUNT(*) FROM order_event_outbox WHERE status='PENDING'") == "0", 400)
    stop_and_time(process)
    say(f"{name}: restart drained in {waited} s; {counts()}; records {records(topic)} for {rows} rows")


SCENES = {
    "schema": lambda: stop_and_time(start("schema.log", "probe-schema", ddl="create", extra=["--order-events.relay.enabled=false"])),
    "tput-quiet": lambda: throughput("tput-quiet", 3000, ["--spring.jpa.show-sql=false"]),
    "tput-local": lambda: throughput("tput-local", 3000, []),
    "hung": lambda: hung_stop("hung", None),
    "hung-resume": lambda: hung_stop("hung-resume", 3),
    "drain-term": lambda: drain_stop("drain-term", 60000, signal.SIGTERM, ["--spring.jpa.show-sql=false"]),
    "drain-kill": lambda: drain_stop("drain-kill", 3000, signal.SIGKILL, ["--spring.jpa.show-sql=false"]),
}

for scene in sys.argv[2:]:
    say(f"== {scene} {time.strftime('%H:%M:%S')}")
    SCENES[scene]()
```
