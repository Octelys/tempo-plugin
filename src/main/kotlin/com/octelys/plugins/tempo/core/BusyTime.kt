package com.octelys.plugins.tempo.core

/**
 * A span already logged in Tempo by something other than this plugin, such as a meeting. Tracked
 * time inside it is not logged again. Times are epoch milliseconds.
 */
data class BusySpan(val startMillis: Long, val endMillis: Long)

/** The parts of a session outside every busy span, in order. */
internal fun WorkSession.outside(busy: List<BusySpan>): List<LongRange> {
    var cursor = startMillis
    val free = mutableListOf<LongRange>()
    busy
        .filter { it.endMillis > startMillis && it.startMillis < endMillis }
        .sortedBy { it.startMillis }
        .forEach { span ->
            if (span.startMillis > cursor) free += cursor until span.startMillis
            cursor = maxOf(cursor, span.endMillis)
        }
    if (endMillis > cursor) free += cursor until endMillis
    return free
}

internal val LongRange.lengthMillis: Long get() = last + 1 - first
