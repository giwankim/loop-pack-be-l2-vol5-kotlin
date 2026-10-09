package com.loopers.application.brand.required

import com.loopers.domain.brand.Brand
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.repository.Repository

/**
 * 브랜드 저장소. Spring Data가 구현을 만든다. 삭제된 브랜드는 없는 브랜드이므로 조회는 존재만 묻고, 삭제된 행은 없다고 답한다.
 * 삭제된 행을 거르는 조건은 [Brand]의 `@SQLRestriction`이 모든 조회에 붙이므로 여기서는 적지 않는다.
 */
interface BrandRepository : Repository<Brand, Long> {
    fun save(brand: Brand): Brand

    /**
     * `CrudRepository.findById`와 이름·매개변수가 같아 `EntityManager.find`로 간다.
     * 없으면 null이다. 반환을 non-null로 적으면 없을 때 예외를 던진다(설계 5.20).
     */
    fun findById(id: Long): Brand?

    /**
     * [id] 브랜드를 `FOR UPDATE`로 잠가 읽는다. 없거나 삭제됐으면 null이다(ADR 0018).
     * `find`와 `By` 사이는 Spring Data가 설명으로 보므로 `@Query` 없이 `id`로 찾는 파생 조회다.
     * 잠금 읽기는 기다린 뒤 가장 최근에 커밋된 행을 읽으므로, 그 사이 커밋된 삭제도 `@SQLRestriction`이 걸러 낸다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateById(id: Long): Brand?

    /**
     * 삭제되지 않은 브랜드를 최신 등록순(등록 시각 내림차순, 동률은 id 내림차순)으로 한 조각 읽는다. 차례는 이름이 적으므로
     * [pageable]에는 조각의 위치와 크기만 싣는다.
     *
     * Spring Data의 [Slice]이므로 `size + 1`개를 조회해 `hasNext`를 정하고 총 개수는 세지 않는다(설계 5.5).
     * `Page`로 바꾸면 count 쿼리가 말없이 따라붙는다. `BrandRepositoryTest`가 조회 한 번을 세어 그 실수를 잡는다.
     *
     * 이름을 `findAll(Pageable)`로 줄이지 않는다. 그 이름과 매개변수는 `SimpleJpaRepository.findAll(Pageable)`과 같아,
     * `@Query`가 없으면 Spring Data가 호출을 그 기본 구현으로 보내고 차례 없이 count 쿼리가 붙은 `Page`를 돌려준다.
     * 짝을 찾을 때 반환 타입은 보지 않고 `Page`는 [Slice]이므로 컴파일도 된다.
     */
    fun findAllByOrderByCreatedAtDescIdDesc(pageable: Pageable): Slice<Brand>

    fun existsByName(name: String): Boolean

    /** [id]가 아닌 다른 삭제되지 않은 브랜드가 [name]을 쓰고 있는지. 이름 수정이 자기 이름과 겹치는 것을 중복으로 보지 않게 한다. */
    fun existsByNameAndIdNot(name: String, id: Long): Boolean
}
