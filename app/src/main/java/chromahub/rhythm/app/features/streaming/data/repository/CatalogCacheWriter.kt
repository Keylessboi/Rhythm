/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.io.Writer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes catalog cache files one at a time, each atomically.
 *
 * A sync triggers several saves in quick succession (catalog, playlists, artist images). Without
 * this, they write the same file concurrently and can leave interleaved or truncated JSON, which
 * the next start cannot read. Here, the snapshot is taken inside the lock, so the last save
 * always writes the latest state. It is streamed to a temp file (a large library's JSON is
 * tens of MB, too much to build as one String) that is then moved over the target, so a
 * reader (or a process kill mid-write) never sees a half-written file.
 */
internal class CatalogCacheWriter {

    private val mutex = Mutex()

    /**
     * Takes a [snapshot] and streams it to [file] with [serialize]; writes nothing if the
     * snapshot is null.
     * @return true if the file was written.
     */
    suspend fun <T : Any> write(
        file: File,
        snapshot: () -> T?,
        serialize: (T, Writer) -> Unit
    ): Boolean = mutex.withLock {
        val content = snapshot() ?: return@withLock false
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        try {
            tempFile.bufferedWriter().use { serialize(content, it) }
            moveReplacing(tempFile, file)
        } catch (e: IOException) {
            tempFile.delete()
            throw e
        }
        true
    }

    /** Deletes [file], after any save in progress, so a queued save cannot race with it. */
    suspend fun delete(file: File): Boolean = mutex.withLock { file.delete() }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
