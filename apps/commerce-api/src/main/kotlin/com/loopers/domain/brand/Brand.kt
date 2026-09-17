package com.loopers.domain.brand

import com.loopers.domain.BaseEntity
import com.loopers.domain.InvalidNameException
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
    @Column(nullable = false, length = NAME_MAX_LENGTH)
    var name: String = name.trim()
        protected set

    init {
        if (this.name.isEmpty()) {
            throw InvalidNameException("브랜드 이름은 공백일 수 없습니다.")
        }
        if (this.name.length > NAME_MAX_LENGTH) {
            throw InvalidNameException("브랜드 이름은 ${NAME_MAX_LENGTH}자 이하여야 합니다.")
        }
    }

    companion object {
        const val NAME_MAX_LENGTH = 100
    }
}
