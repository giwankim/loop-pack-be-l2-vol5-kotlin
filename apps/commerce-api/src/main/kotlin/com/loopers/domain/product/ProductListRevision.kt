package com.loopers.domain.product

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id

/**
 * 고객 상품 목록의 revision. 카탈로그 전체에 행 하나이고, 목록을 바꾸는 쓰기가 자기 트랜잭션의 마지막 SQL로 올린다.
 * 고객 목록의 캐시 키가 이 값을 품으므로 올리면 옛 키는 아무도 읽지 않게 된다(ADR 0020).
 *
 * [com.loopers.domain.BaseEntity]를 상속하지 않는 유일한 엔티티다(ADR 0016의 예외). 생성되는 식별자, 생성·수정·삭제 시각,
 * 논리 삭제가 카운터 하나에는 맞지 않는다. 식별자는 [ID] 하나로 정해져 있고, 행은 지우지 않는다.
 * 엔티티로 두는 것은 Hibernate가 local·test에서 표를 만들고 테스트의 `DatabaseCleanUp`이 다른 표와 함께 비우게 하려는 것이다.
 *
 * Kotlin 코드는 이 엔티티를 만들거나 읽어 고치지 않는다. 읽기와 올리기는 `ProductListRevisionRepository`의 문장 하나씩이다.
 */
@Entity
class ProductListRevision(
    @Id
    val id: Long,

    @Column(nullable = false)
    val revision: Long,
) {
    companion object {
        /** 하나뿐인 행의 식별자. */
        const val ID = 1L
    }
}
