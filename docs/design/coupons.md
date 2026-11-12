# 쿠폰 설계 — 발급 한도와 한 사용자 한 쿠폰

8주차 과제의 설계 문서다. 용어는 [`CONTEXT.md`](../../CONTEXT.md)의 "쿠폰" 절과 행위의 "발급하다", "발급을 중지하다", 그리고 "처리 자리", "수용량", "자리를 얻다", "자리를 놓다"를 따른다. 결정의 출처는 1단계가 [명세 219](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/219), 2단계가 [명세 226](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/226)이고, 두 단계 모두 새 ADR을 두지 않는다. 잠금은 [ADR 0018](../adr/0018-pessimistic-row-locks-in-one-global-order.md)의 방식을 따르고 그 ADR에 날짜 메모를 달았다.

상태: 2026-11-11 1단계(`feat/coupon-issue`)를 인터뷰하고, 같은 날 그 다섯 티켓을 구현한 뒤 이 문서를 썼다. 2026-11-12 2단계(`feat/coupon-admission`)를 인터뷰하고 그 세 티켓을 구현한 뒤 9절을 더하고 10절을 3단계에 맞게 고쳐 썼다. 장애 경계(3단계)는 그 인터뷰 뒤에 절을 더한다.

## 1. 근거와 범위

- 과제: 8주차 실습 "Redis로 작업 수용량 통제하기"의 1·2절, 그리고 6절의 앞 여섯 장면.
- 이 단계는 Redis 없이도 옳아야 하는 것만 다룬다. 발급 한도, 같은 사용자의 두 번째 요청, 발급 수와 쿠폰이 함께 남는 것이다.
- 7주차의 결과에서 시작한다. 주문, 결제, 아웃박스, 구매 활동은 바뀌지 않는다. commerce-streamer는 건드리지 않는다.

### 1.1 세 단계

| 단계 | 하는 것 | 브랜치 | 상태 |
| --- | --- | --- | --- |
| 1 | 쿠폰 캠페인과 쿠폰. 등록, 발급, 발급 중지, 자기 쿠폰 목록. 발급 한도와 중복을 DB가 지킨다. | `feat/coupon-issue` | 인터뷰 끝남(2026-11-11). 구현 끝남(2026-11-11, 1.2절). [명세 219](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/219) |
| 2 | 발급 서비스 안, 이른 조회와 발급 트랜잭션 사이의 처리 자리(Redis Set, Lua, 429). Redis가 정상일 때의 길만. | `feat/coupon-admission` | 인터뷰 끝남(2026-11-12). 구현 끝남(2026-11-12, 9.1절). [명세 226](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/226) |
| 3 | Redis 장애의 경계(503, 수용 닫힘과 재개). | `feat/coupon-admission-failure` | 인터뷰 전 |

셋은 모두 `week8`로 PR한다. DB 잠금 앞에 Redis 잠금을 더하는 실험(`feat/coupon-issue-redis-lock`)은 세 단계가 끝난 뒤 정할 선택지다(6절).

### 1.2 1단계가 만든 것

| 티켓 | 만든 것 |
| --- | --- |
| [220](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/220) | `CouponCampaign`, 등록 포트 `CouponCampaignRegister`, `POST /api-admin/v1/coupon-campaigns`. |
| [221](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/221) | `Coupon`, 발급 포트 `CouponIssuer`(`CouponIssueService`와 `CouponIssueSteps`), `POST /api/v1/coupon-campaigns/{campaignId}/issues`. |
| [222](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/222) | `CouponCampaign.suspend()`, `POST /api-admin/v1/coupon-campaigns/{campaignId}/suspend`. |
| [223](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/223) | 자기 쿠폰 목록 `CouponFinder`, `GET /api/v1/coupons`. |
| [224](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/224) | 경합 장면 넷(`CouponIssueIntegrationTest`, 8절)과 이 문서, ADR 0018의 날짜 메모. 운영 코드는 바뀌지 않았다. |

티켓마다 구현 중에 정한 것은 [글감](../research/week8-coupon-issue-writing-material.md)에 있다.

## 2. 모델

`coupon` 슬라이스에 애그리거트 루트 둘을 둔다. 둘 다 `BaseEntity`를 상속하고 논리 삭제를 지원하지만, 이 단계에서 지우는 길은 없다.

- **`CouponCampaign`**: 이름, 할인액(`Money`), 발급 한도(`totalQuota`), 발급 수(`issuedCount`), 발급 중지 여부(`suspended`). 등록 때 발급 수는 0이고 발급 중지가 아니다. 스펙은 이 값을 발급 가능 여부(`issuable`)로 적었지만, 소진된 쿠폰 캠페인도 `issuable = true`로 읽혀서 `CONTEXT.md`의 발급 중지를 그대로 이름으로 삼았다. 이름은 비어 있지 않고 100자 이하, 할인액은 1원 이상, 발급 한도는 1 이상이다. 엔티티가 직접 검사하고, 요청 DTO는 Bean Validation으로도 검사한다.
  - `issue()`: 발급 중지면 `COUPON_CAMPAIGN_SUSPENDED`, 소진이면 `COUPON_CAMPAIGN_EXHAUSTED`로 거절한다. 이 차례다. 통과하면 발급 수를 1 늘린다. 발급 한도의 규칙은 여기 한 곳에 있다. 재고가 `Product`에 있는 것과 같다.
  - `suspend()`: 발급을 멈춘다. 이미 멈췄으면 그대로다. 되돌리는 메서드는 없다.
