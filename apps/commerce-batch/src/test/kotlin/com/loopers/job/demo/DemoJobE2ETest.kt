package com.loopers.job.demo

import com.loopers.batch.job.demo.DemoJobConfig
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.testcontainers.RedisTestContainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.test.JobOperatorTestUtils
import org.springframework.batch.test.context.SpringBatchTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.time.LocalDate

@SpringBootTest
@SpringBatchTest
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class)
@TestPropertySource(properties = ["spring.batch.job.name=${DemoJobConfig.JOB_NAME}"])
class DemoJobE2ETest @Autowired constructor(
    // IDE 정적 분석 상 [SpringBatchTest] 의 주입보다 [SpringBootTest] 의 주입이 우선되어, 해당 컴포넌트는 없으므로 오류처럼 보일 수 있음.
    // [SpringBatchTest] 자체가 Scope 기반으로 주입하기 때문에 정상 동작함.
    private val jobOperatorTestUtils: JobOperatorTestUtils,
    @param:Qualifier(DemoJobConfig.JOB_NAME) private val job: Job,
) {
    @BeforeEach
    fun beforeEach() {
    }

    @Test
    fun `demoJob fails when the requestDate job parameter is missing`() {
        // arrange
        jobOperatorTestUtils.job = job

        // act
        val jobExecution = jobOperatorTestUtils.startJob()

        // assert
        assertThat(jobExecution).isNotNull()
        assertThat(jobExecution.exitStatus.exitCode).isEqualTo(ExitStatus.FAILED.exitCode)
    }

    @Test
    fun `demoJob completes when given a requestDate`() {
        // arrange
        jobOperatorTestUtils.job = job

        // act
        val jobParameters = JobParametersBuilder()
            .addLocalDate("requestDate", LocalDate.now())
            .toJobParameters()
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters)

        // assert
        assertThat(jobExecution).isNotNull()
        assertThat(jobExecution.exitStatus.exitCode).isEqualTo(ExitStatus.COMPLETED.exitCode)
    }
}
