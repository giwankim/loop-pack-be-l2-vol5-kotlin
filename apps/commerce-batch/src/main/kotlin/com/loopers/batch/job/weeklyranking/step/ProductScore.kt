package com.loopers.batch.job.weeklyranking.step

/** One product's score in the snapshot being scored. */
data class ProductScore(val productId: Long, val score: Long)
