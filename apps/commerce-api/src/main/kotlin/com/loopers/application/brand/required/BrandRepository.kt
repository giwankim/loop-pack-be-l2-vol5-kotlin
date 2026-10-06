package com.loopers.application.brand.required

import com.loopers.application.shared.toPageSlice
import com.loopers.domain.brand.Brand
import com.loopers.domain.shared.PageSlice
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
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
     * 삭제되지 않은 브랜드를 최신 등록순(등록 시각 내림차순, 동률은 id 내림차순)으로 한 조각 읽는다.
     *
     * 본문이 있는 메서드는 JVM default method가 되고, Spring Data는 그것을 쿼리로 만들지 않고 본문을 실행한다.
     * 컴파일러 설정 `jvmDefault`를 `DISABLE`로 바꾸면 default method가 사라져 이 메서드가 쿼리 메서드로 읽힌다.
     */
    fun findAll(page: Int, size: Int): PageSlice<Brand> =
        findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(page, size)).toPageSlice()

    /**
     * [findAll]이 맡기는 파생 조회. 포트가 Spring Data의 조각을 내주지 않도록 바깥에서는 [findAll]을 부른다.
     * Spring Data의 [Slice]이므로 `size + 1`개를 조회해 `hasNext`를 정하고 총 개수는 세지 않는다(설계 5.5).
     * `Page`로 바꾸면 count 쿼리가 말없이 따라붙는다. `BrandRepositoryTest`가 조회 한 번을 세어 그 실수를 잡는다.
     */
    fun findAllByOrderByCreatedAtDescIdDesc(pageable: Pageable): Slice<Brand>

    fun existsByName(name: String): Boolean

    /** [id]가 아닌 다른 삭제되지 않은 브랜드가 [name]을 쓰고 있는지. 이름 수정이 자기 이름과 겹치는 것을 중복으로 보지 않게 한다. */
    fun existsByNameAndIdNot(name: String, id: Long): Boolean
}
