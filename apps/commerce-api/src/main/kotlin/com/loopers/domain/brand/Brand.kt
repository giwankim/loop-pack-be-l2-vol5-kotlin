package com.loopers.domain.brand

import com.loopers.domain.BaseEntity
import com.loopers.domain.shared.InvalidNameException
import com.loopers.domain.shared.Name
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.SQLRestriction

@Entity
@Table(name = "brand")
@SQLRestriction("deleted_at is null")
class Brand(
    name: Name,
) : BaseEntity() {
    @Embedded
    @AttributeOverride(name = "value", column = Column(name = "name", nullable = false, length = NAME_MAX_LENGTH))
    var name: Name = name
        protected set

    init {
        if (name.value.length > NAME_MAX_LENGTH) {
            throw InvalidNameException("브랜드 이름은 ${NAME_MAX_LENGTH}자 이하여야 합니다.")
        }
    }

    companion object {
        const val NAME_MAX_LENGTH = 100
    }
}
