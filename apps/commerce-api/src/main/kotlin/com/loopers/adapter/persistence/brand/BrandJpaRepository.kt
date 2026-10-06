package com.loopers.adapter.persistence.brand

import com.loopers.domain.brand.Brand
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.repository.Repository

/**
 * [Brand]의 Spring Data JPA 저장소. [BrandRepositoryImpl]이 이것에 맡겨 domain의 저장 약속을 지킨다.
 * 삭제된 행을 거르는 조건은 [Brand]의 `@SQLRestriction`이 모든 조회에 붙이므로 여기서는 적지 않는다.
 *
 * 목록이 Spring Data의 [Slice]를 돌려주므로 `size + 1`개를 조회해 `hasNext`를 정하고 총 개수는 세지 않는다(설계 5.5).
 * `Page`로 바꾸면 count 쿼리가 말없이 따라붙는다. `BrandRepositoryTest`가 조회 한 번을 세어 그 실수를 잡는다.
 */
interface BrandJpaRepository : Repository<Brand, Long> {
    fun save(brand: Brand): Brand

    /**
     * `CrudRepository.findById`와 이름·매개변수가 같아 `EntityManager.find`로 간다.
     * 없으면 null이다. 반환을 non-null로 적으면 없을 때 예외를 던진다(설계 5.20).
     */
    fun findById(id: Long): Brand?

    fun findAllByOrderByCreatedAtDescIdDesc(pageable: Pageable): Slice<Brand>

    fun existsByName(name: String): Boolean

    fun existsByNameAndIdNot(name: String, id: Long): Boolean
}
