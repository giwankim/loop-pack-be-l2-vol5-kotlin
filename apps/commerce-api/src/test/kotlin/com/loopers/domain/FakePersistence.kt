package com.loopers.domain

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * fake 저장소가 JPA의 저장 수명 주기를 흉내 내도록 돕는다.
 *
 * [BaseEntity]의 id와 생성·수정 시각은 JPA만 채울 수 있으므로, 처음 저장할 때 id를 매기고
 * `@PrePersist` 콜백을, 다시 저장할 때 `@PreUpdate` 콜백을 호출한다.
 */
class FakePersistence {
    private var sequence = 0L

    fun persist(entity: BaseEntity) {
        if (entity.id == 0L) {
            ID.set(entity, ++sequence)
            PRE_PERSIST.invoke(entity)
        } else {
            PRE_UPDATE.invoke(entity)
        }
    }

    private companion object {
        val ID: Field = BaseEntity::class.java.getDeclaredField("id").apply { isAccessible = true }
        val PRE_PERSIST: Method = BaseEntity::class.java.getDeclaredMethod("prePersist").apply { isAccessible = true }
        val PRE_UPDATE: Method = BaseEntity::class.java.getDeclaredMethod("preUpdate").apply { isAccessible = true }
    }
}
