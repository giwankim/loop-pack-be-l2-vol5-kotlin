# Context map

이 저장소에는 맥락이 셋이다. ADR은 모두 [`docs/adr/`](docs/adr/)에 둔다.

| 맥락 | 앱 | 용어집 |
| --- | --- | --- |
| 커머스 (Commerce) | commerce-api | [`CONTEXT.md`](CONTEXT.md) |
| 구매 활동 (Purchase Activity) | commerce-streamer | [`apps/commerce-streamer/CONTEXT.md`](apps/commerce-streamer/CONTEXT.md) |
| 주간 랭킹 (Weekly Ranking) | commerce-batch | [`apps/commerce-batch/CONTEXT.md`](apps/commerce-batch/CONTEXT.md) |

커머스와 구매 활동은 `order-events` 토픽의 주문 확정 이벤트로만 이어진다. 커머스가 발행하고([ADR 0032](docs/adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md), [ADR 0034](docs/adr/0034-order-confirmed-is-left-in-an-outbox-row-by-tx-b.md)), 구매 활동이 기록한다([ADR 0033](docs/adr/0033-purchase-activity-is-recorded-by-reading-first-and-an-unprocessable-event-stops-the-consumer.md)). 계약은 [주문 이벤트 설계](docs/design/order-events.md) 2절이다. 구매 활동은 커머스의 표를 읽지 않고, 주문과 상품을 식별자로만 안다.

구매 활동과 주간 랭킹은 `purchase_activity` 표로만 이어진다. 구매 활동이 쓰고, 주간 랭킹은 고정할 때만 읽는다. 계약은 네 컬럼(`order_id`, `product_id`, `quantity`, `confirmed_at`)과 삭제되지 않은 행(`deleted_at is null`)이다. 주간 랭킹은 확정 시각으로 한 랭킹 기간의 행만 읽으므로 `confirmed_at`의 인덱스도 계약에 든다. 주간 랭킹은 구매 활동의 클래스를 쓰지 않고 그 모듈에 의존하지 않는다.

주간 랭킹과 커머스는 주간 랭킹의 세 표로만 이어진다. 주간 랭킹이 `weekly_ranking_publication`, `weekly_ranking_snapshot`, `weekly_ranking_item`을 쓰고, 커머스는 그 셋을 읽기만 한다. 배치의 다른 표(`weekly_ranking_snapshot_input`, `weekly_ranking_score`, Batch 메타데이터)는 읽지 않는다. 계약은 커머스가 읽는 컬럼이다.

| 표 | 커머스가 읽는 컬럼 |
| --- | --- |
| `weekly_ranking_publication` | `period_start`, `snapshot_id` |
| `weekly_ranking_snapshot` | `snapshot_id`, `period_start`, `frozen_at` |
| `weekly_ranking_item` | `snapshot_id`, `product_id`, `score`, `product_rank` |

게시가 랭킹 기간이 보이는 스냅샷을 정하고, 커머스는 그 스냅샷 ID로만 주간 랭킹을 읽는다. 시각은 UTC의 벽시계 시각으로 담긴다. 커머스는 이 표들을 엔티티로 잇지 않고 SQL로 읽으며, 주간 랭킹의 클래스를 쓰지 않고 그 모듈에 의존하지 않는다. 표의 원본은 commerce-batch의 `weekly-ranking-schema.sql`이고, commerce-api의 테스트는 계약의 컬럼만 담은 사본으로 표를 만든다.
