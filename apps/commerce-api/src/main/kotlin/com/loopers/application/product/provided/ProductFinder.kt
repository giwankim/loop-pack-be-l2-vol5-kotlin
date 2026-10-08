package com.loopers.application.product.provided

import com.loopers.application.brand.required.ActiveProductChecker
import com.loopers.domain.product.Product
import jakarta.validation.Valid
import org.springframework.data.domain.Slice

/**
 * 상품 조각이 내주는 읽기. 고객과 관리자의 상품 조회가 부른다. 삭제된 상품은 없는 상품이므로 어느 읽기에도 나오지 않는다.
 * 항목마다 브랜드 이름을 연관에서 건너 읽고 좋아요 수를 따로 세므로 엔티티가 아니라 [ProductInfo]를 돌려준다(설계 5.7).
 * 주문이 담을 상품을 읽는 [findOrderable]만 엔티티를 돌려준다.
 *
 * 브랜드가 선언한 [ActiveProductChecker]에도 답한다. 상품이 브랜드의 물음을 따르므로 브랜드는 상품을 모른다.
 */
interface ProductFinder : ActiveProductChecker {
    /** [id]가 가리키는 상품이 없거나 삭제됐으면 `PRODUCT_NOT_FOUND`를 던진다. */
    fun find(id: Long): ProductInfo

    /**
     * 주문할 수 있는 상품. 상품이 없거나 삭제됐거나 브랜드가 삭제됐으면 null이다(포인트·주문 설계 2).
     * 주문 조각의 생성과 확정이 부른다. 주문은 없음을 정상 결과로 받아 자기 오류로 옮기므로 [find]처럼 던지지 않는다.
     *
     * 주문은 좋아요 수를 쓰지 않으므로, 좋아요 수를 세는 [ProductInfo] 대신 엔티티를 돌려준다.
     * 브랜드는 함께 읽혀 있어 트랜잭션 밖에서 건너도 지연 로딩이 없다.
     */
    fun findOrderable(id: Long): Product?

    /** 관리자 목록. 늦게 등록된 상품이 앞서는 한 조각이고 정렬 기준을 고르지 않는다. 총 개수는 세지 않는다(설계 5.5). */
    fun findAll(@Valid request: ProductAdminListRequest): Slice<ProductInfo>

    /** 고객 목록. 고객이 고른 차례로 놓인 한 조각. 모르는 정렬 기준이면 `INVALID_SORT`를 던진다(설계 5.24). */
    fun findAll(@Valid request: ProductListRequest): Slice<ProductInfo>

    /**
     * [userId] 사용자가 좋아요를 누른 상품 한 조각. 최근에 누른 상품이 앞서고 삭제된 상품은 빠진다.
     * 좋아요 조각의 내 좋아요 목록이 부른다. 항목이 고객 목록의 항목과 같은 [ProductInfo]라 옮기는 일은 상품 안에 남는다(설계 5.31).
     *
     * 좋아요 목록의 입력은 좋아요의 Request이고 상품은 좋아요를 모르므로, 페이지 값은 상품의 [ProductLikedListRequest]로
     * 받아 다른 목록과 같은 길로 다시 검증한다.
     */
    fun findAllLikedBy(userId: Long, @Valid request: ProductLikedListRequest): Slice<ProductInfo>
}
