---
status: accepted
date: 2026-10-05
---

# HTTP 테스트는 Kotlin MockMvc DSL 대신 MockMvcTester로 단언한다

HTTP 테스트 11개(`*MockMvcTest`, 3,461줄)는 MockMvc의 Kotlin DSL(`mockMvc.get(…).andExpect { … }`)로 요청하고 Spring의 `ResultMatcher`와 Hamcrest(`containsString` 20곳, `notNullValue` 4곳)로 단언한다. 나머지 테스트는 모두 AssertJ로 단언한다. Boot 4로 올린 김에 HTTP 테스트를 Spring의 AssertJ 테스트 API인 `MockMvcTester`로 옮긴다. 테스트 전체가 단언 라이브러리 하나를 쓰고, 응답에서 값을 꺼낼 때도 같은 API(`extractingPath`)를 쓴다. 지금은 응답의 ID를 다음 요청에 쓰려고 `andReturn()`과 `JsonPath.read<Number>(…).toLong()`을 거친다. `MockMvcTester`는 Framework 6.2에 들어왔으므로 Boot 4의 새 API는 아니다.

- 주입받는 필드의 이름은 `mvc`다. 클래스 이름 `*MockMvcTest`는 그대로 둔다. 설계 문서가 클래스 이름으로 테스트를 가리키고, 밑에서 도는 것은 여전히 MockMvc다.
  - 2026-10-08 클래스 이름을 `*ApiTest`로 바꿨다(`BrandApiMockMvcTest` → `BrandApiTest`). [ADR 0012](./0012-tests-inherit-setup-from-abstract-base-classes.md) 뒤로는 상속한 기반 클래스(`BaseWebApiAdapterTest`)가 테스트의 종류를 말하므로 이름이 도는 방식을 적지 않아도 된다. splearn도 같은 테스트를 `MemberApiTest`라 부르고, 기술 이름은 다른 방식의 테스트(`MemberApiWebMvcTest`)에만 붙인다. 설계 문서의 클래스 이름은 함께 고쳤다.
- 응답은 JSON 경로마다 단언한다. 옛 `jsonPath` 단언 한 줄이 새 단언 한 줄이 된다: `val body = assertThat(result).hasStatusOk().bodyJson()` 뒤에 `body.extractingPath(…)`, 없는 필드는 `doesNotHavePath`, 부분 문자열은 `asString().contains`를 쓴다.
- JSONPath는 작은 정수를 `Integer`로 읽어서 Kotlin `Long`과 같지 않다. 옛 DSL의 `value(…)`는 타입을 맞춰 줬지만 `extractingPath(…).isEqualTo(…)`는 맞추지 않는다. `Long`과 견주는 단언은 `com.loopers.support`의 `isEqualToLong`을 쓴다. 확장 함수의 이름이 `isEqualTo`면 멤버 `isEqualTo(Object)`가 먼저 골라져 확장이 불리지 않으므로 이름을 따로 둔다.
- 요청 빌더는 `assertThat`에 넘기거나 `exchange()`를 부를 때마다 실행된다(`MockMvcTester.java`의 `assertThat()`이 `exchange()`를 부른다). 옛 DSL의 `mockMvc.post(…)`는 부르는 즉시 실행됐다. 단언에 한 번 넘기는 요청은 빌더를 그대로 넘기고, 두 번 이상 단언하거나 단언하지 않는 요청은 `exchange()`로 한 번 실행한다.
- 요청 도우미(`postBrand`, `like`, `charge` 등)는 요청을 실행하고 그 결과(`MvcTestResult`)를 돌려준다. 이름이 동작인 도우미는 부르기만 해도 실행돼야 한다. `MvcTestResult`는 여러 번 단언해도 요청을 다시 보내지 않는다.
- 두 응답의 부분 트리를 견주는 `JsonNode` 도우미(주문 테스트 세 곳)는 그대로 두고 `MvcTestResult`에서 읽게만 바꾼다.
- 옮긴 테스트가 약해지지 않았는지는 두 가지로 확인한다. 리뷰에서 옛 단언과 새 단언이 하나씩 짝지어지는지 본다. 그리고 파일마다 단언 종류(상태, 값, 없는 필드, 부분 문자열)별로 운영 코드를 잠깐 망가뜨려 옮긴 테스트가 실패하는지 본다.

## 고르지 않은 것

- **Kotlin DSL을 그대로 쓴다.** 더 짧고, Framework 7.0.9에서도 폐기 예정이 아니다. 하지만 HTTP 테스트만 다른 단언 라이브러리를 계속 쓴다.
- **`RestTestClient`(Framework 7)를 쓴다.** Framework 7에 새로 들어온 테스트 클라이언트이고 Kotlin 확장도 있다. 하지만 MockMvc에 묶어도 요청마다 `RequestPostProcessor`를 걸 수 없다. `MockMvcClientHttpRequestFactory`가 메서드·URI·헤더·본문·쿠키만 넘기므로 관리자 테스트의 `with(user().roles("ADMIN"))`·`with(csrf())`를 옮길 수 없다. 쓰려면 `@AutoConfigureRestTestClient`도 따로 붙여야 한다.
- **응답 전체를 손으로 적은 JSON과 견준다.** 요청 본문을 손으로 적는 규칙과 짝이 맞고, strict 비교는 있으면 안 되는 필드까지 잡는다. 하지만 관리자 응답의 시각처럼 실행할 때 정해지는 값은 글자로 적을 수 없어 lenient 비교와 경로 단언을 섞어야 한다. 필드 하나를 보는 테스트도 문서 전체를 적어야 한다.
- **응답을 DTO로 바꿔(`convertTo`) 객체로 단언한다.** DTO의 필드 이름을 바꾸면 테스트가 보는 이름도 함께 바뀐다. HTTP 계약이 바뀌어도 테스트는 통과한다.

## 대가

- Kotlin DSL보다 길다. Framework 7.0.9에는 `MockMvcTester`용 Kotlin DSL이 없다.
- Java API의 가정이 Kotlin에서 함정이 된다. `Integer`와 `Long`의 불일치는 테스트가 실패해서 드러나지만 도우미를 거쳐야 한다. 게으른 빌더는 단언하지 않은 요청을 말없이 보내지 않는다. 둘 다 위 규칙이 막고, `/code-review`가 `CODING_STANDARDS.md`에 비추어 지킨다.
- 11개 파일을 옮기는 동안 두 스타일이 섞여 있다. 브랜드 테스트가 먼저 옮겨 규칙을 세우고, 나머지 조각이 그 뒤를 따른다.
