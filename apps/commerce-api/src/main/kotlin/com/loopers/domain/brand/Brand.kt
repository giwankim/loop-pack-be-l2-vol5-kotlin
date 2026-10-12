package com.loopers.domain.brand

import com.loopers.domain.BaseEntity
import com.loopers.domain.shared.InvalidNameException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import org.hibernate.annotations.SQLRestriction

@Entity
@SQLRestriction("deleted_at is null")
class Brand(
    name: String,
) : BaseEntity() {
    /** 받은 그대로의 이름. 공백뿐이지 않고 [NAME_MAX_LENGTH]자 이하다. */
    @Column(nullable = false, length = NAME_MAX_LENGTH)
    var name: String = validatedName(name)
        protected set

    /** 이름을 바꾼다. 거절되면 기존 이름이 그대로 남는다. 이름이 겹치지 않는지는 application이 먼저 본다. */
    fun update(name: String) {
        this.name = validatedName(name)
    }

    companion object {
        const val NAME_MAX_LENGTH = 100

        /**
         * 이름이 지켜야 할 규칙. 생성과 수정이 같은 검사를 쓴다.
         * `name` 프로퍼티의 초기값이 부르는 자리라 받은 값을 그대로 돌려주고, 인스턴스 메서드가 아니라 여기에 둔다.
         */
        private fun validatedName(name: String): String {
            if (name.isBlank()) {
                throw InvalidNameException("브랜드 이름은 공백일 수 없습니다.")
            }

            if (name.length > NAME_MAX_LENGTH) {
                throw InvalidNameException("브랜드 이름은 ${NAME_MAX_LENGTH}자 이하여야 합니다.")
            }

            return name
        }
    }
}
