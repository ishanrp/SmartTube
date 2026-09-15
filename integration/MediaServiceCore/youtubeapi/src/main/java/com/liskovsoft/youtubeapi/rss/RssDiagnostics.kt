package com.liskovsoft.youtubeapi.rss

data class RssDiagnostics(
    val status: String,
    val lastIssue: String?,
    val backoffRemainingMs: Long,
    val backoffLevel: Int,
    val parallelRequests: Int,
    val activeRequests: Int,
    val queuedRequests: Int,
    val lastSuccessfulFetchMs: Long,
    val feedMode: String,
    val memoryEntries: Int,
    val diskEntries: Int,
    val freshEntries: Int,
    val staleEntries: Int,
    val veryStaleEntries: Int,
    val diskBytes: Long,
    val http429Failures: Int,
    val timeoutFailures: Int,
    val otherFailures: Int,
    val recentEvents: List<String>
) {
    companion object {
        const val STATUS_NORMAL = "NORMAL"
        const val STATUS_DEGRADED = "DEGRADED"
        const val STATUS_THROTTLED = "THROTTLED"
        const val STATUS_RECOVERING = "RECOVERING"
    }
}
