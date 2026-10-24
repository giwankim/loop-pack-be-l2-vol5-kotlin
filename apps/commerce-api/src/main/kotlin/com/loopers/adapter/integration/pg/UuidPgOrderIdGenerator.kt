package com.loopers.adapter.integration.pg

import com.loopers.domain.payment.PgOrderIdGenerator
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Mints PG order IDs as random UUID strings (ADR 0029 rule 1). The PG requires at least 6 characters, and a UUID string is 36
 * ASCII characters, which also fits Toss's 6 to 64 letters, digits, `-` and `_`. If the PG narrows its format, only this class
 * changes.
 */
@Component
class UuidPgOrderIdGenerator : PgOrderIdGenerator {
    override fun generate(): String {
        return UUID.randomUUID().toString()
    }
}