- **`Coupon`**: 쿠폰 캠페인 식별자, 사용자 식별자, 발급 시각(`Clock`), 발급 당시 쿠폰 캠페인의 이름과 할인액. 발급 뒤 바뀌지 않는다. 그래서 목록은 쿠폰 캠페인을 읽지 않는다.
- 유일 제약 `UK_COUPON_ON_CAMPAIGN_ID_AND_USER_ID (campaign_id, user_id)`는 뒤의 방어다(3.2절). 목록은 `IDX_COUPON_ON_USER_ID_AND_ISSUED_AT_AND_ID (user_id, issued_at DESC, id DESC)`가 정렬 단계 없이 받친다.
- `coupon`은 `coupon_campaign`과 `users`에 외래 키를 둔다(ADR 0015).
- 혜택의 종류 enum, 설명 문자열, 쿠폰의 사용 상태는 두지 않는다. 쿠폰의 사용은 과제의 범위 밖이다.

## 3. 발급

### 3.1 두 걸음

발급 서비스(`CouponIssueService`)는 통째로 한 트랜잭션이 아니다. `Propagation.NEVER`라서 부르는 쪽의 트랜잭션도 거절한다.

1. **이른 조회.** 트랜잭션 밖에서 요청자의 쿠폰을 읽는다. 있으면 200으로 그 쿠폰을 돌려주고 아무것도 잠그지 않는다. 소진되거나 발급이 중지된 쿠폰 캠페인이라도 이미 받은 쿠폰은 돌려준다. 응답을 잃고 다시 보낸 요청이 이 길로 끝난다.
2. **발급 트랜잭션.** 없으면 `CouponIssueSteps`가 따로 트랜잭션을 연다. 5주차 확정의 saga와 그 steps 빈이 선례다.

둘로 나눈 까닭은 둘이다. 이미 받은 사용자의 재요청이 쿠폰 캠페인 행의 줄에 서지 않는다. 그리고 다음 단계가 두 걸음 사이에서 처리 자리를 얻고, 트랜잭션이 끝난 뒤 그 자리를 놓을 수 있다.

부르는 쪽의 트랜잭션은 느릴 뿐 아니라 틀린다. 이른 조회가 REPEATABLE READ의 스냅샷을 잠금보다 먼저 정하므로, 잠금 아래의 재확인이 앞선 잠금 보유자가 막 커밋한 쿠폰을 보지 못한다.

### 3.2 발급 트랜잭션

차례는 이렇다.

1. 쿠폰 캠페인을 `FOR UPDATE`로 읽는다(`CouponCampaignFinder.findForUpdate`). 없으면 404 `COUPON_CAMPAIGN_NOT_FOUND`다. 이 잠금이 트랜잭션의 첫 문장이어야 한다. 그래야 아래의 재확인이 일반 읽기여도 앞선 보유자가 커밋한 것을 본다.
2. 요청자의 쿠폰을 다시 읽는다. 있으면 그대로 돌려준다(200).
3. `campaign.issue()`.
4. 쿠폰 캠페인과 쿠폰을 저장소로 저장한다(카탈로그 설계 5.37). 둘은 함께 커밋되거나 함께 되돌아간다.

결과는 쿠폰과 "새로 발급했는가"를 함께 돌려주고(`CouponIssueResult`), 웹 어댑터가 그것으로 201과 200을 고른다.

중복은 잠금 아래의 재확인이 정한다. 같은 사용자의 두 요청이 이른 조회를 함께 지나쳐도, 뒤진 쪽은 잠금을 기다린 뒤 앞선 쪽이 커밋한 쿠폰을 읽는다. 그래서 유일 제약 위반을 잡아 기존 쿠폰을 찾는 복구 경로가 없다. 위반이 일어나면 버그이고 500으로 드러난다.

### 3.3 잠금

