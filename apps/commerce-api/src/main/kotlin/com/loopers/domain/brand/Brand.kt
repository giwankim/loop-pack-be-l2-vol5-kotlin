package com.loopers.domain.brand

import com.loopers.domain.BaseEntity
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "brand")
class Brand(
    name: String,
) : BaseEntity() {
    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    var name: String = validName(name)
        protected set

    companion object {
        const val NAME_MAX_LENGTH = 100

        private fun validName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                throw CoreException(errorType = ErrorType.INVALID_NAME, customMessage = "브랜드 이름은 공백일 수 없습니다.")
            }
            if (trimmed.length > NAME_MAX_LENGTH) {
                throw CoreException(errorType = ErrorType.INVALID_NAME, customMessage = "브랜드 이름은 ${NAME_MAX_LENGTH}자 이하여야 합니다.")
            }
            return trimmed
        }
    }
}
