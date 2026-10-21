package com.loopers.application.payment.required

import com.loopers.adapter.integration.pg.LocalPgGateway
import com.loopers.adapter.integration.pg.LocalPgGatewayConfig
import com.loopers.config.jackson.JacksonConfig
import com.loopers.domain.payment.CreditCard
import com.loopers.domain.shared.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** TEST-NET-1 (RFC 5737). It is reserved for documentation and never routed, so a SYN sent there gets no answer. */
private const val UNROUTED_HOST = "192.0.2.1"

private const val UNROUTED_PORT = 8082

/**
 * A PG that never completes the TCP handshake. Both calls give up at the production connect timeout of 500 ms and report it
 * like any other lost answer: outcome unknown for a create, unavailable for a lookup (ADR 0022 rule 3). The create was never
 * sent, but that is still no proof that no PG transaction exists, for the same reason as a starved pool (payments design 3.4).
 *
 * The context is [PaymentGatewayTest]'s without the test PG: `pg.base-url` points at [UNROUTED_HOST] instead. A different
 * base URL needs its own context, so these scenes get their own class, like [ExhaustedPoolPaymentGatewayTest].
 *
 * Whether the SYN is silently dropped depends on the network. Without a default route the connect fails at once with
 * "network unreachable", which is a different failure. [assumeTheHandshakeIsDropped] probes the address first and skips the
 * class instead of failing it.
 */
@SpringJUnitConfig(
    classes = [LocalPgGatewayConfig::class, LocalPgGateway::class, JacksonConfig::class],
    initializers = [ConfigDataApplicationContextInitializer::class],
)
@ImportAutoConfiguration(
    JacksonAutoConfiguration::class,
    HttpMessageConvertersAutoConfiguration::class,
    RestClientAutoConfiguration::class,
)
@TestPropertySource(properties = ["pg.base-url=http://$UNROUTED_HOST:$UNROUTED_PORT"])
class ConnectTimeoutPaymentGatewayTest(
    private val paymentGateway: PaymentGateway,
) {
    companion object {
        private const val PG_ORDER_ID = "LP-ORD-0000000042"
        private const val AMOUNT = 7_000L
        private const val TRANSACTION_KEY = "TEST-TX-0042"
        private val CARD = CreditCard(type = "TEST", number = "0000-0000-0000-0000")

        /** Any limit works. A dropped SYN times out however long the probe waits. */
        private const val PROBE_TIMEOUT_MILLIS = 200

        @JvmStatic
        @BeforeAll
        fun assumeTheHandshakeIsDropped() {
            assumeTrue(handshakeIsDropped(), "$UNROUTED_HOST answers or is unreachable here, so no connect can time out")
        }

        private fun handshakeIsDropped(): Boolean {
            Socket().use { socket ->
                return try {
                    socket.connect(InetSocketAddress(UNROUTED_HOST, UNROUTED_PORT), PROBE_TIMEOUT_MILLIS)
                    false
                } catch (e: SocketTimeoutException) {
                    true
                } catch (e: IOException) {
                    false
                }
            }
        }
    }

    /** The message names HttpClient's own connect timeout. An OS-level TCP timeout would be reported under another name. */
    @Test
    fun `a create that cannot connect within the connect timeout throws PgOutcomeUnknownException`() {
        val exception = assertThrows<PgOutcomeUnknownException> {
            paymentGateway.createTransaction(PG_ORDER_ID, Money(AMOUNT), CARD)
        }

        assertThat(exception.message).isEqualTo("ConnectTimeoutException")
    }

    @Test
    fun `a key lookup that cannot connect within the connect timeout throws PgUnavailableException`() {
        val exception = assertThrows<PgUnavailableException> { paymentGateway.retrieveTransaction(TRANSACTION_KEY) }

        assertThat(exception.message).isEqualTo("ConnectTimeoutException")
    }
}