- 쿠폰 캠페인 행은 발급 트랜잭션의 첫 읽기에서 잠긴다(ADR 0018 규칙 1, 2). 같은 쿠폰 캠페인의 발급은 그 행에서 하나씩 지나가고, 저마다 앞선 쪽이 커밋한 발급 수를 읽는다.
- 발급 중지도 쿠폰 캠페인을 잠가 읽는다. 낡은 읽기로 저장한 발급 중지가 그 사이 커밋된 발급 수를 덮지 않게 한다.
- 이 잠금은 다른 배타 잠금과 한 트랜잭션에 들지 않는다. 발급 트랜잭션이 넣는 쿠폰 행은 새 행이고, 발급 중지는 쿠폰 캠페인 하나만 바꾼다. 쿠폰 INSERT의 외래 키 검사가 요청자의 `users` 행에 공유 잠금을 걸지만, `users` 행을 배타로 잠그는 경로가 없어 순서에 들지 않는다. 그래서 전역 잠금 순서(ADR 0018 규칙 3)에 새 자리를 만들지 않는다.
- 잠금 대기가 3초를 넘으면 `PessimisticLockingFailureException`이 오고, 기존의 409 `CONCURRENT_REQUEST`가 된다(ADR 0018 규칙 7, 8). 아무것도 발급되지 않았으므로 다시 보내도 된다.

## 4. 발급 중지

`POST /api-admin/v1/coupon-campaigns/{campaignId}/suspend`는 한 방향이다. 이미 발급 중지인 쿠폰 캠페인에 다시 보내면 200이고 바뀌는 것이 없다. 발급 중지 전에 발급된 쿠폰은 남는다. 저장하는 값과 관리자 응답의 필드가 모두 `suspended`다.

## 5. API와 오류

| 요청 | 답 |
| --- | --- |
| `POST /api/v1/coupon-campaigns/{campaignId}/issues` | 새 발급 201, 기존 쿠폰 200. 본문은 같다(`CouponResponse`). `Location` 없음 |
| `GET /api/v1/coupons` | 요청자의 쿠폰 `Slice`. `issuedAt DESC, couponId DESC` |
| `POST /api-admin/v1/coupon-campaigns` | 등록. 201과 쿠폰 캠페인 |
| `POST /api-admin/v1/coupon-campaigns/{campaignId}/suspend` | 발급 중지. 200과 쿠폰 캠페인 |

- `CouponResponse`: `couponId`, `campaignId`, `campaignName`, `discountAmount`, `issuedAt`.
- 관리자 응답(`CouponCampaignAdminResponse`): 식별자(`campaignId`, 고객 응답의 이름과 같다), 이름, 할인액, 발급 한도, 발급 수, 발급 중지 여부.
- 요청자는 기존 헤더 resolver로 받고 다시 확인하지 않는다(ADR 0015). 요청의 어떤 필드도 다른 사용자를 가리키지 못한다.

| 경우 | status | code |
| --- | --- | --- |
| 쿠폰 캠페인 없음 | 404 | `COUPON_CAMPAIGN_NOT_FOUND` |
| 발급 중지 | 409 | `COUPON_CAMPAIGN_SUSPENDED` |
| 소진 | 409 | `COUPON_CAMPAIGN_EXHAUSTED` |
| 잠금 대기 초과 | 409 | `CONCURRENT_REQUEST` |
| 빈 처리 자리 없음(2단계, 9.5절) | 429 | `COUPON_ISSUE_CAPACITY_EXCEEDED` |

## 6. 고르지 않은 것

- **조건부 UPDATE(`issued_count = issued_count + 1 where … and issued_count < total_quota`).** 싸고 정확하다. 그러나 발급 한도의 규칙이 `CouponCampaign`에서 SQL로 옮겨 가고, 발급 중지와 소진의 차례도 SQL이 정하게 된다. 재고에서 고르지 않은 까닭(ADR 0018)과 같다.
- **유일 제약 위반의 복구 경로.** 위반을 잡고 새 트랜잭션에서 기존 쿠폰을 찾아 200으로 답하는 길이다. 잠금 없이도 중복을 막는다. 그러나 발급 수는 여전히 잠금이나 조건부 UPDATE가 지켜야 하므로 잠금이 어차피 있다. 잠금 아래의 재확인이 그 경합을 없애므로, 복구 경로는 일어나지 않는 일을 위한 코드가 된다.
- **Redis 잠금으로 발급 한도를 지키기.** 과제의 체크리스트는 한도·중복·발급 수와 쿠폰 insert를 DB에서 원자적으로 지키라고 한다. Redis 잠금만으로 지키면 임대가 끝났거나 Redis가 키를 잃었을 때 두 발급이 같은 발급 수를 읽고, MySQL에는 뒤진 쪽을 거절할 것이 없다. Redlock은 독립된 master 여럿이 필요한데 이 저장소의 Redis master는 하나다. DB 잠금을 둔 채 그 앞에 Redis 잠금을 더하는 실험(`feat/coupon-issue-redis-lock`)은 세 단계가 끝난 뒤 정할 선택지로 남긴다.
- **기동 때 쿠폰 캠페인을 심는 코드.** 과제의 "로컬 관리자 준비 코드"는 등록 API로 갈음한다. 기동 seed는 프로필마다 다른 데이터를 만들고, 테스트는 어차피 등록 포트로 준비한다.
- **관리자의 조회와 목록.** 과제가 요구하지 않는다. 발급 수는 DB에서 본다. 수정, 삭제, 재개, 고객의 쿠폰 캠페인 조회도 같은 까닭으로 없다.

