# 결제 사고 설계 — 끝나지 않은 결제를 지표로 알리고, 사고를 재현해 복구한다

10주차 과제의 설계 문서다. 브랜치마다 절 하나를 쓴다(`weekly-ranking.md`의 방식). 용어는 [`CONTEXT.md`](../../CONTEXT.md)를 따른다("끝나지 않은 결제", "결과 미확인", "처리 중", "대사"). 결정의 출처는 1단계가 [명세 266](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/266)이다.

상태: 2026-11-26 1단계(`feat/payment-observability`)를 인터뷰했다. 이 문서는 그 첫 티켓([issue 267](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/267))이 뼈대로 만들었고, 절의 내용은 단계를 구현한 뒤 쓴다.

## 1. 근거와 범위

### 1.1 세 단계

| 단계 | 하는 것 | 브랜치 | 상태 |
| --- | --- | --- | --- |
| 1 | 끝나지 않은 결제의 수를 gauge로 알리고, PG의 답과 TX B의 결과를 주문별 로그 이벤트로 남긴다. 실제 포트의 테스트 기반. | `feat/payment-observability` | 인터뷰 끝남(2026-11-26). [명세 266](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/266) |
| 2 | 사고의 재현(정상 10건과 장애 10건), 가설의 판정, 대사 API로 하는 복구, 증거 파일. | `feat/payment-incident` | 인터뷰 전 |
| 3 | 아웃박스 → Kafka → 구매 활동 → 주간 랭킹의 후속 확인, `docs/runbook/week10.md`, 기능별 검증 범위와 남은 위험. | `feat/payment-incident-follow-on` | 인터뷰 전 |

셋은 모두 `week10`으로 PR한다.

### 1.2 1단계가 만든 것

| 티켓 | 만든 것 |
| --- | --- |
| [267](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/267) | 실제 포트의 테스트 기반 `BaseRealPortWebApiAdapterTest`와 `ManagementEndpointApiTest`. 운영 코드는 없다. |

티켓마다 구현 중에 정한 것은 [글감](../research/week10-payment-observability-writing-material.md)에 있다.

## 2. 1단계: 끝나지 않은 결제의 지표와 결제의 로그 이벤트

(1단계를 구현한 뒤 쓴다.)

## 3. 2단계: 사고의 재현과 복구

(2단계를 인터뷰한 뒤 쓴다.)

## 4. 3단계: 후속 확인과 runbook

(3단계를 인터뷰한 뒤 쓴다.)
