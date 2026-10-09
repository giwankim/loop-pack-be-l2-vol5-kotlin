package com.loopers.application.brand.provided

import com.loopers.application.brand.BrandModifyService
import com.loopers.application.brand.BrandQueryService
import com.loopers.application.order.provided.OrderConfirmer
import com.loopers.application.product.ProductModifyService
import com.loopers.application.product.provided.ProductRegister
import com.loopers.domain.brand.createBrandAdminUpdateRequest
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.product.createProductAdminRegisterRequest
import com.loopers.domain.product.createProductAdminStockUpdateRequest
import com.loopers.domain.product.createProductAdminUpdateRequest
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.Pause
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.test.util.AopTestUtils

/**
 * 브랜드 삭제와 그 브랜드나 상품을 바꾸는 다른 쓰기가 엇갈릴 때를 실제 MySQL의 행 잠금으로 확인한다(ADR 0018 규칙 1, 3, 4).
 * 어느 쪽이 먼저 잠그든 둘 중 하나로 끝나야 한다. 다른 쪽이 먼저 끝나고 삭제가 그 결과까지 삭제하거나, 삭제가 먼저 끝나고 다른 쪽이
 * 없는 대상으로 거절된다. 되살아난 브랜드나 상품, 삭제된 브랜드 아래 삭제되지 않은 상품, 사라진 변경은 어느 경우에도 남지 않는다.
 * 브랜드 행 잠금이 막지 않아야 하는 것도 여기서 본다. 같은 브랜드의 상품 등록끼리, 그리고 다른 브랜드 상품의 주문 확정은 삭제를 기다리지 않는다.
 *
 * 멈추는 방법과 기다리는지 보는 방법은 [com.loopers.application.order.provided.OrderConfirmerConcurrencyTest]와 같다.
 */
