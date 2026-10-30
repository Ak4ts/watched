package dev.watched.shared

/**
 * Median gap between consecutive timestamps, in millis.
 *
 * Median rather than mean because wearable data is gappy by nature — a watch that was off the
 * wrist for six hours would wreck a mean and tell us nothing about the real sampling rate.
 * Returns null when there are fewer than two points to compare.
 */
fun medianInterval(timestampsMs: List<Long>): Long? {
    if (timestampsMs.size < 2) return null
    val gaps = timestampsMs.sorted().zipWithNext { a, b -> b - a }.sorted()
    val mid = gaps.size / 2
    return if (gaps.size % 2 == 1) gaps[mid] else (gaps[mid - 1] + gaps[mid]) / 2
}
