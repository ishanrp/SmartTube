package com.liskovsoft.youtubeapi.rss

import android.content.Context
import com.liskovsoft.youtubeapi.app.AppService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object RssRuntimeState {
    private const val PREFS_NAME = "rss_service_state"
    private const val KEY_PARALLEL_REQUESTS = "parallel_requests"
    private const val KEY_BACKOFF_LEVEL = "backoff_level"
    private const val KEY_BACKOFF_UNTIL = "backoff_until"
    private const val KEY_LAST_ISSUE = "last_issue"
    private const val KEY_LAST_ISSUE_AT = "last_issue_at"
    private const val KEY_LAST_SUCCESS = "last_success"
    private const val KEY_FEED_MODE = "feed_mode"
    private const val KEY_429_FAILURES = "http_429_failures"
    private const val KEY_TIMEOUT_FAILURES = "timeout_failures"
    private const val KEY_OTHER_FAILURES = "other_failures"
    private const val KEY_EVENTS = "events"

    private const val DEFAULT_PARALLEL_REQUESTS = 4
    private const val MIN_PARALLEL_REQUESTS = 1
    private const val MAX_PARALLEL_REQUESTS = 8
    private const val DEGRADED_WINDOW_MS = 5 * 60 * 1000L
    private const val EVENT_LIMIT = 30
    private const val EVENT_DELIM = "\u001e"

    private val backoffDurationsMs = longArrayOf(
        60 * 1000L,
        5 * 60 * 1000L,
        15 * 60 * 1000L
    )

    private val prefs by lazy {
        AppService.instance().context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun getParallelRequests(): Int {
        return prefs.getInt(KEY_PARALLEL_REQUESTS, DEFAULT_PARALLEL_REQUESTS)
            .coerceIn(MIN_PARALLEL_REQUESTS, MAX_PARALLEL_REQUESTS)
    }

    @Synchronized
    fun setParallelRequests(value: Int): Int {
        val clamped = value.coerceIn(MIN_PARALLEL_REQUESTS, MAX_PARALLEL_REQUESTS)
        prefs.edit().putInt(KEY_PARALLEL_REQUESTS, clamped).apply()
        addEvent("Parallel requests set to $clamped")
        return clamped
    }

    @Synchronized
    fun canRequest(nowMs: Long = System.currentTimeMillis()): Boolean {
        return nowMs >= prefs.getLong(KEY_BACKOFF_UNTIL, 0)
    }

    @Synchronized
    fun recordSuccess() {
        val now = System.currentTimeMillis()
        val backoffUntil = prefs.getLong(KEY_BACKOFF_UNTIL, 0)
        val backoffLevel = prefs.getInt(KEY_BACKOFF_LEVEL, 0)
        val editor = prefs.edit().putLong(KEY_LAST_SUCCESS, now)

        if (backoffLevel > 0 && now >= backoffUntil) {
            editor.putInt(KEY_BACKOFF_LEVEL, 0)
                .putLong(KEY_BACKOFF_UNTIL, 0)
            addEvent("RSS requests recovered")
        }

        editor.apply()
    }

    @Synchronized
    fun recordHttpFailure(code: Int) {
        val now = System.currentTimeMillis()
        val editor = prefs.edit()
            .putString(KEY_LAST_ISSUE, "HTTP $code")
            .putLong(KEY_LAST_ISSUE_AT, now)

        if (code == 429) {
            editor.putInt(KEY_429_FAILURES, prefs.getInt(KEY_429_FAILURES, 0) + 1)

            val currentUntil = prefs.getLong(KEY_BACKOFF_UNTIL, 0)
            if (now >= currentUntil) {
                val nextLevel = (prefs.getInt(KEY_BACKOFF_LEVEL, 0) + 1)
                    .coerceAtMost(backoffDurationsMs.size)
                val duration = backoffDurationsMs[nextLevel - 1]

                editor.putInt(KEY_BACKOFF_LEVEL, nextLevel)
                    .putLong(KEY_BACKOFF_UNTIL, now + duration)
                addEvent("HTTP 429; backoff " + formatDuration(duration))
            }
        } else {
            editor.putInt(KEY_OTHER_FAILURES, prefs.getInt(KEY_OTHER_FAILURES, 0) + 1)
            addEvent("RSS HTTP $code")
        }

        editor.apply()
    }

    @Synchronized
    fun recordTimeout() {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_LAST_ISSUE, "Timeout")
            .putLong(KEY_LAST_ISSUE_AT, now)
            .putInt(KEY_TIMEOUT_FAILURES, prefs.getInt(KEY_TIMEOUT_FAILURES, 0) + 1)
            .apply()
        addEvent("RSS timeout")
    }

    @Synchronized
    fun recordFailure(label: String) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_LAST_ISSUE, label)
            .putLong(KEY_LAST_ISSUE_AT, now)
            .putInt(KEY_OTHER_FAILURES, prefs.getInt(KEY_OTHER_FAILURES, 0) + 1)
            .apply()
        addEvent(label)
    }

    @Synchronized
    fun recordFeedMode(mode: String) {
        prefs.edit().putString(KEY_FEED_MODE, mode).apply()
    }

    @Synchronized
    fun addEvent(message: String) {
        val events = loadEvents().toMutableList()
        events.add(System.currentTimeMillis().toString() + "|" + message)

        while (events.size > EVENT_LIMIT) {
            events.removeAt(0)
        }

        prefs.edit().putString(KEY_EVENTS, events.joinToString(EVENT_DELIM)).apply()
    }

    @Synchronized
    fun status(nowMs: Long = System.currentTimeMillis()): String {
        val backoffUntil = prefs.getLong(KEY_BACKOFF_UNTIL, 0)
        val level = prefs.getInt(KEY_BACKOFF_LEVEL, 0)
        val lastIssueAt = prefs.getLong(KEY_LAST_ISSUE_AT, 0)

        return when {
            nowMs < backoffUntil -> RssDiagnostics.STATUS_THROTTLED
            level > 0 -> RssDiagnostics.STATUS_RECOVERING
            lastIssueAt > 0 && nowMs - lastIssueAt <= DEGRADED_WINDOW_MS -> RssDiagnostics.STATUS_DEGRADED
            else -> RssDiagnostics.STATUS_NORMAL
        }
    }

    @Synchronized
    fun backoffRemainingMs(nowMs: Long = System.currentTimeMillis()): Long {
        return (prefs.getLong(KEY_BACKOFF_UNTIL, 0) - nowMs).coerceAtLeast(0)
    }

    @Synchronized
    fun backoffLevel(): Int = prefs.getInt(KEY_BACKOFF_LEVEL, 0)

    @Synchronized
    fun lastIssue(): String? = prefs.getString(KEY_LAST_ISSUE, null)

    @Synchronized
    fun lastSuccessfulFetchMs(): Long = prefs.getLong(KEY_LAST_SUCCESS, 0)

    @Synchronized
    fun feedMode(): String = prefs.getString(KEY_FEED_MODE, "None") ?: "None"

    @Synchronized
    fun http429Failures(): Int = prefs.getInt(KEY_429_FAILURES, 0)

    @Synchronized
    fun timeoutFailures(): Int = prefs.getInt(KEY_TIMEOUT_FAILURES, 0)

    @Synchronized
    fun otherFailures(): Int = prefs.getInt(KEY_OTHER_FAILURES, 0)

    @Synchronized
    fun recentEvents(): List<String> {
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)

        return loadEvents().mapNotNull { raw ->
            val split = raw.indexOf('|')
            if (split <= 0) {
                return@mapNotNull null
            }

            val timestamp = raw.substring(0, split).toLongOrNull() ?: return@mapNotNull null
            formatter.format(Date(timestamp)) + "  " + raw.substring(split + 1)
        }.reversed()
    }

    private fun loadEvents(): List<String> {
        val raw = prefs.getString(KEY_EVENTS, null)
        return if (raw.isNullOrEmpty()) emptyList() else raw.split(EVENT_DELIM)
    }

    private fun formatDuration(durationMs: Long): String {
        val minutes = durationMs / 60_000
        return if (minutes > 0) "${minutes}m" else "${durationMs / 1_000}s"
    }
}