class BrandRegisterConcurrencyTest(
    private val brandRegister: BrandRegister,
    private val productRegister: ProductRegister,
    private val orderConfirmer: OrderConfirmer,
) : BaseCommittingApplicationServiceTest() {
    @MockkSpyBean
    private lateinit var productModifyService: ProductModifyService

    @MockkSpyBean
    private lateinit var brandModifyService: BrandModifyService

    @MockkSpyBean
    private lateinit var brandQueryService: BrandQueryService

    private val pause = Pause()

    /** 등록이 공유 잠금으로 읽는 브랜드 행은 삭제가 커밋한 뒤 삭제된 행이므로 삭제 필터가 걸러 낸다. */
    @Test
    fun `registering a product while a brand delete holds it waits, then is rejected as BRAND_NOT_FOUND and saves nothing`() {
        prepareBrand()
        pauseBrandDeleteAfterItsProducts()

        val deleting = inAnotherThread { brandRegister.delete(brand.id) }
        pause.awaitHeld()
        val registering = inAnotherThread { productRegister.register(createProductAdminRegisterRequest(brand.id)) }

        assertThat(registering.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        deleting.await()
        val exception = assertThrows<CoreException> { registering.await() }
        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
        inNewTransaction {
            assertThat(countProductRows()).isZero()
        }
    }

    /**
     * 등록이 상품을 넣기 전, 브랜드를 읽은 자리에서 멈춘다. 상품을 넣은 뒤에 멈추면 외래 키 검사가 건 공유 잠금이 삭제를 막아,
     * 브랜드 읽기의 잠금이 없어도 이 테스트가 지나간다. 연쇄의 잠금 읽기는 기다린 뒤 커밋된 새 상품을 보고 함께 삭제한다.
     */
    @Test
    fun `deleting a brand while a product registration holds the brand waits, then deletes the new product too`() {
        prepareBrand()
        pauseRegistrationAfterItsBrandRead()

        val registering = inAnotherThread { productRegister.register(createProductAdminRegisterRequest(brand.id)) }
        pause.awaitHeld()
        val deleting = inAnotherThread { brandRegister.delete(brand.id) }

        assertThat(deleting.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        val registered = registering.await()
        deleting.await()
        inNewTransaction {
            assertThat(countProductRows()).isOne()
            assertThat(columnOf("product", "deleted_at", registered.id)).isNotNull()
        }
    }

    /** 공유 잠금끼리는 서로 막지 않으므로, 같은 브랜드의 등록은 줄을 서지 않는다. */
    @Test
    fun `registering a product while another registration under the same brand holds it finishes without waiting`() {
        prepareBrand()
        pauseRegistrationAfterItsBrandRead()

        val first = inAnotherThread { productRegister.register(createProductAdminRegisterRequest(brand.id)) }
        pause.awaitHeld()
        val second = inAnotherThread { productRegister.register(createProductAdminRegisterRequest(brand.id)) }

        assertThat(second.finishesWithin(LOCK_WAIT_PROBE)).isTrue()
        second.await()
        pause.release()
        first.await()
        inNewTransaction {
            assertThat(countProductRows()).isEqualTo(2L)
        }
    }

    /** 관리자의 상품 쓰기가 잠가 읽는 상품 행은 연쇄가 커밋한 뒤 삭제된 행이므로 삭제 필터가 걸러 낸다. */
    @Test
    fun `updating a product while its brand delete holds it waits, then is rejected as PRODUCT_NOT_FOUND`() {
        prepareProduct(name = "처음 이름")
        pauseBrandDeleteAfterItsProducts()

        val deleting = inAnotherThread { brandRegister.delete(brand.id) }
        pause.awaitHeld()
        val updating = inAnotherThread {
            productRegister.update(product.id, createProductAdminUpdateRequest(name = "바꾼 이름"))
        }

        assertThat(updating.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        deleting.await()
        val exception = assertThrows<CoreException> { updating.await() }
        assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
        inNewTransaction {
            assertThat(columnOf("product", "name", product.id)).isEqualTo("처음 이름")
            assertThat(columnOf("product", "deleted_at", product.id)).isNotNull()
        }
    }

    @Test
    fun `updating a product's stock while its brand delete holds it waits, then is rejected as PRODUCT_NOT_FOUND`() {
        prepareProduct(stock = 10)
        pauseBrandDeleteAfterItsProducts()

        val deleting = inAnotherThread { brandRegister.delete(brand.id) }
        pause.awaitHeld()
        val updating = inAnotherThread {
            productRegister.updateStock(product.id, createProductAdminStockUpdateRequest(quantity = 3))
        }

        assertThat(updating.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        deleting.await()
        val exception = assertThrows<CoreException> { updating.await() }
        assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
        inNewTransaction {
            assertThat(columnOf("product", "stock", product.id)).isEqualTo(10)
            assertThat(columnOf("product", "deleted_at", product.id)).isNotNull()
        }
    }

    @Test
    fun `deleting a product while its brand delete holds it waits, then is rejected as PRODUCT_NOT_FOUND`() {
        prepareProduct()
        pauseBrandDeleteAfterItsProducts()

        val deleting = inAnotherThread { brandRegister.delete(brand.id) }
        pause.awaitHeld()
        val deletingProduct = inAnotherThread { productRegister.delete(product.id) }

        assertThat(deletingProduct.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        deleting.await()
        val exception = assertThrows<CoreException> { deletingProduct.await() }
        assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
        inNewTransaction {
            assertThat(columnOf("product", "deleted_at", product.id)).isNotNull()
        }
    }

    /** 연쇄의 잠금 읽기는 기다린 뒤 커밋된 수정을 읽고 삭제하므로, 수정은 남고 상품은 되살아나지 않는다. */
    @Test
    fun `deleting a brand while a product update holds the product waits, then deletes the updated product`() {
        prepareProduct(name = "처음 이름", price = 1_000)
        pauseProductUpdateAfterUpdating()

        val updating = inAnotherThread {
            productRegister.update(product.id, createProductAdminUpdateRequest(name = "바꾼 이름", price = 2_000))
        }
        pause.awaitHeld()
        val deleting = inAnotherThread { brandRegister.delete(brand.id) }

        assertThat(deleting.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        updating.await()
        deleting.await()
        inNewTransaction {
            assertThat(columnOf("product", "name", product.id)).isEqualTo("바꾼 이름")
            assertThat(columnOf("product", "price", product.id)).isEqualTo(2_000L)
            assertThat(columnOf("product", "deleted_at", product.id)).isNotNull()
        }
    }

    /** 이름 변경이 잠가 읽는 브랜드 행은 삭제가 커밋한 뒤 삭제된 행이므로 삭제 필터가 걸러 낸다. */
    @Test
    fun `renaming a brand while its delete holds it waits, then is rejected as BRAND_NOT_FOUND`() {
        prepareBrand(name = "처음 이름")
        pauseBrandDeleteAfterItsProducts()

        val deleting = inAnotherThread { brandRegister.delete(brand.id) }
        pause.awaitHeld()
        val renaming = inAnotherThread { brandRegister.update(brand.id, createBrandAdminUpdateRequest(name = "바꾼 이름")) }

        assertThat(renaming.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        deleting.await()
        val exception = assertThrows<CoreException> { renaming.await() }
        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
        inNewTransaction {
            assertThat(columnOf("brand", "name", brand.id)).isEqualTo("처음 이름")
            assertThat(columnOf("brand", "deleted_at", brand.id)).isNotNull()
        }
    }

    @Test
    fun `deleting a brand while a rename holds it waits, then deletes the renamed brand`() {
        prepareBrand(name = "처음 이름")
        pauseRenameAfterRenaming()

        val renaming = inAnotherThread { brandRegister.update(brand.id, createBrandAdminUpdateRequest(name = "바꾼 이름")) }
        pause.awaitHeld()
        val deleting = inAnotherThread { brandRegister.delete(brand.id) }

        assertThat(deleting.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        renaming.await()
        deleting.await()
        inNewTransaction {
            assertThat(columnOf("brand", "name", brand.id)).isEqualTo("바꾼 이름")
            assertThat(columnOf("brand", "deleted_at", brand.id)).isNotNull()
        }
    }

    /** 뒤의 삭제는 브랜드를 잠가 읽다가 기다리므로 멈출 단계에 닿지 않고, 커밋된 삭제를 보고 거절된다. 두 번 차례로 지운 것과 같다. */
    @Test
    fun `deleting a brand while another delete of it holds it waits, then is rejected as BRAND_NOT_FOUND`() {
        prepareProduct()
        pauseBrandDeleteAfterItsProducts()

        val first = inAnotherThread { brandRegister.delete(brand.id) }
        pause.awaitHeld()
        val second = inAnotherThread { brandRegister.delete(brand.id) }

        assertThat(second.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        first.await()
        val exception = assertThrows<CoreException> { second.await() }
        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
        inNewTransaction {
            assertThat(columnOf("brand", "deleted_at", brand.id)).isNotNull()
            assertThat(columnOf("product", "deleted_at", product.id)).isNotNull()
        }
    }

    /**
     * 연쇄는 `brand_id` 인덱스를 따라 그 브랜드의 상품 행만 잠근다. 인덱스가 없으면 잠금 읽기가 훑은 모든 행을 잠가,
     * 다른 브랜드 상품의 확정도 삭제가 커밋할 때까지 기다린다(ADR 0018 규칙 6). 외래 키도 인덱스를 남기므로, 이 테스트가
     * 실패하는 것을 보려면 외래 키와 `brand_id`의 인덱스를 모두 지워야 한다.
     */
    @Test
    fun `confirming another brand's product while a brand delete holds its locks finishes without waiting`() {
        val brandToDelete = prepareBrand()
        prepareProduct(brand = brandToDelete)
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 1)
        charge(amount = 1_000)
        pauseBrandDeleteAfterItsProducts()

        val deleting = inAnotherThread { brandRegister.delete(brandToDelete.id) }
        pause.awaitHeld()
        val confirming = inAnotherThread { orderConfirmer.confirm(user.id, order.id) }

        assertThat(confirming.finishesWithin(LOCK_WAIT_PROBE)).isTrue()
        assertThat(confirming.await().status).isEqualTo(OrderStatus.CONFIRMED)
        pause.release()
        deleting.await()
    }

    /** 브랜드 삭제가 브랜드와 그 상품을 잠가 삭제한 뒤, 브랜드를 저장하기 전에 멈춘다. */
    private fun pauseBrandDeleteAfterItsProducts() {
        val target = AopTestUtils.getUltimateTargetObject<ProductModifyService>(productModifyService)
        every { target.deleteAllOfBrand(any()) } answers {
            callOriginal()
            pause.hold()
        }
    }

    /** 관리자의 상품 수정이 잠가 읽은 상품을 바꾼 뒤, 커밋하기 전에 멈춘다. */
    private fun pauseProductUpdateAfterUpdating() {
        val target = AopTestUtils.getUltimateTargetObject<ProductModifyService>(productModifyService)
        every { target.update(any(), any()) } answers {
            callOriginal().also { pause.hold() }
        }
    }

    /** 브랜드 이름 변경이 잠가 읽은 브랜드의 이름을 바꾼 뒤, 커밋하기 전에 멈춘다. */
    private fun pauseRenameAfterRenaming() {
        val target = AopTestUtils.getUltimateTargetObject<BrandModifyService>(brandModifyService)
        every { target.update(any(), any()) } answers {
            callOriginal().also { pause.hold() }
        }
    }

    /**
     * 상품 등록이 브랜드를 공유 잠금으로 읽은 뒤, 상품을 넣기 전에 멈춘다. 처음 부른 등록만 멈추고, 뒤의 호출은 그대로 지나간다.
     * 브랜드 삭제는 이 읽기를 부르지 않는다.
     */
    private fun pauseRegistrationAfterItsBrandRead() {
        val target = AopTestUtils.getUltimateTargetObject<BrandQueryService>(brandQueryService)
        every { target.findForShare(any()) } answers {
            callOriginal().also { pause.hold() }
        } andThenAnswer {
            callOriginal()
        }
    }

    /** 삭제된 상품도 세도록 SQL 제한을 지나는 native 조회로 상품 표의 행을 센다. */
    private fun countProductRows(): Long {
        return (entityManager.createNativeQuery("select count(*) from product").singleResult as Number).toLong()
    }

    /** 삭제된 브랜드와 상품은 어느 포트로도 읽히지 않으므로 SQL 제한을 지나는 native 조회로 [table] 행의 [column]을 읽는다. */
    private fun columnOf(table: String, column: String, id: Long): Any? {
        return entityManager
            .createNativeQuery("select $column from $table where id = :id")
            .setParameter("id", id)
            .singleResult
    }
}
