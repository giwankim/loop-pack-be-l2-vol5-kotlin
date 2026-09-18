package com.loopers.application.product

import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.product.ProductSort
import com.loopers.domain.product.Stock
import com.loopers.domain.shared.Money
import com.loopers.domain.shared.PageSlice
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.validation.Valid
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Service
@Validated
class ProductService(
    private val productRepository: ProductRepository,
    private val brandRepository: BrandRepository,
    private val productInfoAssembler: ProductInfoAssembler,
) {
    /** 새 상품에는 좋아요가 없다는 불변식으로 0을 넣는다. 세어 볼 관계가 아직 없다(설계 5.7). */
    @Transactional
    fun register(@Valid request: ProductAdminRegisterRequest): ProductInfo {
        val brand = brandRepository.findById(request.brandId) ?: throw CoreException(ErrorType.BRAND_NOT_FOUND)
        val product = Product(
            brand = brand,
            name = request.name,
            price = Money(request.price),
            stock = Stock(request.stock),
        )
        return ProductInfo.from(productRepository.save(product), likeCount = 0)
    }

    @Transactional(readOnly = true)
    fun find(id: Long): ProductInfo = productInfoAssembler.toInfo(findOrThrow(id))

    @Transactional
    fun update(id: Long, @Valid request: ProductAdminUpdateRequest): ProductInfo {
        val product = findOrThrow(id)
        product.update(name = request.name, price = Money(request.price))
        return productInfoAssembler.toInfo(product)
    }

    @Transactional
    fun updateStock(id: Long, @Valid request: ProductAdminStockUpdateRequest): ProductInfo {
        val product = findOrThrow(id)
        product.updateStock(request.quantity)
        return productInfoAssembler.toInfo(product)
    }

    /**
     * 늦게 등록된 상품이 앞서는 한 조각. 관리자 목록은 정렬 기준을 고르지 않는다.
     */
    @Transactional(readOnly = true)
    fun findAll(@Valid request: ProductAdminListRequest): PageSlice<ProductInfo> =
        findAll(
            brandId = request.brandId,
            page = request.page,
            size = request.size,
            sort = ProductSort.LATEST,
        )

    /**
     * 고객이 고른 차례로 놓인 한 조각.
     *
     * 정렬 기준을 여기서 옮기는 것은 컨트롤러를 거치지 않는 호출도 같은 검사를 받게 하기 위해서다.
     * 배치나 컨슈머가 [ProductListRequest]를 손으로 만들어 불러도 모르는 철자는 여기서 걸린다.
     * [ProductSort]는 전송 방식을 모르므로 모르는 철자에 null을 돌려주고, 그것이 400이라는 것은 여기서 정한다.
     */
    @Transactional(readOnly = true)
    fun findAll(@Valid request: ProductListRequest): PageSlice<ProductInfo> =
        findAll(
            brandId = request.brandId,
            page = request.page,
            size = request.size,
            sort = ProductSort.from(request.sort) ?: throw CoreException(ErrorType.INVALID_SORT),
        )

    /** 항목마다 브랜드를 읽으므로 [ProductInfo]로 옮기는 일은 이 트랜잭션 안에서 끝난다(설계 5.31). */
    private fun findAll(brandId: Long?, page: Int, size: Int, sort: ProductSort): PageSlice<ProductInfo> =
        productInfoAssembler.toInfos(
            productRepository.findAll(brandId = brandId, page = page, size = size, sort = sort),
        )

    /** 논리 삭제. 이미 삭제된 상품은 없는 상품이므로 다시 삭제할 수 없다. 남은 좋아요는 그대로 둔다. */
    @Transactional
    fun delete(id: Long) {
        findOrThrow(id).delete()
    }

    /** 삭제된 상품은 없는 상품이므로 저장소가 이미 걸러 주고, 없으면 여기서 거절한다. */
    private fun findOrThrow(id: Long): Product =
        productRepository.findById(id) ?: throw CoreException(ErrorType.PRODUCT_NOT_FOUND)
}