## 7. 대가

- 한 쿠폰 캠페인의 발급은 그 행 하나에서 줄을 선다. 2단계가 동시 실행을 처리 자리의 수로 묶으므로 줄은 수용량을 넘지 않는다(9.7절).
- 이른 조회와 재확인이 같은 읽기를 두 번 적는다. 둘 다 명세가 요구하는 자리다.
- 경합 테스트에 쿠폰 저장소의 spy가 더해져 테스트 컨텍스트가 하나 늘었다. 컨텍스트마다 Hikari 풀을 가진다. 스위트가 이미 MySQL 기본 한도 151 가운데 147까지 쓰고 있어, 이 티켓 앞에 테스트 MySQL의 연결 한도를 300으로 올려 두었다(`dd888459`).

## 8. 검증

이음매는 넷이다. provided 포트(`BaseCommittingApplicationServiceTest`), HTTP(`BaseWebApiAdapterTest`), 도메인 단위, 저장소. 경합 장면은 포트를 부른다. 2단계의 처리 자리는 API 앞이 아니라 그 포트 안(`CouponIssueService`)에 있으므로, 이 장면들은 이제 기본 수용량 10의 처리 자리를 지난다. 8개 스레드가 모두 자리를 얻으므로 결과는 바뀌지 않았고, 2단계 동안 `CouponIssueIntegrationTest`는 고치지 않았다(9.8절).

### 8.1 경합 장면

`CouponIssueIntegrationTest`가 실제 MySQL에서 실제로 커밋한다. 과제의 `--tests '*CouponIssueIntegrationTest'`가 이 클래스를 찾는다. 경합 장면은 start gate로 함께 풀고, 결과를 성공·업무 거절·기술 오류로 센 뒤 새 트랜잭션에서 읽는다.

| 장면 | 결과 |
| --- | --- |
| 발급 한도 5인 쿠폰 캠페인에 서로 다른 8명이 8개 스레드로 발급 | 발급 5, `COUPON_CAMPAIGN_EXHAUSTED` 3, 기술 오류 0. 쿠폰 행 5, 발급 수 5 |
| 한 사용자가 두 스레드로 동시에 발급 | 둘 다 같은 쿠폰 식별자, 새 발급은 하나. 쿠폰 행 1, 발급 수 1. 다른 사용자의 목록은 비어 있다 |
| 쿠폰 저장소의 `save`가 flush 뒤 던진다(springmockk spy) | 발급 수의 UPDATE가 나간 뒤 발급이 실패하고 쿠폰 행 0, 발급 수 0. 이어서 spy 없이 발급하면 쿠폰 1, 발급 수 1 |
| 다른 사용자의 발급 트랜잭션이 쿠폰을 넣고 쿠폰 캠페인 잠금을 쥔 채 멈춘 동안 발급 | 3초 뒤 `PessimisticLockingFailureException`(API의 409 `CONCURRENT_REQUEST`). 기다린 쪽의 쿠폰은 없고, 풀린 뒤 쥔 쪽의 쿠폰 1, 발급 수 1 |

되돌림 장면은 통제된 주입이다. 관리 상태 엔티티의 `save`는 SQL을 보내지 않으므로 발급 수의 UPDATE는 원래 커밋의 flush에서 나간다. 그래서 spy는 쿠폰의 INSERT 자리에서 영속성 컨텍스트를 먼저 flush하고 던진다(`CODING_STANDARDS.md`의 중간 실패 테스트). 붙잡은 SQL에 `update coupon_campaign` 하나가 있고 쿠폰의 INSERT는 없다. MySQL이 이미 받은 발급 수를 롤백만이 되돌린다.

한 실행이 남긴 줄(`scripts/scene-lines CouponIssueIntegrationTest`):

```
Quota scene: 8 users, total quota 5: issued 5, exhausted 3, technical errors 0; coupon rows 5, issued count 5
Same-user scene: 2 threads: coupon IDs [1, 1], newly issued [true, false]; coupon rows 1, issued count 1
```

### 8.2 붉은 확인

커밋하지 않은 로컬 수정으로 두 번 돌렸다(2026-11-11). 둘 다 되돌렸다.

