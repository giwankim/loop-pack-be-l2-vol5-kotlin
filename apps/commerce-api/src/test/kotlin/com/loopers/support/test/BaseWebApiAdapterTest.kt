package com.loopers.support.test

import com.loopers.config.security.AdminSecurityConfig
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.assertj.MockMvcTester

/**
 * MockMvc 테스트의 기반. [BaseApplicationServiceTest]의 설정과 `prepare` 메서드에 MockMvc와 관리자 보안 설정을 더한다.
 *
 * MockMvc는 요청을 테스트 스레드에서 처리하므로 물려받은 테스트 트랜잭션이 컨트롤러와 서비스까지 감싸고, 테스트마다 롤백으로 정리한다.
 * 요청마다 커밋된 결과를 다음 요청이 읽어야 하는 클래스는 클래스에 `@Transactional(propagation = NOT_SUPPORTED)`를 달아 빠지고
 * [com.loopers.support.DatabaseCleanUp]으로 되돌린다. 실제 톰캣(RANDOM_PORT)을 띄우거나 다른 스레드가 끼어드는 테스트는
 * 이 롤백으로 정리할 수 없으므로 이 기반을 쓰지 않는다.
 *
 * [AdminSecurityConfig]를 가져오는 까닭은 관리자 경계가 필요해서만이 아니다. Spring Security가 테스트 클래스패스에 있어서
 * `SecurityFilterChain` 빈이 하나도 없는 컨텍스트에는 Boot 기본 체인이 들어가 모든 경로에 인증을 요구한다(설계 5.10).
 * 이 빈이 있으면 체인은 관리자 경로 하나뿐이고, 고객 경로(`/api/` 아래)는 어느 체인에도 걸리지 않아 그대로 지나간다.
 */
@AutoConfigureMockMvc
@Import(AdminSecurityConfig::class)
abstract class BaseWebApiAdapterTest : BaseApplicationServiceTest() {
    @Autowired
    protected lateinit var mvc: MockMvcTester
}
