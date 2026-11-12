# 쿠폰 설계 — 발급 한도와 한 사용자 한 쿠폰

8주차 과제의 설계 문서다. 용어는 [`CONTEXT.md`](../../CONTEXT.md)의 "쿠폰" 절과 행위의 "발급하다", "발급을 중지하다"를 따른다. 결정의 출처는 [명세 219](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/219)이고, 이 단계는 새 ADR을 두지 않는다. 잠금은 [ADR 0018](../adr/0018-pessimistic-row-locks-in-one-global-order.md)의 방식을 따르고 그 ADR에 날짜 메모를 달았다.

상태: 2026-11-11 1단계(`feat/coupon-issue`)를 인터뷰하고, 같은 날 그 다섯 티켓을 구현한 뒤 이 문서를 썼다. 처리 자리(2단계)와 장애 경계(3단계)는 저마다의 인터뷰 뒤에 절을 더한다.

## 1. 근거와 범위

- 과제: 8주차 실습 "Redis로 작업 수용량 통제하기"의 1·2절, 그리고 6절의 앞 여섯 장면.
- 이 단계는 Redis 없이도 옳아야 하는 것만 다룬다. 발급 한도, 같은 사용자의 두 번째 요청, 발급 수와 쿠폰이 함께 남는 것이다.
- 7주차의 결과에서 시작한다. 주문, 결제, 아웃박스, 구매 활동은 바뀌지 않는다. commerce-streamer는 건드리지 않는다.

### 1.1 세 단계

| 단계 | 하는 것 | 브랜치 | 상태 |
| --- | --- | --- | --- |
| 1 | 쿠폰 캠페인과 쿠폰. 등록, 발급, 발급 중지, 자기 쿠폰 목록. 발급 한도와 중복을 DB가 지킨다. | `feat/coupon-issue` | 인터뷰 끝남(2026-11-11). 구현 끝남(2026-11-11, 1.2절). [명세 219](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/219) |
| 2 | 발급 API 앞의 처리 자리(Redis Set, Lua, 429). | `feat/coupon-admission` | 인터뷰 전 |
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

## 6. 고르지 않은 것

- **조건부 UPDATE(`issued_count = issued_count + 1 where … and issued_count < total_quota`).** 싸고 정확하다. 그러나 발급 한도의 규칙이 `CouponCampaign`에서 SQL로 옮겨 가고, 발급 중지와 소진의 차례도 SQL이 정하게 된다. 재고에서 고르지 않은 까닭(ADR 0018)과 같다.
- **유일 제약 위반의 복구 경로.** 위반을 잡고 새 트랜잭션에서 기존 쿠폰을 찾아 200으로 답하는 길이다. 잠금 없이도 중복을 막는다. 그러나 발급 수는 여전히 잠금이나 조건부 UPDATE가 지켜야 하므로 잠금이 어차피 있다. 잠금 아래의 재확인이 그 경합을 없애므로, 복구 경로는 일어나지 않는 일을 위한 코드가 된다.
- **Redis 잠금으로 발급 한도를 지키기.** 과제의 체크리스트는 한도·중복·발급 수와 쿠폰 insert를 DB에서 원자적으로 지키라고 한다. Redis 잠금만으로 지키면 임대가 끝났거나 Redis가 키를 잃었을 때 두 발급이 같은 발급 수를 읽고, MySQL에는 뒤진 쪽을 거절할 것이 없다. Redlock은 독립된 master 여럿이 필요한데 이 저장소의 Redis master는 하나다. DB 잠금을 둔 채 그 앞에 Redis 잠금을 더하는 실험(`feat/coupon-issue-redis-lock`)은 세 단계가 끝난 뒤 정할 선택지로 남긴다.
- **기동 때 쿠폰 캠페인을 심는 코드.** 과제의 "로컬 관리자 준비 코드"는 등록 API로 갈음한다. 기동 seed는 프로필마다 다른 데이터를 만들고, 테스트는 어차피 등록 포트로 준비한다.
- **관리자의 조회와 목록.** 과제가 요구하지 않는다. 발급 수는 DB에서 본다. 수정, 삭제, 재개, 고객의 쿠폰 캠페인 조회도 같은 까닭으로 없다.

## 7. 대가

- 한 쿠폰 캠페인의 발급은 그 행 하나에서 줄을 선다. 다음 단계가 동시 실행을 처리 자리의 수로 묶으므로 줄은 짧다.
- 이른 조회와 재확인이 같은 읽기를 두 번 적는다. 둘 다 명세가 요구하는 자리다.
- 경합 테스트에 쿠폰 저장소의 spy가 더해져 테스트 컨텍스트가 하나 늘었다. 컨텍스트마다 Hikari 풀을 가진다. 스위트가 이미 MySQL 기본 한도 151 가운데 147까지 쓰고 있어, 이 티켓 앞에 테스트 MySQL의 연결 한도를 300으로 올려 두었다(`dd888459`).

## 8. 검증

