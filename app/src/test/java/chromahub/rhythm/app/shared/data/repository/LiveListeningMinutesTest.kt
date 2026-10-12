/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.shared.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/** The minutes shown must be exactly what the previous inline code produced. */
class LiveListeningMinutesTest {

    /** The previous inline arithmetic, kept verbatim as the reference. */
    private fun old(dbDurationMs: Long, isPlaying: Boolean, durationMs: Long, progress: Float): Int {
        val activeSessionDurationMs = if (isPlaying && durationMs > 0) (progress * durationMs).toLong() else 0L
        val totalMs = dbDurationMs + activeSessionDurationMs
        return (totalMs / 60000L).toInt().coerceAtLeast(0)
    }

    @Test
    fun matchesThePreviousArithmeticAcrossInputs() {
        val dbs = listOf(0L, 59_999L, 60_000L, 600_000L, 7_200_000L)
        val durations = listOf(-1L, 0L, 1L, 215_000L, 3_600_000L)
        val progresses = listOf(0f, 0.001f, 0.25f, 0.5f, 0.999f, 1f)
        for (db in dbs) for (d in durations) for (p in progresses) for (playing in listOf(true, false)) {
            assertEquals("db=$db d=$d p=$p playing=$playing", old(db, playing, d, p), liveListeningMinutes(db, playing, d, p))
        }
    }

    @Test
    fun pausedOrUnknownDurationIgnoresProgress() {
        assertEquals(1, liveListeningMinutes(60_000L, false, 215_000L, 0.9f))
        assertEquals(1, liveListeningMinutes(60_000L, true, 0L, 0.9f))
    }

    @Test
    fun playingAddsTheLivePositionAndCrossesMinuteBoundaries() {
        assertEquals(0, liveListeningMinutes(0L, true, 215_000L, 0.2f))
        assertEquals(1, liveListeningMinutes(0L, true, 215_000L, 0.3f))
        assertEquals(3, liveListeningMinutes(0L, true, 215_000L, 1f))
    }
}
