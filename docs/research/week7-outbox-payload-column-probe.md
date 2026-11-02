# Week 7 outbox: the payload column, TEXT or JSON

The measurement behind [ADR 0034](../adr/0034-order-confirmed-is-left-in-an-outbox-row-by-tx-b.md) rule 6, taken on 2026-11-02 during step 1's interview. It compares a `TEXT` column with MySQL's `JSON` type for the outbox's serialized body, given that the relay rebuilds a typed message from the row instead of sending the stored string.

## How it ran

A throwaway test on `845966c` (the `week7` tip) extended `BaseCommittingApplicationServiceTest`, so it ran on the real context: Hibernate, MySQL 8.4 and the Kafka test broker from Testcontainers. It used the real `OrderConfirmedMessage`, the `JsonMapper` bean and the shared `KafkaTemplate`. The test is not in the suite; its source is at the end of this file.

Limits: one run per configuration on a laptop with one writer, so timing differences of a few hundredths of a millisecond mean nothing. The byte comparison with the week 6 path used one 2-item payload.

## What it found

| Question | `TEXT` | `JSON` | Separates them? |
| --- | --- | --- | --- |
| Rebuilt record bytes match the week 6 path | yes | yes | no |
| Values survive (micros, `Long.MAX`, item order) | yes | yes | no |
| `json_extract`, `->>`, `json_length` work | yes | yes | no |
| Insert p50, 2 / 100 items | 1.24 / 1.33 ms | 1.30 / 1.36 ms | no |
| Read and parse 100 rows, p50, 2 / 100 items | 1.08 / 5.84 ms | 1.12 / 6.29 ms | no |
| Bytes per value, 2 / 100 items | 135 / 3363 | 157 / 3881 | `JSON` about 15% larger |
| Table size, 2,500 rows at 100 items | 11.0 MB | 14.2 MB | `JSON` about 29% larger |
| Rejects invalid JSON | only with a `CHECK` | built in | no, once the `CHECK` is added |
| 139 KB payload | rejected | stored | not in practice: a 100-item order at maximum values is 5.7 KB |
| Full scan with `json_extract`, 2,500 rows at 100 items | 60 ms | 2 ms | `JSON` much faster, but no reader needs it |
| Keeps exactly what TX B serialized | yes | no | yes |
| Converts to the other type with `ALTER` | yes | yes, without the original string | yes |

The earlier run, with the `CHECK` on the `TEXT` table, showed no measurable insert cost: its p50s are within the noise of the plain run below.

## Outside sources

