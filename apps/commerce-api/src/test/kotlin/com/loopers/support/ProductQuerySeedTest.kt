package com.loopers.support

import com.loopers.domain.brand.Brand
import com.loopers.domain.product.Product
import com.loopers.domain.shared.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProductQuerySeedTest {
    @Test
    fun `the first 10,000 products match the assignment's totals, with brand 7 holding 100 of them`() {
        val catalog = ProductQuerySeed.products.filter { it.id <= 10_000 }
        val brand7 = catalog.filter { it.brandId == 7L }

        assertThat(catalog).hasSize(10_000)
        assertThat(catalog.sumOf { it.price }).isEqualTo(472_685_000L)
        assertThat(brand7).hasSize(100)
        assertThat(brand7.sumOf { it.price }).isEqualTo(4_647_200L)
    }

    @Test
    fun `brands 99 and 100 are the deleted brands, and every product they own is deleted with them`() {
        val deletedBrandIds = ProductQuerySeed.brands.filter { it.deletedAt != null }.map { it.id }
        val owned = ProductQuerySeed.products.filter { it.brandId in deletedBrandIds }

        assertThat(deletedBrandIds).containsExactly(99L, 100L)
        assertThat(owned).isNotEmpty()
        assertThat(owned).allSatisfy { assertThat(it.deletedAt).isNotNull() }
    }

    @Test
    fun `brand 101 is a live brand whose 2,000 products share 20 prices`() {
        val large = ProductQuerySeed.products.filter { it.brandId == 101L }

        assertThat(ProductQuerySeed.brands.single { it.id == 101L }.deletedAt).isNull()
        assertThat(large).hasSize(2_000)
        assertThat(large.map { it.price }.distinct()).hasSize(20)
    }

    @Test
    fun `every seeded brand and product passes its entity's checks, and some products are sold out`() {
        val brands = ProductQuerySeed.brands.associate { it.id to Brand(it.name) }

        ProductQuerySeed.products.forEach { Product(brands.getValue(it.brandId), it.name, Money(it.price), it.stock) }

        assertThat(ProductQuerySeed.products.filter { it.stock == 0 }).isNotEmpty()
    }

    @Test
    fun `likes come from the 1,000 seeded users and no user likes the same product twice`() {
        val userIds = ProductQuerySeed.users.map { it.id }.toSet()
        val pairs = ProductQuerySeed.likes.map { it.userId to it.productId }

        assertThat(userIds).hasSize(1_000)
        assertThat(ProductQuerySeed.likes).allSatisfy { assertThat(it.userId).isIn(userIds) }
        assertThat(pairs).doesNotHaveDuplicates()
    }

    @Test
    fun `every product's like count follows its rule, at most 1,000, with zeros, ties and popular products present`() {
        val likeCounts = ProductQuerySeed.likes.groupingBy { it.productId }.eachCount()
        val counts = ProductQuerySeed.products.associate { it.id to (likeCounts[it.id] ?: 0) }

        assertThat(counts).allSatisfy { i, count ->
            when {
                i % 4 == 0L -> assertThat(count).isZero()
                i % 97 == 0L -> assertThat(count).isBetween(300, 900)
                else -> assertThat(count).isEqualTo((i % 17).toInt())
            }
        }
        assertThat(counts.values.max()).isLessThanOrEqualTo(1_000)
        assertThat(counts.values).contains(0)
        assertThat(counts.values.filter { it > 0 }.groupingBy { it }.eachCount().values.max()).isGreaterThan(1)
        assertThat(counts.values.filter { it >= 300 }).isNotEmpty()
    }

    @Test
    fun `the seed holds 126,955 likes, 55,700 of them on the 93 popular products`() {
        val popularLikes = ProductQuerySeed.likes.filter { it.productId % 97 == 0L }
        val popularProducts = popularLikes.map { it.productId }.distinct()

        assertThat(ProductQuerySeed.likes).hasSize(126_955)
        assertThat(popularProducts).hasSize(93)
        assertThat(popularLikes).hasSize(55_700)
    }
}
