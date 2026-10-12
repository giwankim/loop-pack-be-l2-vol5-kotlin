package com.loopers.application.brand.provided

import com.loopers.application.brand.required.BrandRepository
import com.loopers.application.order.provided.OrderFinder
import com.loopers.application.product.provided.ProductFinder
import com.loopers.domain.brand.Brand
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.product.Product
import com.loopers.domain.shared.Money
import com.loopers.support.brandDeletedAt
import com.loopers.support.productDeletedAt
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataAccessResourceFailureException
import java.time.Instant

/**
 * 3주차 과제 4절 표의 "브랜드 정상·실패" 행이다. 브랜드 삭제가 끝나면 브랜드와 그 상품이 함께 삭제되고, 중간에 실패하면 모두
 * 처음 상태로 돌아오는지 실제 MySQL에서 확인한다(ADR 0017). 어느 쪽이든 다른 브랜드의 상품과 과거 주문은 그대로다.
 *
 * 준비는 과제가 정한 그대로다. 상품 둘을 가진 브랜드(하나는 재고 0), 다른 브랜드의 상품 하나, 첫 브랜드 상품의 확정된 주문.
 * 모두 포트로 만들어 커밋하고, 삭제 전에 새 트랜잭션에서 읽어 둔 상태를 삭제 뒤 새 트랜잭션에서 다시 읽은 상태와 견준다.
 * 결과를 새 트랜잭션에서 다시 읽어야 하므로 커밋하는 기반을 쓴다.
 *
 * 실패는 저장 단계에 넣는다. [BrandRepository]를 spy해 `save`가 영속성 컨텍스트를 flush한 뒤 던지게 한다. flush가 상품과 브랜드의
 * UPDATE를 MySQL에 실제로 보내므로, 예외는 변경 SQL이 나간 뒤에 난다. 운영 코드는 flush하지 않는다.
 * 저장소는 CGLIB 프록시인 Service와 달리 JDK 프록시이므로 stub은 spy에 바로 건다. 기반의 `prepare`도 이 저장소를 거치므로
 * stub은 준비를 마친 뒤에 건다. stub이 없는 정상 테스트에서 spy는 원래 동작을 부른다.
 */
class BrandRegisterRollbackTest(
    private val brandRegister: BrandRegister,
    private val brandFinder: BrandFinder,
    private val productFinder: ProductFinder,
    private val orderFinder: OrderFinder,
) : BaseCommittingApplicationServiceTest() {
    @MockkSpyBean
    private lateinit var brandRepository: BrandRepository

    /** 삭제하는 브랜드. 브랜드를 둘 준비하므로 기반의 `brand` 필드 대신 읽는다. */
    private lateinit var brandToDelete: Brand

    private lateinit var inStock: Product

    private lateinit var soldOut: Product

    private lateinit var otherBrandProduct: Product

    @Test
    fun `deleting a brand deletes both of its products and leaves another brand's product and a past order as they were`() {
        prepareBrandWithPastOrder()
        val before = inNewTransaction { snapshot() }

        brandRegister.delete(brandToDelete.id)

        inNewTransaction {
            assertThat(entityManager.brandDeletedAt(brandToDelete.id)).isNotNull()
            assertThat(entityManager.productDeletedAt(inStock.id)).isNotNull()
            assertThat(entityManager.productDeletedAt(soldOut.id)).isNotNull()
            assertThat(productFinder.find(otherBrandProduct.id).state()).isEqualTo(before.otherBrandProduct)
            assertThat(orderFinder.find(user.id, order.id).detail()).isEqualTo(before.pastOrder)
        }
    }

    @Test
    fun `a brand delete failing midway leaves the brand, its products, another brand's product and a past order as they were`() {
        prepareBrandWithPastOrder()
        val before = inNewTransaction { snapshot() }
        val failure = DataAccessResourceFailureException("브랜드를 저장하다 실패했다")
        failSavingBrandAfterFlush(failure)

        val exception = assertThrows<DataAccessResourceFailureException> { brandRegister.delete(brandToDelete.id) }

        assertThat(exception).isSameAs(failure)
        inNewTransaction {
            assertThat(snapshot()).isEqualTo(before)
        }
    }

    /**
     * 과제의 준비. 재고 있는 상품과 재고 0인 상품을 가진 브랜드, 다른 브랜드의 상품을 등록한다. 첫 브랜드의 재고 있는 상품을 담은
     * 주문은 그 합계만큼 충전해 확정한다. 포트마다 자기 트랜잭션에서 커밋한다.
     */
    private fun prepareBrandWithPastOrder() {
        brandToDelete = prepareBrand()
        inStock = prepareProduct(brandToDelete)
        soldOut = prepareProduct(brandToDelete, stock = 0)
        otherBrandProduct = prepareProduct(prepareBrand())
        prepareOrder(products = listOf(inStock))
        charge(amount = order.totalAmount.amount)
        confirmOrder()
    }

    /**
     * 삭제 전후로 견주는 상태를 포트로 읽는다. 삭제된 브랜드와 상품은 포트가 읽지 못하므로 삭제 전이나 실패 뒤에만 부른다.
     * 실패 뒤에도 삭제가 남았으면 읽기가 `BRAND_NOT_FOUND`나 `PRODUCT_NOT_FOUND`로 실패한다.
     */
    private fun snapshot(): Snapshot {
        return Snapshot(
            brand = brandFinder.find(brandToDelete.id).state(),
            inStock = productFinder.find(inStock.id).state(),
            soldOut = productFinder.find(soldOut.id).state(),
            otherBrandProduct = productFinder.find(otherBrandProduct.id).state(),
            pastOrder = orderFinder.find(user.id, order.id).detail(),
        )
    }

    private data class Snapshot(
        val brand: BrandState,
        val inStock: ProductState,
        val soldOut: ProductState,
        val otherBrandProduct: ProductState,
        val pastOrder: OrderDetail,
    )

    private data class BrandState(
        val name: String,
        val updatedAt: Instant,
        val deletedAt: Instant?,
    )

    private data class ProductState(
        val name: String,
        val stock: Int,
        val updatedAt: Instant,
        val deletedAt: Instant?,
    )

    /** 과거 주문의 상세. 품목은 주문이 저장한 스냅샷이다(ADR 0002). */
    private data class OrderDetail(
        val status: OrderStatus,
        val items: List<LineItemDetail>,
        val totalAmount: Money,
        val paidAmount: Money?,
        val confirmedAt: Instant?,
    )

    private data class LineItemDetail(
        val productName: String,
        val unitPrice: Money,
        val quantity: Int,
    )

    private fun Brand.state(): BrandState {
        return BrandState(name, updatedAt, deletedAt)
    }

    private fun Product.state(): ProductState {
        return ProductState(name, stock, updatedAt, deletedAt)
    }

    private fun Order.detail(): OrderDetail {
        val itemDetails = items.map { LineItemDetail(it.productName, it.unitPrice, it.quantity) }

        return OrderDetail(status, itemDetails, totalAmount, paidAmount, confirmedAt)
    }

    /** 브랜드의 저장이 그때까지의 변경을 flush해 MySQL에 보낸 뒤 [failure]를 던진다. */
    private fun failSavingBrandAfterFlush(failure: RuntimeException) {
        every { brandRepository.save(any()) } answers {
            entityManager.flush()
            throw failure
        }
    }
}
