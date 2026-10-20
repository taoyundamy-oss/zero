package com.zero.v2.core.data.dao

/** SQL aggregates retain unknowns and denominators independently of the detail page limit. */
data class UsageBucket(
    val bucket: Long,
    val requests: Long,
    val input: Long,
    val cached: Long,
    val observedInput: Long,
    val output: Long,
    val knownCache: Long,
    val unknownInput: Long,
    val unfinished: Long,
    val failed: Long,
    val localRequests: Long,
)

data class UsageRoute(val connectionId: String, val model: String)
