package com.loopers.testcontainers

import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket

/**
 * 장애 테스트가 멈추고, 다시 띄우고, 걸리게 하는 Redis 컨테이너다. [StoppableRedisTestContainersConfig]가 컨텍스트마다 하나 띄운다.
 *
 * 다시 띄워도 주소가 그대로여야 Lettuce가 같은 주소로 다시 연결한다. 그래서 시작할 때 빈 host 포트를 골라 고정하고,
 * 멈추기와 다시 띄우기는 같은 컨테이너를 docker-java로 stop/start한다. `GenericContainer.stop()`은 컨테이너를 지우고
 * `start()`는 새 포트에 새 컨테이너를 만들어, Lettuce가 옛 포트에 계속 다시 연결한다.
 *
 * - [stop]·[start]: 끊긴 Redis. 연결이 거절되고, 다시 띄우면 데이터가 남아 있다.
 * - [pause]·[unpause]: 걸린 Redis. 연결은 열려 있지만 응답이 오지 않는다.
 * - [close]: 이 컨테이너만 지운다. 컨텍스트가 닫힐 때 Spring이 부른다.
 *
 * 컨텍스트를 나눠 쓰는 다음 테스트를 위해, 멈추거나 걸리게 한 테스트는 끝날 때 [stop]·[pause]를 되돌린다.
 *
 * [RedisTestContainersConfig]처럼 저장 지점을 두지 않아 `FLUSHALL`이 RDB 파일을 쓰지 않는다. 다만 멈출 때(SIGTERM)는 RDB를 남긴다
 * (`--shutdown-on-sigterm save`). 저장 지점이 없으면 Redis는 멈출 때도 저장하지 않으므로, 이것이 없으면 다시 띄운 Redis가
 * 멈추기 전의 값을 잃는다. 그 값이 남아 있다는 것이 장애 테스트의 장면이다.
 */
class StoppableRedis : AutoCloseable {
    companion object {
        private const val REDIS_PORT = 6379
    }

    private val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:8.10"))
        .apply {
            val hostPort = ServerSocket(0).use { it.localPort }
            withExposedPorts(REDIS_PORT)
            withCommand("redis-server", "--save", "", "--appendonly", "no", "--shutdown-on-sigterm", "save")
            withCreateContainerCmdModifier { command ->
                command.hostConfig!!.withPortBindings(
                    PortBinding(Ports.Binding.bindPort(hostPort), ExposedPort.tcp(REDIS_PORT)),
                )
            }
            start()
        }

    val host: String
        get() = container.host

    val port: Int
        get() = container.firstMappedPort

    fun stop() {
        container.dockerClient.stopContainerCmd(container.containerId).exec()
    }

    /** 같은 컨테이너를 다시 띄우고, Redis가 같은 포트에서 연결을 받을 때까지 기다린다. */
    fun start() {
        container.dockerClient.startContainerCmd(container.containerId).exec()
        Wait.forListeningPort().waitUntilReady(container)
    }

    fun pause() {
        container.dockerClient.pauseContainerCmd(container.containerId).exec()
    }

    fun unpause() {
        container.dockerClient.unpauseContainerCmd(container.containerId).exec()
    }

    /** 걸렸으면 풀고 멈췄으면 다시 띄운다. 돌고 있으면 아무것도 하지 않는다. 테스트가 중간에 실패해도 정리에서 부른다. */
    fun restore() {
        val state = container.dockerClient.inspectContainerCmd(container.containerId).exec().state
        when {
            state.paused == true -> unpause()
            state.running != true -> start()
        }
    }

    override fun close() {
        container.stop()
    }
}