- **쿠폰 캠페인을 `FOR UPDATE` 없이 읽는다**(`findForUpdateById`의 `@Lock`을 지운다). 8명 장면이 붉다. 두 번 돌려 발급 5·거절 0·기술 오류 3, 발급 4·거절 0·기술 오류 4였다. 기술 오류는 모두 MySQL의 교착(1213)이었고, 같은 사용자 장면도 두 번 모두 교착 하나로 붉었다. 잠그지 않은 트랜잭션들은 같은 발급 수를 읽는다. 쿠폰의 INSERT는 외래 키 검사로 쿠폰 캠페인 행에 공유 잠금을 걸고, 커밋 때의 발급 수 UPDATE는 서로의 공유 잠금을 기다려 교착한다. 이 실행에서는 외래 키가 덮어쓰기를 교착으로 바꿨지만, 그것은 우연한 방어다. 한 트랜잭션이 다른 쪽의 INSERT 전에 커밋까지 끝나면 덮어쓰기가 그대로 남는다. `FOR UPDATE`를 세는 장면도 함께 붉었다. 잠금 대기 장면은 둘째 실행에서 푸르게 남았다. 쥔 쪽이 쿠폰을 넣은 뒤 멈추므로 외래 키의 공유 잠금을 쥐고 있고, 잠그지 않은 쪽도 커밋 때의 발급 수 UPDATE에서 그 잠금을 기다리다 같은 예외로 끝나기 때문이다. 그 장면이 보이는 것은 잠금 실패의 답이지 `FOR UPDATE`가 아니다. (첫 실행에서는 잠금 대기 장면의 쥔 쪽이 발급 트랜잭션이 아니라 `findForUpdate`만 부르는 트랜잭션이어서 그 장면도 붉었다. 검토가 그 장면을 발급 단계의 spy로 바꿨다.)
- **잠금 아래의 재확인을 지운다.** 같은 사용자 장면이 붉다. 성공 1, 기술 오류 1이고, 그 오류는 `Duplicate entry '1-1' for key 'coupon.UK_COUPON_ON_CAMPAIGN_ID_AND_USER_ID'`다. 유일 제약이 뒤의 방어로 일했다. 다른 장면은 그대로 통과했다.

### 8.3 끝의 확인

```
scripts/final-check --app commerce-api
```

- 끝의 확인은 2026-11-12 `6bbe944e`의 깨끗한 트리에서 `scripts/final-check --app commerce-api`로 돌렸다(01:27:40Z 시작). 셸에 `BOOTSTRAP_SERVERS`는 없었다. ktlint가 통과했다. commerce-api는 105개 클래스, 1143개 테스트, 실패 0, 오류 0, 건너뜀 0이다(4 min 58 s). 티켓 223이 끝난 105개 클래스, 1139개 테스트에서 `CouponIssueIntegrationTest`의 장면 넷이 더해졌다. 이 실행의 보고서에서 읽은 8.1절의 두 줄도 위와 같다. 같은 사용자 장면의 `newly issued` 순서는 어느 스레드가 먼저 잠금을 얻느냐에 따라 실행마다 바뀐다.
- 앞의 확인은 2026-11-11 `8e251c66`에서 같은 수로 통과했다(5 min 16 s). 그 뒤 `de9a3bdb`가 발급 중지 여부의 이름을 `suspended`로 바꾸고 경로 변수의 이름을 밝혀서, 코드가 바뀐 이 커밋 위에서 다시 돌렸다.
- `8e251c66`의 바로 앞 실행은 1143개 가운데 하나가 실패했다. `PointApiTest`의 첫 테스트가 `@BeforeEach`에서 Redis를 비우다 `RedisConnectionFailureException`(Lettuce의 연결 한도 100 ms 안에 `Cannot determine topology`)으로 끝났고, 그 클래스의 나머지 11개는 통과했다. 쿠폰은 Redis를 쓰지 않는다. 고치지 않고 다시 돌린 `8e251c66`의 실행이 통과했다.

## 9. 2단계: 처리 자리

1단계로 DB가 발급 한도와 한 사용자 한 쿠폰을 지킨다. 그러나 동시에 실행되는 발급의 수는 아무것도 묶지 않았다. 발급 트랜잭션은 첫 문장인 `FOR UPDATE`부터 커밋까지 DB 연결 하나를 쥐고, 같은 쿠폰 캠페인의 발급은 그 행에서 하나씩 지나가므로 나머지는 연결을 쥔 채 최대 3초를 기다린다. 풀은 40이고 주문과 결제가 함께 쓴다. 넘친 요청은 3초 뒤 409 `CONCURRENT_REQUEST`를 받았다. 과제(8주차 실습 3절과 5절)는 빈 처리 자리가 없으면 바로 거절하라고 한다.

2단계는 Redis가 정상일 때의 길만 만든다. Redis 장애의 경계는 3단계의 몫이다(10절).

### 9.1 2단계가 만든 것

| 티켓 | 만든 것 |
| --- | --- |
| [227](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/227) | required 포트 `CouponIssueSlots`, Redis 어댑터 `RedisCouponIssueSlots`와 자리를 얻는 Lua 스크립트, 수용량 `CouponIssueProperties`. `CONTEXT.md`의 네 용어는 그 앞의 커밋 `5a304888`. |
| [228](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/228) | `CouponIssueService`가 자리를 얻고 놓는다. `CouponIssueCapacityExceededException`과 429 `COUPON_ISSUE_CAPACITY_EXCEEDED`. |
| [229](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/229) | 실행 중인 발급으로 수용량을 채우는 장면(`CouponIssueSlotsIntegrationTest`, 9.8절)과 이 절. 운영 코드는 바뀌지 않았다. |

