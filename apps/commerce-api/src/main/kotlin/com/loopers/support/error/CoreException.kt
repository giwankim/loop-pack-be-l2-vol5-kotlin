package com.loopers.support.error

class CoreException(
    val errorType: ErrorType,
) : RuntimeException(errorType.message)
