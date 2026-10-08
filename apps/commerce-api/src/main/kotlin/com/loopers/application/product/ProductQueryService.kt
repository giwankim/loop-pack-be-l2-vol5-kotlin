package com.loopers.application.product

import com.loopers.application.product.provided.ProductAdminListRequest
import com.loopers.application.product.provided.ProductFinder
import com.loopers.application.product.provided.ProductInfo
import com.loopers.application.product.provided.ProductLikedListRequest
import com.loopers.application.product.provided.ProductListRequest
import com.loopers.application.product.required.ProductListRepository
import com.loopers.application.product.required.ProductRepository
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductSort
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ValidatedApplicationService
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Slice

/**
 * [ProductFinder]의 구현. 목록이 Request를 받으므로 검증하는 Service다. 상품 하나는 Spring Data의 [ProductRepository]가,
 * 상품 목록은 QueryDSL을 쓰는 [ProductListRepository]가 읽는다.
 */
@ValidatedApplicationService(readOnly = true)
class ProductQueryService(
    private val productRepository: ProductRepository,
    private val productListRepository: ProductListRepository,
    private val productInfoAssembler: ProductInfoAssembler,
) : ProductFinder {
    /** 삭제된 상품은 없는 상품이므로 저장소가 이미 걸러 주고, 없으면 여기서 거절한다. 브랜드는 읽지 않는다. */
    override fun find(id: Long): Product =
        productRepository.findById(id) ?: throw CoreException(ErrorType.PRODUCT_NOT_FOUND)

    /** 브랜드 이름을 연관에서 건너 읽으므로 [ProductInfo]로 옮기는 일은 이 트랜잭션 안에서 끝난다. */
    override fun findInfo(id: Long): ProductInfo = productInfoAssembler.toInfo(find(id))

    override fun findOrderableOrNull(id: Long): Product? = productRepository.findByIdWithActiveBrand(id)

    override fun findAll(request: ProductAdminListRequest): Slice<ProductInfo> =
        findAll(
            brandId = request.brandId,
            page = request.page,
            size = request.size,
            sort = ProductSort.LATEST,
        )

    /**
     * 정렬 기준을 여기서 옮기는 것은 컨트롤러를 거치지 않는 호출도 같은 검사를 받게 하기 위해서다.
     * 배치나 컨슈머가 [ProductListRequest]를 손으로 만들어 불러도 모르는 철자는 여기서 걸린다.
     * [ProductSort]는 전송 방식을 모르므로 모르는 철자에 null을 돌려주고, 그것이 400이라는 것은 여기서 정한다.
     */
    override fun findAll(request: ProductListRequest): Slice<ProductInfo> =
        findAll(
            brandId = request.brandId,
            page = request.page,
            size = request.size,
            sort = ProductSort.from(request.sort) ?: throw CoreException(ErrorType.INVALID_SORT),
        )

    /** 항목마다 브랜드를 읽으므로 [ProductInfo]로 옮기는 일은 이 트랜잭션 안에서 끝난다(설계 5.31). */
    override fun findAllLikedBy(userId: Long, request: ProductLikedListRequest): Slice<ProductInfo> =
        productInfoAssembler.toInfos(
            productRepository.findAllLikedBy(userId = userId, pageable = PageRequest.of(request.page, request.size)),
        )

    /** 삭제된 상품은 [com.loopers.domain.product.Product]의 `@SQLRestriction`이 걸러 주므로 남은 상품이 있는지만 묻는다. */
    override fun hasActiveProducts(brandId: Long): Boolean = productRepository.existsByBrandId(brandId)

    /** 항목마다 브랜드를 읽으므로 [ProductInfo]로 옮기는 일은 이 트랜잭션 안에서 끝난다(설계 5.31). */
    private fun findAll(brandId: Long?, page: Int, size: Int, sort: ProductSort): Slice<ProductInfo> =
        productInfoAssembler.toInfos(
            productListRepository.findAll(brandId = brandId, pageable = PageRequest.of(page, size), sort = sort),
        )
}