이음매는 넷이다. provided 포트(`BaseCommittingApplicationServiceTest`), HTTP(`BaseWebApiAdapterTest`), 도메인 단위, 저장소. 경합 장면은 포트를 부르므로, 다음 단계가 API 앞에 처리 자리를 놓아도 그대로 유효하다.

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
Same-user scene: 2 threads: coupon IDs [1, 1], newly issued [false, true]; coupon rows 1, issued count 1
```

### 8.2 붉은 확인

커밋하지 않은 로컬 수정으로 두 번 돌렸다(2026-11-11). 둘 다 되돌렸다.

- **쿠폰 캠페인을 `FOR UPDATE` 없이 읽는다**(`findForUpdateById`의 `@Lock`을 지운다). 8명 장면이 붉다. 두 번 돌려 발급 5·거절 0·기술 오류 3, 발급 4·거절 0·기술 오류 4였다. 기술 오류는 모두 MySQL의 교착(1213)이었고, 같은 사용자 장면도 두 번 모두 교착 하나로 붉었다. 잠그지 않은 트랜잭션들은 같은 발급 수를 읽는다. 쿠폰의 INSERT는 외래 키 검사로 쿠폰 캠페인 행에 공유 잠금을 걸고, 커밋 때의 발급 수 UPDATE는 서로의 공유 잠금을 기다려 교착한다. 이 실행에서는 외래 키가 덮어쓰기를 교착으로 바꿨지만, 그것은 우연한 방어다. 한 트랜잭션이 다른 쪽의 INSERT 전에 커밋까지 끝나면 덮어쓰기가 그대로 남는다. `FOR UPDATE`를 세는 장면도 함께 붉었다. 잠금 대기 장면은 둘째 실행에서 푸르게 남았다. 쥔 쪽이 쿠폰을 넣은 뒤 멈추므로 외래 키의 공유 잠금을 쥐고 있고, 잠그지 않은 쪽도 커밋 때의 발급 수 UPDATE에서 그 잠금을 기다리다 같은 예외로 끝나기 때문이다. 그 장면이 보이는 것은 잠금 실패의 답이지 `FOR UPDATE`가 아니다. (첫 실행에서는 잠금 대기 장면의 쥔 쪽이 발급 트랜잭션이 아니라 `findForUpdate`만 부르는 트랜잭션이어서 그 장면도 붉었다. 검토가 그 장면을 발급 단계의 spy로 바꿨다.)
- **잠금 아래의 재확인을 지운다.** 같은 사용자 장면이 붉다. 성공 1, 기술 오류 1이고, 그 오류는 `Duplicate entry '1-1' for key 'coupon.UK_COUPON_ON_CAMPAIGN_ID_AND_USER_ID'`다. 유일 제약이 뒤의 방어로 일했다. 다른 장면은 그대로 통과했다.

### 8.3 끝의 확인

```
scripts/final-check --app commerce-api
```

- 끝의 확인은 2026-11-11 `8e251c66`의 깨끗한 트리에서 `scripts/final-check --app commerce-api`로 돌렸다(08:45:33Z 시작). 셸에 `BOOTSTRAP_SERVERS`는 없었다. ktlint가 통과했다. commerce-api는 105개 클래스, 1143개 테스트, 실패 0, 오류 0, 건너뜀 0이다(5 min 16 s). 티켓 223이 끝난 105개 클래스, 1139개 테스트에서 `CouponIssueIntegrationTest`의 장면 넷이 더해졌다. `8e251c66` 뒤로는 문서만 바뀌었다. 이 실행의 보고서에서 읽은 8.1절의 두 줄도 위와 같다.
- 같은 커밋의 바로 앞 실행은 1143개 가운데 하나가 실패했다. `PointApiTest`의 첫 테스트가 `@BeforeEach`에서 Redis를 비우다 `RedisConnectionFailureException`(Lettuce의 연결 한도 100 ms 안에 `Cannot determine topology`)으로 끝났고, 그 클래스의 나머지 11개는 통과했다. 쿠폰은 Redis를 쓰지 않는다. 고치지 않고 다시 돌린 위 실행이 통과했다.

## 9. 다음 단계로 넘기는 것

- 다음 단계는 이른 조회와 발급 트랜잭션 사이에서 처리 자리를 얻고, 트랜잭션이 끝난 뒤 놓는다(3.1절). 소진, 발급 중지, 중복은 발급 트랜잭션만 정하므로 처리 자리는 발급 한도를 알 필요가 없다.
- 이 단계의 어떤 것도 발급 수를 줄이지 않는다. 처리 자리를 놓는 일이 쿠폰을 돌려주는 일로 읽히지 않게 한다.
- 자기 쿠폰 목록과 이른 조회는 DB만 쓴다. Redis가 내려가도 그대로 일한다.
- `CONTEXT.md`는 release를 "예약을 돌려주다"에 쓰고 반환하다를 피할 말로 둔다. 처리 자리를 놓는 말은 `feat/coupon-admission`의 인터뷰가 정한다.
