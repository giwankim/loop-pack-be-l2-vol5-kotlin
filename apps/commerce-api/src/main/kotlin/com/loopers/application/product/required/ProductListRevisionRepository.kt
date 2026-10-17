package com.loopers.application.product.required

import com.loopers.domain.product.ProductListRevision
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository

/**
 * 상품 목록 revision의 저장소. Spring Data가 구현을 만들어 MySQL의 오류가 Spring의 예외로 번역된다.
 * 잠금 대기 초과와 교착은 `PessimisticLockingFailureException`으로 와서 409 `CONCURRENT_REQUEST`가 된다(ADR 0018 규칙 8).
 */
interface ProductListRevisionRepository : Repository<ProductListRevision, Long> {
    /**
     * 지금의 revision. 행이 없으면 0이다. 잠그지 않는 일반 `SELECT`라서 고객 목록의 읽기 전용 트랜잭션에서 첫 문장으로 읽으면
     * 뒤따르는 페이지 쿼리와 좋아요 수 조회가 같은 스냅샷을 본다. `FOR SHARE`로 읽지 않는다. 그러면 커밋하지 않은 쓰기 뒤에 줄을 서고
     * 스냅샷이 아니라 가장 최근에 커밋된 값을 읽는다(ADR 0020 규칙 2).
     */
    @Query("select coalesce(max(r.revision), 0L) from ProductListRevision r where r.id = ${ProductListRevision.ID}")
    fun findRevision(): Long

    /**
     * revision을 하나 올린다. 행이 없으면 1로 만든다. 더하기는 문장 안에서 하므로 Kotlin에서 읽고 더해 쓰지 않는다.
     * 그러려면 잠금 읽기가 하나 더 들고 뜨거운 행을 더 오래 쥔다. upsert라서 표를 비운 뒤에도 0행을 고치고 지나가지 않는다(ADR 0020 규칙 1).
     */
    @Modifying
    @Query(
        "insert into product_list_revision (id, revision) values (${ProductListRevision.ID}, 1) " +
            "on duplicate key update revision = revision + 1",
        nativeQuery = true,
    )
    fun raise()

    /**
     * 영속성 컨텍스트를 flush한다. `JpaRepository.flush`와 이름이 같아 `SimpleJpaRepository`로 간다.
     * 올리기 앞의 flush도 이 저장소를 거쳐 예외가 번역되게 하려고 둔다.
     */
    fun flush()
}
