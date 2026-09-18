package com.loopers.domain.brand

import com.loopers.domain.BaseEntity
import com.loopers.domain.shared.InvalidNameException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.SQLRestriction

@Entity
@Table(name = "brand")
@SQLRestriction("deleted_at is null")
class Brand(
    name: String,
) : BaseEntity() {
    /** 앞뒤 공백을 뗀 이름. 비어 있지 않고 [NAME_MAX_LENGTH]자 이하다. */
    @Column(nullable = false, length = NAME_MAX_LENGTH)
    var name: String = normalizeName(name)
        protected set

    /** 이름을 바꾼다. 거절되면 기존 이름이 그대로 남는다. 이름이 겹치지 않는지는 application이 먼저 본다. */
    fun update(name: String) {
        this.name = normalizeName(name)
    }

    companion object {
        const val NAME_MAX_LENGTH = 100

        /**
         * 이름의 앞뒤 공백을 떼고 규칙을 검사해 저장될 값을 돌려준다. 어기면 [InvalidNameException].
         *
         * 브랜드를 만들지 않고도 저장될 이름을 알아야 하는 곳이 있어 공개한다. 이름 수정은 바꾸기 전에
         * 그 이름이 다른 브랜드의 것인지 물어야 하는데, 물어볼 이름은 뗀 이름이어야 하기 때문이다(설계 5.23).
         */
        fun normalizeName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                throw InvalidNameException("브랜드 이름은 공백일 수 없습니다.")
            }
            if (trimmed.length > NAME_MAX_LENGTH) {
                throw InvalidNameException("브랜드 이름은 ${NAME_MAX_LENGTH}자 이하여야 합니다.")
            }
            return trimmed
        }
    }
}
