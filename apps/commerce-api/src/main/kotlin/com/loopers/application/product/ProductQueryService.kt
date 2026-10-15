package com.loopers.application.product

import com.loopers.application.product.provided.ProductAdminListRequest
import com.loopers.application.product.provided.ProductFinder
import com.loopers.application.product.provided.ProductInfo
import com.loopers.application.product.provided.ProductLikedListRequest
import com.loopers.application.product.provided.ProductListInfo
import com.loopers.application.product.provided.ProductListRequest
import com.loopers.application.product.provided.ProductSort
import com.loopers.application.product.required.ProductListRepository
import com.loopers.application.product.required.ProductRepository
import com.loopers.domain.product.Product
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
    override fun find(productId: Long): Product {
        return productRepository.findById(productId) ?: throw CoreException(ErrorType.PRODUCT_NOT_FOUND)
    }

    override fun findOrNull(productId: Long): Product? {
        return productRepository.findById(productId)
    }

    /** 클래스의 `readOnly`는 부르는 쪽의 쓰기 트랜잭션에 참여할 때 걸리지 않는다. */
    override fun findForUpdate(productId: Long): Product {
        return productRepository.findForUpdateById(productId) ?: throw CoreException(ErrorType.PRODUCT_NOT_FOUND)
    }

    /** 클래스의 `readOnly`는 부르는 쪽의 쓰기 트랜잭션에 참여할 때 걸리지 않는다. */
    override fun findForUpdateOrNull(productId: Long): Product? {
        return productRepository.findForUpdateById(productId)
    }

    /** 브랜드 이름을 연관에서 건너 읽으므로 [ProductInfo]로 옮기는 일은 이 트랜잭션 안에서 끝난다. */
    override fun findInfo(productId: Long): ProductInfo {
        return productInfoAssembler.toInfo(find(productId))
    }

    /** 항목마다 브랜드를 읽으므로 [ProductInfo]로 옮기는 일은 이 트랜잭션 안에서 끝난다(설계 5.31). */
    override fun findAll(request: ProductAdminListRequest): Slice<ProductInfo> {
        val products = findSlice(
            brandId = request.brandId,
            page = request.page,
            size = request.size,
            sort = ProductSort.LATEST,
        )

        return productInfoAssembler.toInfos(products)
    }

    /**
     * 정렬 기준을 여기서 옮기는 것은 컨트롤러를 거치지 않는 호출도 같은 검사를 받게 하기 위해서다.
     * 배치나 컨슈머가 [ProductListRequest]를 손으로 만들어 불러도 모르는 철자는 여기서 걸린다.
     * [ProductSort]는 전송 방식을 모르므로 모르는 철자에 null을 돌려주고, 그것이 400이라는 것은 여기서 정한다.
     *
     * 항목마다 브랜드를 읽으므로 [ProductListInfo]로 옮기는 일도 이 트랜잭션 안에서 끝난다.
     */
    override fun findAll(request: ProductListRequest): ProductListInfo {
        val products = findSlice(
            brandId = request.brandId,
            page = request.page,
            size = request.size,
            sort = ProductSort.from(request.sort) ?: throw CoreException(ErrorType.INVALID_SORT),
        )

        return productInfoAssembler.toListInfo(products)
    }

    /** 항목마다 브랜드를 읽으므로 [ProductInfo]로 옮기는 일은 이 트랜잭션 안에서 끝난다(설계 5.31). */
    override fun findAllLikedBy(userId: Long, request: ProductLikedListRequest): Slice<ProductInfo> {
        return productInfoAssembler.toInfos(
            productRepository.findAllLikedBy(userId = userId, pageable = PageRequest.of(request.page, request.size)),
        )
    }

    /** 두 목록이 같은 쿼리로 고른다. 무엇으로 옮길지는 목록마다 다르므로 상품의 조각을 그대로 돌려준다. */
    private fun findSlice(brandId: Long?, page: Int, size: Int, sort: ProductSort): Slice<Product> {
        return productListRepository.findAll(brandId = brandId, pageable = PageRequest.of(page, size), sort = sort)
    }
}