티켓마다 구현 중에 정한 것은 [글감](../research/week8-coupon-admission-writing-material.md)에 있다.

### 9.2 발급의 차례

발급 서비스(`CouponIssueService`, `Propagation.NEVER`)의 차례는 네 걸음이 된다.

1. 트랜잭션 밖에서 요청자의 쿠폰을 읽는다. 있으면 200이고 처리 자리를 얻지 않는다. 그래서 이미 받은 사용자의 재요청은 처리 자리가 모두 찼어도 쿠폰을 받는다. (1단계 그대로)
2. 자리를 얻는다(`CouponIssueSlots.acquire()`). 빈 처리 자리가 없으면 `CouponIssueCapacityExceededException`으로 거절하고 발급 트랜잭션을 시작하지 않는다. 기다리지도 다시 시도하지도 않는다.
3. 발급 트랜잭션을 돌린다(`CouponIssueSteps.issueUnderLock`). (1단계 그대로)
4. `finally`에서 자리를 놓는다(`release(token)`). 서비스가 `NEVER`이므로 3이 돌아오거나 던진 때에는 커밋이나 롤백이 이미 끝났다. 그래서 빈 처리 자리는 언제나 DB 일이 끝났다는 뜻이다. 발급, 업무 거절(소진, 발급 중지, 없음), 잠금 대기 초과, 예상하지 못한 예외가 모두 같은 `finally`를 지난다.

소진, 발급 중지, 중복은 여전히 발급 트랜잭션만 정한다. 처리 자리는 발급 한도를 모르고, 자리를 놓는 일은 발급 수도 쿠폰도 바꾸지 않는다. 동시에 온 같은 사용자의 두 요청은 저마다 자리를 얻을 수 있고, DB가 쿠폰 하나로 끝낸다(3.2절).

- 포트 `CouponIssueSlots`는 `application/coupon/required`에 있다. `acquire()`는 토큰을, 빈 처리 자리가 없으면 `null`을 돌려준다. `null`은 평범한 결과이고, 예외는 Redis의 문제만 뜻하게 둔다. 3단계가 그 예외를 옮긴다.
- `CouponIssueCapacityExceededException`은 `application/coupon`에 있다. 소진과 발급 중지는 쿠폰 캠페인의 사실이라 도메인에 있지만, 처리 자리가 찼다는 것은 서버의 지금 사정이라 어떤 엔티티도 알지 못한다.

### 9.3 Redis Set과 Lua

- 처리 자리는 Redis Set 하나다. 키는 어댑터의 상수 `coupon-issue:slots`이고 설정 값이 아니다. 인스턴스마다 이름이 다르면 저마다 수용량까지 센다. 캐시 키도 아니다. 키에도 원소에도 TTL이 없다.
- 원소는 어댑터가 `acquire()`마다 만드는 UUID 토큰이다. 요청, 사용자, 쿠폰 캠페인과 무관하므로 두 실행이 한 자리를 나누지 않고, 클라이언트가 보내는 무엇도 자리를 가리키지 못한다.
- 자리를 얻는 일은 `resources/redis/coupon-issue-slot-acquire.lua` 하나가 한다. 과제 3절의 "Lua 수용 로직 예시" 그대로다. 토큰의 존재 확인(`SISMEMBER`), 크기 확인(`SCARD >= capacity`), 추가(`SADD`)를 한 번에 하므로 두 요청이 마지막 자리를 함께 얻지 못한다. `KEYS[1]`은 키, `ARGV[1]`은 수용량, `ARGV[2]`는 토큰이고, 1은 얻음, 0은 빈 자리 없음이다. 두 번의 왕복으로 나누면 8개 스레드가 모두 크기 0을 읽고 모두 들어왔다(티켓 227의 변이 확인, 세 번에 세 번).
- 자리를 놓는 일은 스크립트 없이 `SREM key token`이다. 그것만으로 원자적이고, 두 번 놓거나 모르는 토큰을 놓아도 다른 원소를 건드리지 않는다.
- 두 명령 모두 `redisTemplateMaster`로 보낸다. 판단이 replica의 낡은 읽기에 기대지 않는다. 명령 한도는 `redis.yml`의 100 ms 그대로다.

### 9.4 수용량

- `coupon-issue.slot-capacity`(`CouponIssueProperties`)이고, 서버 설정에서만 온다. 요청이 넓히지 못한다. 1보다 작으면 기동이 실패한다. 모든 요청을 거절하는 서버가 뜨는 것보다 낫다.
- `application.yml`의 값은 모든 프로필에서 **10**이다. 연결 풀 40의 4분의 1이고, 1단계의 8개 스레드 장면보다 크다. **측정한 값이 아니라 출발점이다.** 부하를 걸어 고른 수가 아니고, 연결 풀의 몫과 기존 장면을 받는 것만 따졌다.

### 9.5 API와 오류

| 경우 | status | code |
| --- | --- | --- |
| 빈 처리 자리 없음 | 429 | `COUPON_ISSUE_CAPACITY_EXCEEDED` |

