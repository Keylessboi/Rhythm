/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.shared.data.repository

import chromahub.rhythm.app.shared.data.repository.PlaybackStatsRepository.PlaybackEvent
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Reads and writes playback_history.json and keeps the parsed events between reads.
 *
 * PlaybackStatsRepository.loadSummary used to read and Gson-parse the whole 90-day file on
 * every call, and the UI calls it often (see HomeScreen / RhythmNavigation). The parsed list is
 * immutable and is reused until the file changes: the repository's own writes drop it, and a change made by
 * anything else (backup restore, clearing app data) is noticed through the file's length and
 * modification time. Not thread-safe by itself; the repository calls it under its file lock.
 */
internal class PlaybackHistoryStore(
    private val file: File,
    private val gson: Gson = Gson()
) {
    private val eventsType = object : TypeToken<MutableList<PlaybackEvent>>() {}.type

    private var cached: List<PlaybackEvent>? = null
    private var cachedLength = -1L
    private var cachedModified = -1L

    /** Number of times the file was actually read and parsed; for tests. */
    internal var parseCount = 0
        private set

    /** The events as an immutable snapshot, shared between callers. Never mutate it. */
    fun snapshot(): List<PlaybackEvent> {
        if (!file.exists()) {
            cached = null
            return emptyList()
        }
        val length = file.length()
        val modified = file.lastModified()
        cached?.let { events ->
            if (length == cachedLength && modified == cachedModified) return events
        }
        // The key is taken before the read, so a change during the read leaves a stale key and the
        // next call parses again instead of trusting content it did not see.
        val events = java.util.Collections.unmodifiableList(parse())
        cached = events
        cachedLength = length
        cachedModified = modified
        return events
    }

    /** A private, mutable copy for read-modify-write callers. */
    fun readMutable(): MutableList<PlaybackEvent> = ArrayList(snapshot())

    fun write(events: List<PlaybackEvent>) {
        // Dropped rather than updated in place: the next read re-parses what is really on disk.
        cached = null
        runCatching { file.writeText(gson.toJson(events)) }
    }

    fun delete() {
        cached = null
        if (file.exists()) file.delete()
    }

    private fun parse(): MutableList<PlaybackEvent> {
        parseCount++
        val raw = runCatching { file.readText() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return mutableListOf()

        return runCatching {
            val element = gson.fromJson(raw, JsonElement::class.java)
            if (element.isJsonArray) {
                gson.fromJson<MutableList<PlaybackEvent>>(element, eventsType)
            } else {
                mutableListOf()
            }
        }.getOrElse { mutableListOf() }
    }
}
