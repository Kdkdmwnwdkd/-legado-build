package com.shimmer.reader.lib.cloud

import com.shimmer.reader.exception.NoStackTraceException

class S3CapacityFullException(
    message: String = "All S3 containers are full",
    val scope: S3ContainerScope = S3ContainerScope.DEFAULT
) : NoStackTraceException(message)