- `ApiControllerAdvice`가 답한다. 처리 자리가 찬 것은 부하이지 결함이 아니고 몰릴 때 많이 나오므로, 그 로그에는 스택 트레이스를 싣지 않는다.
- `Retry-After` 헤더는 없다(9.6절).
- 다른 답(201, 200, 404, 409 셋)은 바뀌지 않는다. `GET /api/v1/coupons`와 이른 조회는 처리 자리를 쓰지 않는다.

### 9.6 고르지 않은 것

- **쿠폰 캠페인마다의 키.** 과제는 "쿠폰 신규 발급 경로 전체의 활성 실행 최대 C개"라고 한다. 캠페인마다 키를 두면 캠페인 수에 따라 동시 실행의 합이 늘어 연결 풀의 몫을 묶지 못한다.
- **웹 인터셉터.** 과제의 차례(기존 쿠폰 조회 → 자리 얻기)는 DB 읽기 뒤에 자리를 얻는다. 인터셉터는 이른 조회를 한 번 더 해야 하고, 포트를 부르는 경합 장면은 처리 자리를 지나지 않게 된다.
- **서버에서 기다리기.** 빈 자리가 날 때까지 기다리면 그동안 요청 스레드를 쥐고, 1단계의 문제(연결을 쥔 채 기다림)를 스레드로 옮길 뿐이다. 과제도 바로 거절하라고 한다. 대기열과 대기실도 같은 까닭으로 범위 밖이다.
- **`Retry-After`.** 처리 자리가 언제 비는지 아는 것이 없어 값이 추측이 된다.
- **TTL.** 키나 원소가 만료되면 죽은 실행의 토큰은 사라지지만, 오래 걸리는 살아 있는 실행의 토큰도 사라져 수용량을 넘겨 받아들인다. 만료는 죽은 실행과 느린 실행을 가리지 못한다. 남은 토큰의 정리는 3단계의 재개 규칙이 맡는다.
- **새 ADR.** 키의 배치는 상수 하나이고, 요청이 끝난 뒤 남는 데이터가 없어 되돌리기 쉽다.

### 9.7 대가

- 한 쿠폰 캠페인에 발급이 몰리면 처리 자리를 모두 채운다. 하나는 실행 중이고 나머지는 행 잠금을 기다린다. 그동안 다른 쿠폰 캠페인의 요청은 DB가 한가해도 429를 받는다.
- 처리 자리가 묶는 것은 일하는 쪽이 아니라 연결을 쥐고 기다리는 쪽의 수다. 한 쿠폰 캠페인 안에서는 행 잠금이 실행을 이미 하나로 묶는다.
- 처리 자리는 분산 세마포어가 아니다. 발급 한도를 모르고, 줄을 세우지 않고, TTL이 없고, Redis 장애를 아직 다루지 않는다.
- 2단계와 3단계 사이에는 Redis가 내려가면 발급 API가 500으로 답한다. 자리를 얻는 호출이 던지면 발급 트랜잭션은 시작되지 않는다. 커밋 뒤 자리를 놓는 호출이 던져도 500이지만, 쿠폰은 이미 있고 다시 보낸 요청은 이른 조회로 200을 받는다. 발급 트랜잭션이 던진 뒤 자리를 놓는 호출까지 던지면 뒤의 예외가 업무 예외를 가린다.
- 자리를 얻고 놓기 전에 JVM이 죽으면 그 토큰은 Set에 남아 자리 하나를 계속 차지한다.
- 1단계의 경합 장면이 이제 Redis와, 8보다 큰 수용량에 기댄다.
- 수용량 3의 장면이 자기 Spring 컨텍스트를 하나 더 띄운다(9.8절).

### 9.8 검증

이음매는 셋이다.

- **Redis 어댑터**(`CouponIssueSlotsTest`): 실제 Redis에서 수용량까지 얻고 그다음은 `null`, 하나를 놓으면 다시 얻음, 두 번 놓거나 모르는 토큰을 놓아도 다른 토큰이 남음, 토큰이 호출마다 다름, 8개 스레드가 수용량 3에 함께 얻으면 토큰 정확히 3. 수용량 1 미만의 기동 실패는 `CouponIssuePropertiesTest`.
- **provided 포트**: 차례대로 부르는 장면은 `CouponIssueAdmissionTest`(기본 컨텍스트, 수용량 10). 자리는 기반 클래스가 포트로 `null`이 나올 때까지 얻어 채운다. 빈 자리가 없으면 거절하고 `FOR UPDATE`가 나가지 않는다, 이미 받은 사용자는 그래도 쿠폰을 받는다, 자리 하나를 놓으면 다시 보낸 요청이 발급된다, 소진·발급 중지·없음으로 끝나도 Set이 비어 있다.
- **HTTP**(`CouponApiTest`): 429와 code, 이미 받은 사용자의 200, 처리 자리가 찼을 때의 목록.

