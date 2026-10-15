package com.loopers.application.product

import com.loopers.application.brand.required.ProductListMarker
import com.loopers.application.product.provided.ProductListChangeMarker
import com.loopers.application.product.required.ProductListRevisionRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * [ProductListChangeMarker]와 브랜드가 선언한 [ProductListMarker]의 구현. 처음 표시할 때 트랜잭션에 [RaiseRevisionBeforeCommit]을
 * 하나 등록하고, 그것이 커밋 바로 앞에 revision을 올린다. 두 포트는 부르는 조각만 다르고 하는 일이 같으므로 한 메서드가 둘을 맡는다.
 * 상품 조각의 포트가 브랜드의 포트를 상속하지 않고 구현이 둘을 직접 맡는다(ADR 0017).
 *
 * `@ApplicationService`가 아니다. 그 표지의 `@Transactional`은 트랜잭션 밖의 표시에 새 트랜잭션을 열어 주므로,
 * 표시가 곧바로 커밋되어 아무것도 바꾸지 않은 채 revision만 오른다. 표시는 부르는 쓰기의 트랜잭션에만 합류해야 한다.
 */
@Component
class ProductListRevisionMarker(
    private val productListRevisionRepository: ProductListRevisionRepository,
) : ProductListChangeMarker,
    ProductListMarker {
    override fun markChanged() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "상품 목록이 바뀌었다는 표시는 쓰기의 트랜잭션 안에서만 할 수 있다."
        }

        if (TransactionSynchronizationManager.getSynchronizations().none { it is RaiseRevisionBeforeCommit }) {
            TransactionSynchronizationManager.registerSynchronization(RaiseRevisionBeforeCommit(productListRevisionRepository))
        }
    }

    /**
     * 커밋 바로 앞에 영속성 컨텍스트를 flush하고 revision을 올린다. 그래서 올리기가 트랜잭션의 마지막 SQL이다(ADR 0020 규칙 3).
     *
     * Spring은 `beforeCommit`을 Hibernate가 커밋 안에서 flush하기 전에 부른다. 먼저 flush하지 않으면 올리기가 쓰기의 UPDATE·DELETE보다
     * 앞서 나가, revision을 쥔 채 다른 행의 잠금을 기다릴 수 있다. 좋아요 취소의 DELETE가 그렇다. 그 행은 flush에서 처음 잠긴다.
     *
     * 여기서 난 예외는 Spring이 트랜잭션을 되돌리고 호출자에게 던진다. 커밋 뒤의 훅, `REQUIRES_NEW`, 이벤트 리스너로 옮기지 않는다.
     * 그러면 올리기가 실패해도 쓰기는 남아 옛 결과가 캐시에 남는다(ADR 0020 규칙 1).
     */
    private class RaiseRevisionBeforeCommit(
        private val productListRevisionRepository: ProductListRevisionRepository,
    ) : TransactionSynchronization {
        override fun beforeCommit(readOnly: Boolean) {
            productListRevisionRepository.flush()
            productListRevisionRepository.raise()
        }
    }
}
