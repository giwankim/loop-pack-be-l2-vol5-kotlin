package com.loopers.adapter.monitoring

import com.loopers.support.test.BaseApplicationServiceTest
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext

/**
 * The `test` profile turns the gauge off, so this is the context every other scene shares: no background count reaches their SQL
 * captures (spec 266, story 23). Off, the gauge's metric isn't registered at all.
 */
class UnresolvedPaymentGaugeDisabledTest(
    private val applicationContext: ApplicationContext,
    private val meterRegistry: MeterRegistry,
) : BaseApplicationServiceTest() {
    @Test
    fun `with the property off, the gauge's bean doesn't exist and its metric isn't registered`() {
        assertThat(applicationContext.getBeansOfType(UnresolvedPaymentGauge::class.java)).isEmpty()
        assertThat(meterRegistry.find(UnresolvedPaymentGauge.NAME).meters()).isEmpty()
    }
}
