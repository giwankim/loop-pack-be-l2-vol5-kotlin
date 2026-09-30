# Coding Standards

이 저장소의 코드가 지키는 규칙이다. `/code-review`는 변경을 이 문서에 비추어 본다. 결정의 까닭은 각 규칙이 가리키는 ADR과 설계 문서에 있다.

## 테스트

### 이름과 구조

- 테스트 메서드 이름은 백틱으로 감싼 영어 문장이다. 예: `` fun `registering a blank name returns 400 and saves nothing`() ``.
- 메서드 이름이 곧 표시 이름이라 `@DisplayName`을 붙이지 않는다.
- 테스트 클래스는 `@Nested` 없이 평평하다.
- `assertThrows`가 돌려준 예외는 `exception`이라는 변수에 담는다.
