package com.loopers.domain.shared

import com.loopers.domain.InvalidNameException
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * 브랜드와 상품이 함께 쓰는 이름. 앞뒤 공백을 뗀 뒤 비어 있지 않고 [MAX_LENGTH]자 이하다.
 * 불변 값 객체이며, 뗀 값이 같으면 같은 이름이다. 컬럼 이름은 쓰는 엔티티가 `@AttributeOverride`로 정한다.
 */
@Embeddable
class Name(
    value: String,
) {
    @Column(nullable = false, length = MAX_LENGTH)
    val value: String = value.trim()

    init {
        if (this.value.isEmpty()) {
            throw InvalidNameException("이름은 공백일 수 없습니다.")
        }
        if (this.value.length > MAX_LENGTH) {
            throw InvalidNameException("이름은 ${MAX_LENGTH}자 이하여야 합니다.")
        }
    }

    override fun equals(other: Any?): Boolean = other is Name && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 100
    }
}
