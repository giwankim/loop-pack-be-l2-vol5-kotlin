package com.loopers.domain

import org.instancio.InstancioApi
import org.instancio.kotlin.KSelect.field

/**
 * Instancio가 만드는 엔티티를 저장하지 않은 상태로 둔다. `id`가 0이 아니면 `save`가 삽입하지 않고 병합하고,
 * `deletedAt`이 있으면 `@SQLRestriction`이 행을 가린다. `createdAt`·`updatedAt`은 `@PrePersist`가 덮어쓴다(ADR 0010).
 */
fun <T : BaseEntity> InstancioApi<T>.unsaved(): InstancioApi<T> =
    ignore(field(BaseEntity::id))
        .ignore(field(BaseEntity::deletedAt))