과제 5절의 장면은 `CouponIssueSlotsIntegrationTest`가 실제 Redis와 실제 MySQL에서 수용량 3의 자기 컨텍스트로 돌린다(`@TestPropertySource`). 멈춤은 통제된 주입이다. springmockk spy가 `CouponIssueSteps.issueUnderLock`의 시작에서 세 사용자의 발급을 저마다의 latch로 붙잡는다. 자리를 얻은 뒤이고 SQL을 보내기 전이므로, 멈춘 발급은 자리를 쥐되 쿠폰 캠페인의 잠금은 쥐지 않는다. 그래서 세 발급이 같은 쿠폰 캠페인에서 서로를 기다리지 않고 함께 멈춘다. 1단계의 잠금 대기 장면은 쿠폰을 넣은 뒤 멈추므로 다른 발급이 그 잠금에서 3초 뒤 실패하는데, 이 장면에서는 그러면 안 된다.

| 장면 | 결과 |
| --- | --- |
| 세 사용자의 발급이 `issueUnderLock` 안에서 멈춘다 | Set의 토큰 3 |
| 넷째 사용자가 발급 | `CouponIssueCapacityExceededException`. 넷째의 쿠폰 없음, 발급 수 0 |
| 멈춘 발급 하나를 풀어 끝낸 뒤 넷째가 다시 발급 | 둘 다 새 발급 |
| 나머지 둘을 푼다 | 모두 새 발급. Set 비어 있음, 쿠폰 행 4, 발급 수 4 |

한 실행이 남긴 줄(`scripts/scene-lines CouponIssueSlotsIntegrationTest`):

```
Capacity scene: capacity 3: 3 issues held in their slots, the next user refused with CouponIssueCapacityExceededException, issued on retry after one finished; slots held at the end 0, coupon rows 4, issued count 4
```

1단계의 `CouponIssueIntegrationTest`는 2단계 동안 diff가 없고, 기본 수용량 10의 처리 자리를 지나 그대로 통과한다(8절).

**붉은 확인.** 커밋하지 않은 로컬 수정으로 `CouponIssueService`의 `release`를 지우고 돌렸다(2026-11-12). 되돌렸다. 넷째의 재요청이 `CouponIssueCapacityExceededException`으로 붉다. 끝난 발급의 토큰이 Set에 남아 자리 셋이 계속 찼기 때문이다. 그 줄에서 테스트가 멈추므로, 재요청의 실패를 기록하고 이어 가도록 장면도 함께 고쳐 한 번 더 돌렸다. 재요청은 거절되었고, 끝에 Set에는 토큰 3이 남았으며(`Expecting empty but was: [...]` 세 UUID), 쿠폰 행은 3이었다. 티켓 228의 변이 확인(같은 수정에 그 티켓의 장면 6개가 붉음)은 차례대로 부르는 장면의 몫이고, 이 장면은 실행 중인 발급이 자리를 쥐는 동안에도 같은 것을 보인다.

### 9.9 끝의 확인

```
scripts/final-check --app commerce-api
```

## 10. 3단계로 넘기는 것

3단계(`feat/coupon-admission-failure`)는 Redis 장애의 경계를 맡는다. 2단계가 남긴 것은 이렇다.

- **Redis 예외는 번역되지 않는다.** `acquire()`나 `release()`가 던지는 Spring `DataAccessException`은 서비스도 어댑터도 잡지 않고, 발급 API는 500으로 답한다. `acquire()`가 먼저이므로 그것이 던지면 발급 트랜잭션은 시작되지 않는다. `null`(빈 자리 없음)과 예외(Redis의 문제)를 포트에서 이미 나눴으므로, 503으로 옮기는 일은 한 곳(`CouponIssueService` 또는 어댑터)에 들어간다. 커밋 뒤의 `release()`가 던지는 경우와, 발급 트랜잭션의 예외를 `release()`의 예외가 가리는 경우(9.7절)도 그 규칙이 정한다.
- **죽은 JVM은 토큰을 남긴다.** 자리를 얻고 놓기 전에 프로세스가 죽으면 그 토큰은 Set에 남고, 아무것도 지우지 않는다. TTL은 고르지 않았다(9.6절). 남은 토큰의 정리는 3단계의 재개 규칙이 정한다.
- **수용을 닫는 것이 없다.** 지금은 Redis가 답하면 받아들이고 답하지 않으면 500이다. 수용 닫힘과 명시적 재개, 불확실한 응답의 처리, 실제 Redis 중단 테스트는 모두 3단계의 몫이다.
- 소진, 발급 중지, 중복은 여전히 발급 트랜잭션만 정한다. 3단계의 어떤 규칙도 처리 자리로 발급 한도를 대신하지 않는다. 자리를 놓는 일은 발급 수를 줄이지 않는다.
- 자기 쿠폰 목록과 이른 조회는 DB만 쓴다. Redis가 내려가도 이미 받은 사용자는 200을 받는다.
