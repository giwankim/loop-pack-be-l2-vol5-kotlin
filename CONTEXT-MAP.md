# Context map

이 저장소에는 맥락이 셋이다. ADR은 모두 [`docs/adr/`](docs/adr/)에 둔다.

| 맥락 | 앱 | 용어집 |
| --- | --- | --- |
| 커머스 (Commerce) | commerce-api | [`CONTEXT.md`](CONTEXT.md) |
| 구매 활동 (Purchase Activity) | commerce-streamer | [`apps/commerce-streamer/CONTEXT.md`](apps/commerce-streamer/CONTEXT.md) |
| 주간 랭킹 (Weekly Ranking) | commerce-batch | [`apps/commerce-batch/CONTEXT.md`](apps/commerce-batch/CONTEXT.md) |

커머스와 구매 활동은 `order-events` 토픽의 주문 확정 이벤트로만 이어진다. 커머스가 발행하고([ADR 0032](docs/adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md), [ADR 0034](docs/adr/0034-order-confirmed-is-left-in-an-outbox-row-by-tx-b.md)), 구매 활동이 기록한다([ADR 0033](docs/adr/0033-purchase-activity-is-recorded-by-reading-first-and-an-unprocessable-event-stops-the-consumer.md)). 계약은 [주문 이벤트 설계](docs/design/order-events.md) 2절이다. 구매 활동은 커머스의 표를 읽지 않고, 주문과 상품을 식별자로만 안다.

구매 활동과 주간 랭킹은 `purchase_activity` 표로만 이어진다. 구매 활동이 쓰고, 주간 랭킹은 고정할 때만 읽는다. 계약은 네 컬럼(`order_id`, `product_id`, `quantity`, `confirmed_at`)과 삭제되지 않은 행(`deleted_at is null`)이다. 주간 랭킹은 확정 시각으로 한 랭킹 기간의 행만 읽으므로 `confirmed_at`의 인덱스도 계약에 든다. 주간 랭킹은 구매 활동의 클래스를 쓰지 않고 그 모듈에 의존하지 않는다.
