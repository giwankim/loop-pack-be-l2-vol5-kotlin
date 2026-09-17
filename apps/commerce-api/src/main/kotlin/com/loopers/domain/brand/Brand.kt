package com.loopers.domain.brand

import com.loopers.domain.BaseEntity
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
    @AttributeOverride(name = "value", column = Column(name = "name", nullable = false, length = Name.MAX_LENGTH))
    var name: Name = name
        protected set
}
