package com.loopers.application.order.required

import com.loopers.domain.order.OrderEventOutbox
import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import org.springframework.data.repository.Repository
import java.time.Instant

/** The outbox's store. Spring Data makes the implementation. A row that isn't there is null. */
interface OrderEventOutboxRepository : Repository<OrderEventOutbox, Long> {
    fun save(orderEventOutbox: OrderEventOutbox): OrderEventOutbox

    /** The row of one event. `UK_ORDER_EVENT_OUTBOX_ON_EVENT_ID` allows at most one. */
    fun findByEventId(eventId: String): OrderEventOutbox?

    fun count(): Long

    /**
     * The rows whose turn has come at [now], oldest due first, locked with `FOR UPDATE SKIP LOCKED` so that another claim takes the
     * next rows rather than waiting (ADR 0035 rule 2). A lock timeout of -2 is how JPA's hint asks Hibernate for `SKIP LOCKED`. [now]
     * comes from the `Clock` bean, not the database's `now()`.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query(
        """
        select o from OrderEventOutbox o
        where o.status = com.loopers.domain.order.OrderEventOutboxStatus.PENDING and o.nextAttemptAt <= :now
        order by o.nextAttemptAt, o.id
        """,
    )
    fun findDueForUpdate(now: Instant, pageable: Pageable): List<OrderEventOutbox>

    /** One row locked with `FOR UPDATE`, so a result is applied to its current state. `find` and `By` frame a derived query's description. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateById(orderEventOutboxId: Long): OrderEventOutbox?
}
