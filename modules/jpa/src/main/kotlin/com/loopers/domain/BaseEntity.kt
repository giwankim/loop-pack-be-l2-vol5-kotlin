package com.loopers.domain

import jakarta.persistence.Column
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 생성/수정/삭제 정보를 자동으로 관리해준다.
 * 재사용성을 위해 이 외의 컬럼이나 동작은 추가하지 않는다.
 *
 * 모든 엔티티가 이 클래스를 상속한다. 애그리거트에 딸린 엔티티도 그렇다(ADR 0016).
 * - 애그리거트 루트는 삭제가 기능이 아니어도 논리 삭제를 기본으로 한다. 루트마다 `@SQLRestriction("deleted_at is null")`을
 *   붙이고 저장소 테스트로 "삭제된 것은 없는 것"을 확인한다. Hibernate는 [MappedSuperclass]의 `@SQLRestriction`을 무시한다.
 * - 딸린 엔티티는 루트를 따른다. 필터를 붙이지 않고 [delete]를 부르지 않으며, 루트를 거쳐서만 읽는다.
 * - 좋아요는 예외로, 취소하면 행을 지운다(ADR 0001).
 *
 * 시각은 마이크로초로 자른 [Instant]다. MySQL `datetime(6)`과 정밀도가 같아 저장 전의 첫 응답과 저장 후 조회가 같다.
 *
 * @property id 엔티티 ID
 * @property createdAt 생성 시점
 * @property updatedAt 수정 시점
 * @property deletedAt 삭제 시점
 */
@MappedSuperclass
abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @Column(nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @Column(nullable = false)
    lateinit var updatedAt: Instant
        protected set

    var deletedAt: Instant? = null
        protected set

    /**
     * 엔티티의 유효성을 검증한다.
     *
     * 이 메소드는 [PrePersist] 및 [PreUpdate] 시점에 호출된다.
     */
    open fun guard() = Unit

    @PrePersist
    private fun prePersist() {
        guard()

        val now = now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    private fun preUpdate() {
        guard()

        val now = now()
        updatedAt = now
    }

    /**
     * delete 연산은 멱등하게 동작할 수 있도록 한다. (삭제된 엔티티를 다시 삭제해도 동일한 결과가 나오도록)
     */
    fun delete() {
        deletedAt ?: run { deletedAt = now() }
    }

    private fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
}
