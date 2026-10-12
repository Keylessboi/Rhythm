/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.shared.data.repository

/**
 * The "listened today" minutes shown on Home and used by the Rhythm Guard exposure check.
 * The part already in the history file (dbDurationMs) changes only when a song finishes, so it is
 * loaded once per real change. The live part follows the playing song's position (progress is
 * 0..1 of durationMs) and is added here without touching the disk. Same arithmetic as the
 * previous inline code, so the displayed number is unchanged.
 */
fun liveListeningMinutes(
    dbDurationMs: Long,
    isPlaying: Boolean,
    durationMs: Long,
    progress: Float
): Int {
    val activeSessionDurationMs = if (isPlaying && durationMs > 0) {
        (progress * durationMs).toLong()
    } else {
        0L
    }
    return ((dbDurationMs + activeSessionDurationMs) / 60000L).toInt().coerceAtLeast(0)
}
