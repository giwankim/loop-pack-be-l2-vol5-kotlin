package com.loopers.adapter.scheduling

import com.loopers.support.test.BaseApplicationServiceTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext
import org.springframework.scheduling.config.TaskManagementConfigUtils

/** The `test` profile doesn't turn the relay on, so this is the context every other scene shares. Their rounds run only by hand. */
class OrderEventRelaySchedulerDisabledTest(
    private val applicationContext: ApplicationContext,
) : BaseApplicationServiceTest() {
    @Test
    fun `with the property off, the trigger's bean doesn't exist`() {
        assertThat(applicationContext.getBeansOfType(OrderEventRelayScheduler::class.java)).isEmpty()
    }

    @Test
    fun `with the property off, nothing runs scheduled methods`() {
        val processorBeanName = TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME

        assertThat(applicationContext.containsBean(processorBeanName)).isFalse()
    }
}