- The [MySQL 8.4 manual](https://dev.mysql.com/doc/refman/8.4/en/json.html) says the ordering of JSON object keys is "subject to change and not guaranteed to be consistent across releases".
- MySQL-targeted outbox tables use text: [Eventuate](https://github.com/eventuate-foundation/eventuate-common/blob/HEAD/mysql/3.create-message-table.sql) has `payload LONGTEXT`, and [Spring Modulith](https://docs.spring.io/spring-modulith/reference/appendix.html) has `SERIALIZED_EVENT VARCHAR(4000)`.
- [Debezium's](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html) outbox example uses PostgreSQL `jsonb`, and it passes the value through unmodified.

These are three examples, not a survey.

## Raw results

The final run, with a plain `TEXT` table in section 9:

```text
== 1. Hibernate String field: read back what was written
  written  {"orderId":12345,"confirmedAt":"2026-10-27T01:02:03.123456Z","items":[{"productId":1001,"quantity":1},{"productId":1002,"quantity":2}]}
  TEXT     {"orderId":12345,"confirmedAt":"2026-10-27T01:02:03.123456Z","items":[{"productId":1001,"quantity":1},{"productId":1002,"quantity":2}]}
  JSON     {"items": [{"quantity": 1, "productId": 1001}, {"quantity": 2, "productId": 1002}], "orderId": 12345, "confirmedAt": "2026-10-27T01:02:03.123456Z"}
  TEXT identical=true  JSON identical=false
== 2. Rehydrate -> real KafkaTemplate -> real record bytes
  W6 path  {"eventId":"order-confirmed:v1:12345","eventType":"OrderConfirmed","schemaVersion":1,"payload":{"orderId":12345,"confirmedAt":"2026-10-27T01:02:03.123456Z","items":[{"productId":1001,"quantity":1},{"productId":1002,"quantity":2}]}}
  via TEXT identical to W6 path=true  via JSON identical to W6 path=true
== 3. Value edge cases survive storage + parse
  TEXT=true JSON=true  {"items": [{"quantity": 1, "productId": 1001}, {"quantity": 2, "productId": 1002}], "order
  TEXT=true JSON=true  {"items": [{"quantity": 1, "productId": 1001}, {"quantity": 2, "productId": 1002}], "order
  TEXT=true JSON=true  {"items": [{"quantity": 1, "productId": 9223372036854775798}, {"quantity": 2, "productId":
  TEXT=true JSON=true  {"items": [{"quantity": 2147483647, "productId": 9}, {"quantity": 1, "productId": 1}], "or
== 4. SQL inspection works on which column?
  probe_text json_extract -> OK: [1001]
  probe_text ->> -> OK: [2026-10-27T01:02:03.123456Z]
  probe_text json_length -> OK: [2]
  probe_json json_extract -> OK: [1001]
  probe_json ->> -> OK: [2026-10-27T01:02:03.123456Z]
  probe_json json_length -> OK: [2]
== 5. Bad input
  TEXT accepts garbage -> OK: kotlin.Unit
  JSON accepts garbage -> ERROR DataException: Data truncation: Invalid JSON text: "Invalid value." at position 1 in value for column 'probe_json.payload'.
  TEXT + CHECK(json_valid) DDL -> OK: kotlin.Unit
  TEXT + CHECK accepts garbage -> ERROR ConstraintViolationException: Check constraint 'ck_probe_valid' is violated.
  JSON duplicate key -> OK: [{"a": 2}]
== 6. Semantic comparison in SQL (same facts, different key order)
  JSON  payload = cast(? as json) -> OK: [1]
  TEXT  payload = ? -> OK: [0]
  TEXT  cast(payload as json) = cast(? as json) -> OK: [1]
== 7. Worst-case size: 100 items at max values, then past TEXT's limit
  100 items worst case = 5685 bytes
  4000 items = 138964 bytes
  TEXT stores 4000 items -> ERROR DataException: Data truncation: Data too long for column 'payload' at row 1
  JSON stores 4000 items -> OK: kotlin.Unit
== 8. Reversibility with ALTER
  TEXT -> JSON -> OK: [0]
  JSON -> TEXT recovers original? -> OK: [0]
== 8b. CHECK declared on the entity, generated by Hibernate
  show create -> OK: CREATE TABLE `probe_checked` (   `id` bigint NOT NULL AUTO_INCREMENT,   `payload` text COLLATE utf8mb4_general_ci NOT NULL,   PRIMARY KEY (`id`),   CONSTRAINT `CK_PROBE_CHECKED_PAYLOAD` CHECK (json_valid(`payload`)) ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  valid accepted -> OK: kotlin.Unit
  garbage rejected -> ERROR ConstraintViolationException: Check constraint 'CK_PROBE_CHECKED_PAYLOAD' is violated.
== 9. Cost: one row per commit, alternating batches; first round discarded
  2 items insert ms  TEXT p50=1.24 p99=2.23 | JSON p50=1.30 p99=2.50  (n=2000 each)
  2 items bytes per value  TEXT=[135.0000]  JSON=[157.0000]
  2 items table data_length  [[probe_json, 524288], [probe_text, 475136]]
  2 items read+parse 100-row batch ms  TEXT p50=1.08 | JSON p50=1.12
  2 items json_extract full scan of [2500] rows ms  TEXT=3.3 JSON=1.3
  100 items insert ms  TEXT p50=1.33 p99=2.45 | JSON p50=1.36 p99=2.28  (n=2000 each)
  100 items bytes per value  TEXT=[3363.0000]  JSON=[3881.0000]
  100 items table data_length  [[probe_json, 14172160], [probe_text, 11026432]]
  100 items read+parse 100-row batch ms  TEXT p50=5.84 | JSON p50=6.29
  100 items json_extract full scan of [2500] rows ms  TEXT=60.2 JSON=2.2
```

Section 9 of the earlier run, with `CHECK (json_valid(payload))` on the `TEXT` table:

```text
== 9. Cost: one row per commit, alternating batches; first round discarded
  2 items insert ms  TEXT p50=1.31 p99=3.00 | JSON p50=1.39 p99=3.07  (n=2000 each)
  2 items bytes per value  TEXT=[135.0000]  JSON=[157.0000]
  2 items table data_length  [[probe_json, 524288], [probe_text, 475136]]
  2 items read+parse 100-row batch ms  TEXT p50=1.11 | JSON p50=1.16
  2 items json_extract full scan of [2500] rows ms  TEXT=3.1 JSON=1.4
  100 items insert ms  TEXT p50=1.22 p99=2.08 | JSON p50=1.27 p99=2.82  (n=2000 each)
  100 items bytes per value  TEXT=[3363.0000]  JSON=[3881.0000]
  100 items table data_length  [[probe_json, 14172160], [probe_text, 11026432]]
  100 items read+parse 100-row batch ms  TEXT p50=5.59 | JSON p50=5.62
  100 items json_extract full scan of [2500] rows ms  TEXT=57.3 JSON=1.7
```

## The probe

```kotlin
package com.loopers.probe

import com.loopers.adapter.integration.kafka.OrderConfirmedMessage
import com.loopers.adapter.integration.kafka.OrderEventsProperties
import com.loopers.support.TopicReader
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.time.Instant

@Entity
@Table(name = "probe_text")
class TextProbe(@Column(columnDefinition = "text", nullable = false) val payload: String) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0
}

@Entity
@Table(name = "probe_json")
class JsonProbe(@Column(columnDefinition = "json", nullable = false) val payload: String) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0
}

@Entity
@Table(name = "probe_checked", check = [jakarta.persistence.CheckConstraint(name = "CK_PROBE_CHECKED_PAYLOAD", constraint = "json_valid(payload)")])
class CheckedProbe(@Column(columnDefinition = "text", nullable = false) val payload: String) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0
}

@TestPropertySource(properties = ["order-events.topic=order-events-probe"])
class PayloadProbeTest : BaseCommittingApplicationServiceTest() {
    @Autowired lateinit var em: EntityManager
    @Autowired lateinit var tm: PlatformTransactionManager
    @Autowired lateinit var mapper: JsonMapper
    @Autowired lateinit var kafka: KafkaTemplate<Any, Any>
    @Autowired lateinit var kafkaProperties: KafkaProperties
    @Autowired lateinit var props: OrderEventsProperties

    private val out = File("probe-results.txt")
    private fun say(s: String) { out.appendText(s + "\n") }
    private fun tx(block: () -> Unit) { TransactionTemplate(tm).executeWithoutResult { block() } }
    private fun sql(q: String): List<Any?> {
        var r: List<Any?> = emptyList()
        tx { r = em.createNativeQuery(q).resultList }
        return r
    }
    private fun ddl(q: String) { tx { em.createNativeQuery(q).executeUpdate() } }
    private fun attempt(label: String, block: () -> Any?) {
        try { say("  $label -> OK: ${block()}") } catch (e: Exception) {
            var c: Throwable = e; while (c.cause != null) c = c.cause!!
            say("  $label -> ERROR ${e.javaClass.simpleName}: ${c.message?.take(110)}")
        }
    }

    private fun payload(items: Int, at: String = "2026-10-27T01:02:03.123456Z", base: Long = 1000, id: Long = 12345) =
        OrderConfirmedMessage.Payload(id, Instant.parse(at), (1..items).map { OrderConfirmedMessage.Payload.Item(base + it, it) })
    private fun envelope(p: OrderConfirmedMessage.Payload) =
        OrderConfirmedMessage("order-confirmed:v1:${p.orderId}", OrderConfirmedMessage.EVENT_TYPE, OrderConfirmedMessage.SCHEMA_VERSION, p)

    private fun roundTrip(p: String): Pair<String, String> {
        var t = 0L; var j = 0L
        tx { val a = TextProbe(p); val b = JsonProbe(p); em.persist(a); em.persist(b); em.flush(); t = a.id; j = b.id }
        var rt = ""; var rj = ""
        tx { em.clear(); rt = em.find(TextProbe::class.java, t).payload; rj = em.find(JsonProbe::class.java, j).payload }
        return rt to rj
    }

    @Test
    fun probe() {
        out.writeText("")
        val small = payload(2)
        val smallText = mapper.writeValueAsString(small)
        say("== 1. Hibernate String field: read back what was written")
        val (rt, rj) = roundTrip(smallText)
        say("  written  $smallText"); say("  TEXT     $rt"); say("  JSON     $rj")
        say("  TEXT identical=${rt == smallText}  JSON identical=${rj == smallText}")

        say("== 2. Rehydrate -> real KafkaTemplate -> real record bytes")
        val reader = TopicReader(kafkaProperties.bootstrapServers, props.topic)
        val direct = envelope(small)
        val viaText = envelope(mapper.readValue(rt, OrderConfirmedMessage.Payload::class.java))
        val viaJson = envelope(mapper.readValue(rj, OrderConfirmedMessage.Payload::class.java))
        listOf(direct, viaText, viaJson).forEach { kafka.send(props.topic, "12345", it).get() }
        val rec = reader.publishedRecords(3).map { it.value() }
        reader.close()
        say("  W6 path  ${rec[0]}")
        say("  via TEXT identical to W6 path=${rec[1] == rec[0]}  via JSON identical to W6 path=${rec[2] == rec[0]}")

        say("== 3. Value edge cases survive storage + parse")
        val cases = listOf(
            payload(2, "2026-10-27T01:02:03.100Z"), payload(2, "2026-10-27T01:02:03Z"),
            payload(3, base = Long.MAX_VALUE - 10, id = Long.MAX_VALUE),
            OrderConfirmedMessage.Payload(1, Instant.parse("2026-10-27T01:02:03.000001Z"), listOf(OrderConfirmedMessage.Payload.Item(9, Int.MAX_VALUE), OrderConfirmedMessage.Payload.Item(1, 1))),
        )
        cases.forEach { c ->
            val (a, b) = roundTrip(mapper.writeValueAsString(c))
            say("  TEXT=${mapper.readValue(a, OrderConfirmedMessage.Payload::class.java) == c} JSON=${mapper.readValue(b, OrderConfirmedMessage.Payload::class.java) == c}  ${b.take(90)}")
        }

        say("== 4. SQL inspection works on which column?")
        listOf("probe_text", "probe_json").forEach { tb ->
            attempt("$tb json_extract") { sql("select json_extract(payload,'$.items[0].productId') from $tb limit 1") }
            attempt("$tb ->>") { sql("select payload->>'$.confirmedAt' from $tb limit 1") }
            attempt("$tb json_length") { sql("select json_length(payload,'$.items') from $tb limit 1") }
        }

        say("== 5. Bad input")
        attempt("TEXT accepts garbage") { tx { em.persist(TextProbe("not json")); em.flush() } }
        attempt("JSON accepts garbage") { tx { em.persist(JsonProbe("not json")); em.flush() } }
        ddl("delete from probe_text where payload = 'not json'")
        attempt("TEXT + CHECK(json_valid) DDL") { ddl("alter table probe_text add constraint ck_probe_valid check (json_valid(payload))") }
        attempt("TEXT + CHECK accepts garbage") { tx { em.persist(TextProbe("not json")); em.flush() } }
        attempt("JSON duplicate key") { tx { em.persist(JsonProbe("{\"a\":1,\"a\":2}")); em.flush() }; sql("select payload from probe_json order by id desc limit 1") }

        say("== 6. Semantic comparison in SQL (same facts, different key order)")
        val reordered = "{\"items\":[{\"quantity\":1,\"productId\":1001},{\"quantity\":2,\"productId\":1002}],\"orderId\":12345,\"confirmedAt\":\"2026-10-27T01:02:03.123456Z\"}"
        attempt("JSON  payload = cast(? as json)") { sql("select count(*) from probe_json where payload = cast('$reordered' as json)") }
        attempt("TEXT  payload = ?") { sql("select count(*) from probe_text where payload = '$reordered'") }
        attempt("TEXT  cast(payload as json) = cast(? as json)") { sql("select count(*) from probe_text where cast(payload as json) = cast('$reordered' as json)") }

        say("== 7. Worst-case size: 100 items at max values, then past TEXT's limit")
        val worst = OrderConfirmedMessage.Payload(Long.MAX_VALUE, Instant.parse("2026-10-27T01:02:03.123456Z"), (1..100).map { OrderConfirmedMessage.Payload.Item(Long.MAX_VALUE - it, Int.MAX_VALUE) })
        say("  100 items worst case = ${mapper.writeValueAsString(worst).length} bytes")
        val huge = mapper.writeValueAsString(payload(4000))
        say("  4000 items = ${huge.length} bytes")
        attempt("TEXT stores 4000 items") { tx { em.persist(TextProbe(huge)); em.flush() } }
        attempt("JSON stores 4000 items") { tx { em.persist(JsonProbe(huge)); em.flush() } }

        say("== 8. Reversibility with ALTER")
        ddl("truncate table probe_text"); ddl("truncate table probe_json")
        tx { em.persist(TextProbe(smallText)); em.persist(JsonProbe(smallText)); em.flush() }
        ddl("create table p_t2 like probe_text"); ddl("insert into p_t2 select * from probe_text")
        ddl("create table p_j2 like probe_json"); ddl("insert into p_j2 select * from probe_json")
        attempt("TEXT -> JSON") { ddl("alter table p_t2 modify payload json not null"); sql("select cast(payload as char) = '$smallText' from p_t2") }
        attempt("JSON -> TEXT recovers original?") { ddl("alter table p_j2 modify payload text not null"); sql("select payload = '$smallText' from p_j2") }
        ddl("drop table p_t2"); ddl("drop table p_j2")

        say("== 8b. CHECK declared on the entity, generated by Hibernate")
        attempt("show create") { (sql("show create table probe_checked").single() as Array<*>)[1].toString().replace("\n", " ") }
        attempt("valid accepted") { tx { em.persist(CheckedProbe(smallText)); em.flush() } }
        attempt("garbage rejected") { tx { em.persist(CheckedProbe("not json")); em.flush() } }
        ddl("alter table probe_text drop check ck_probe_valid")
        say("== 9. Cost: one row per commit, alternating batches; first round discarded")
        for ((name, items) in listOf("2 items" to 2, "100 items" to 100)) {
            val text = mapper.writeValueAsString(payload(items))
            ddl("truncate table probe_text"); ddl("truncate table probe_json")
            val ts = mutableListOf<Long>(); val js = mutableListOf<Long>()
            fun batch(sink: MutableList<Long>?, make: () -> Any) { repeat(500) { val t0 = System.nanoTime(); tx { em.persist(make()) }; sink?.add(System.nanoTime() - t0) } }
            batch(null) { TextProbe(text) }; batch(null) { JsonProbe(text) }
            repeat(4) { r ->
                if (r % 2 == 0) { batch(ts) { TextProbe(text) }; batch(js) { JsonProbe(text) } } else { batch(js) { JsonProbe(text) }; batch(ts) { TextProbe(text) } }
            }
            fun q(l: List<Long>, p: Double) = "%.2f".format(l.sorted()[((l.size - 1) * p).toInt()] / 1e6)
            say("  $name insert ms  TEXT p50=${q(ts, .5)} p99=${q(ts, .99)} | JSON p50=${q(js, .5)} p99=${q(js, .99)}  (n=${ts.size} each)")
            sql("analyze table probe_text"); sql("analyze table probe_json")
            say("  $name bytes per value  TEXT=${sql("select avg(length(payload)) from probe_text")}  JSON=${sql("select avg(json_storage_size(payload)) from probe_json")}")
            say("  $name table data_length  ${sql("select table_name, data_length from information_schema.tables where table_schema = database() and table_name in ('probe_text','probe_json') order by table_name").map { (it as Array<*>).toList() }}")
            val rtN = mutableListOf<Long>(); val rjN = mutableListOf<Long>()
            fun read(tb: String, sink: MutableList<Long>) { repeat(100) { val t0 = System.nanoTime(); var rows: List<Any?> = emptyList(); tx { rows = em.createNativeQuery("select payload from $tb order by id limit 100").resultList }; rows.forEach { mapper.readValue(it as String, OrderConfirmedMessage.Payload::class.java) }; sink.add(System.nanoTime() - t0) } }
            read("probe_text", mutableListOf()); read("probe_json", mutableListOf())
            repeat(4) { r -> if (r % 2 == 0) { read("probe_text", rtN); read("probe_json", rjN) } else { read("probe_json", rjN); read("probe_text", rtN) } }
            say("  $name read+parse 100-row batch ms  TEXT p50=${q(rtN, .5)} | JSON p50=${q(rjN, .5)}")
            fun scan(tb: String): Double { val t0 = System.nanoTime(); repeat(5) { sql("select count(*) from $tb where json_extract(payload,'$.orderId') = 1") }; return (System.nanoTime() - t0) / 5e6 }
            scan("probe_text"); scan("probe_json")
            say("  $name json_extract full scan of ${sql("select count(*) from probe_text")} rows ms  TEXT=%.1f JSON=%.1f".format(scan("probe_text"), scan("probe_json")))
        }
    }
}
```
