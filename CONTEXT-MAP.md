# Context map

이 저장소에는 맥락이 둘이다. ADR은 둘 다 [`docs/adr/`](docs/adr/)에 둔다.

| 맥락 | 앱 | 용어집 |
| --- | --- | --- |
| 커머스 (Commerce) | commerce-api | [`CONTEXT.md`](CONTEXT.md) |
| 구매 활동 (Purchase Activity) | commerce-streamer | [`apps/commerce-streamer/CONTEXT.md`](apps/commerce-streamer/CONTEXT.md) |

두 맥락은 `order-events` 토픽의 주문 확정 이벤트로만 이어진다. 커머스가 발행하고([ADR 0032](docs/adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md)), 구매 활동이 기록한다([ADR 0033](docs/adr/0033-purchase-activity-is-recorded-by-reading-first-and-an-unprocessable-event-stops-the-consumer.md)). 계약은 [주문 이벤트 설계](docs/design/order-events.md) 2절이다. 구매 활동은 커머스의 표를 읽지 않고, 주문과 상품을 식별자로만 안다.
