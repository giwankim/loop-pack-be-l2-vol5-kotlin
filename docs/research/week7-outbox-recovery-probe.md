# Week 7 outbox recovery: the W6-jar probe

One throwaway run on 2026-11-05, during step 3's interview, on `92c007d`. It asked whether week 6's real revision could prepare the assignment's §5 state, and what moving that database to week 7 would take. The interview then chose not to run the W6 jar in the suite ([ADR 0036](../adr/0036-missing-outbox-rows-are-recovered-for-named-orders-from-stored-facts.md) rule 5), so this note is the only evidence for the parts of that path the suite doesn't test ([design 14.3](../design/order-outbox.md#143-5절의-준비-보존한-w6-테스트-구성)).

## How it ran

| Part | What |
| --- | --- |
| Containers | `docker run` on free ports, outside any compose project: `mysql:8.4`, `redis:8.10`, `apache/kafka-native:4.4.0` (the suite's images) |
| W6 | `git worktree add … eba8534` (week 6's tip, the same tree as the `week7` merge `845966c`), then its `bootJar` |
| HEAD | The commerce-api and commerce-streamer `bootJar`s at `92c007d` |
| PG | A Python stub that answers every create with 202 and `SUCCESS`, and logs each create |
| Driver | `curl` against the jar's HTTP API. The user row was inserted with SQL, and the brand, products and points went through the jar's admin and customer APIs. Each order was product 1 × 2 and product 2 × 1 |

Every jar ran with `--spring.profiles.active=test`, the MySQL URL with a database name, the Redis master and replica host and port, `--spring.kafka.bootstrap-servers`, `--spring.kafka.admin.fail-fast=false`, `--server.port` and `--management.server.port`, `--pg.response-timeout=1s` (the test profile's 200 ms timed out against the stub), and `PG_BASE_URL` and `PG_CALLBACK_URL` in the environment. `ddl-auto` was `create` on the first W6 run and `none` on the second.

**Limits.**

- One run of each step, on a laptop through Docker.
- Two values come from the probe's report, not from a saved file: the confirm's 0.12 s response, and the empty topic list after the kill. Every other value below is in a saved log or dump.
- Not run: the whole sequence from W6's state through W7's recovery to the relay (recovery didn't exist yet), a second `update` start, launching any of this from a test, a stop before the send on a *reachable* broker, and `update` on a large table.

## What it found

| Question | Finding |
| --- | --- |
| Does the W6 revision still build? | Yes, in 22 s with today's toolchain. The two HEAD jars took 11 s together |
| Does the W6 jar start without a broker? | Yes, with admin fail-fast off. It took 46.8 s instead of 6.9 s, while the admin client logged 80 `TimeoutException` lines (`Timed out waiting for a node assignment`) |
| Case A: committed, stopped before the send | Confirmed and committed with no record. The confirm answered `CONFIRMED` while `OrderConfirmedKafkaRelay.relay` was blocked in `KafkaProducer.waitOnMetadata`, before any byte left. After `kill -9` the broker held no topic at all. That is guaranteed by the setup: the jar never had a broker address |
| Case B: published and consumed | A second W6 jar (`ddl-auto=none`, real broker) confirmed order 2 and logged `Published the order confirmed event. eventId=order-confirmed:v1:2, topic=order-events, partition=2, offset=0`. Order 1 stayed `CONFIRMED` |
| What does W7 need to add the outbox table? | `ddl-auto=update` creates it with the unique key, the check and the index, and removes nothing. It also issues four `modify column` statements on W6 tables, which changed nothing here |
| Does the streamer start without dropping commerce tables? | Yes, with `ddl-auto=create`: it creates only its own two tables. The commerce tables' `SHOW CREATE TABLE` and row counts were unchanged |
| Can a test count deliveries from the streamer? | Yes, one line per delivery, with the offset and the result |

### The state before W7

| | Order 1 (A) | Order 2 (B) |
| --- | --- | --- |
| Status, `confirmed_at` | `CONFIRMED`, `2026-11-05 09:24:47.336933` | `CONFIRMED`, `2026-11-05 09:25:29.621060` |
| Record on the broker | none | partition 2, offset 0 |
| PG creates | 1 | 1 |

Stock after both: product 1 at 96, product 2 at 98, from 100. These values were the same before and after W7's `update` start.

### The thread dump during A

```text
at org.apache.kafka.clients.producer.KafkaProducer.waitOnMetadata(KafkaProducer.java:1131)
at org.apache.kafka.clients.producer.KafkaProducer.doSend(KafkaProducer.java:995)
at org.apache.kafka.clients.producer.KafkaProducer.send(KafkaProducer.java:951)
at org.springframework.kafka.core.DefaultKafkaProducerFactory$CloseSafeProducer.send(DefaultKafkaProducerFactory.java:1129)
at org.springframework.kafka.core.KafkaTemplate.doSend(KafkaTemplate.java:865)
at org.springframework.kafka.core.KafkaTemplate.observeSend(KafkaTemplate.java:833)
at org.springframework.kafka.core.KafkaTemplate.send(KafkaTemplate.java:609)
at com.loopers.adapter.integration.kafka.OrderConfirmedKafkaRelay.relay(OrderConfirmedKafkaRelay.kt:50)
```

The relay was `@Async`, so the thread was the relay's executor, not the request's. The send waits there for up to week 6's `max.block.ms` (the client default, 60 s), and no WARN had been logged yet when the process was killed.

### `ddl-auto=update` on the W6 database

The `SHOW CREATE TABLE` diff of the whole schema, before and after, removed no line and added one table:

```sql
CREATE TABLE `order_event_outbox` (
  ...
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_ORDER_EVENT_OUTBOX_ON_EVENT_ID` (`event_id`),
  KEY `IDX_ORDER_EVENT_OUTBOX_ON_STATUS_AND_NEXT_ATTEMPT_AT_AND_ID` (`status`,`next_attempt_at`,`id`),
  CONSTRAINT `CK_ORDER_EVENT_OUTBOX_ON_PAYLOAD_JSON_VALID` CHECK (json_valid(`payload`))
)
```

Hibernate also ran these, which left each column's definition as it was:

```sql
alter table orders modify column payment_method enum ('PG','POINT')
alter table orders modify column status enum ('CONFIRMED','DRAFT','PAYMENT_FAILED','PAYMENT_PENDING') not null
alter table payment modify column idempotency_key varchar(64) character set utf8mb4 collate utf8mb4_bin not null
alter table payment modify column status enum ('FAILED','PENDING','SUCCESS','UNKNOWN') not null
```

So "touches no W6 table" holds for the result, not for the statements. On a large table a `modify column` can rebuild the table, which wasn't measured. The alternative, running the generated DDL's three outbox statements (create, index, unique), gives the same keys and check with a different column order.

### The streamer's lines

The second delivery was produced by hand with the console producer, copying the first record:

```text
Recorded the order confirmed event. eventId=order-confirmed:v1:2, orderId=2, partition=2, offset=0, result=RECORDED
Recorded the order confirmed event. eventId=order-confirmed:v1:2, orderId=2, partition=2, offset=1, result=ALREADY_RECORDED
```

The purchase activity stayed at two rows after the second.

## Cleanup

The containers, processes and both worktrees were removed. An `apache/kafka:4.1.0` image stayed pulled. The scripts and raw logs were in the session scratchpad, not the repo: `run-w6.sh`, `run-w7.sh`, `drive.sh`, `pg.py`, the jars' logs, the thread dump, the schema dumps before and after each start, and the generated DDL.
