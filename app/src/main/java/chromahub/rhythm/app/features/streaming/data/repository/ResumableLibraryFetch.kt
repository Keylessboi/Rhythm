/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.repository

import android.util.Log
import chromahub.rhythm.app.features.streaming.data.provider.ProviderSong
import com.google.gson.Gson
import java.io.File

/**
 * Progress of an interrupted full library fetch, persisted after every album page.
 *
 * @param lastModified server library `lastModified` when the fetch started; the checkpoint is
 *        only resumed while the server still reports the same value.
 * @param nextAlbumOffset album-list offset of the first page not fetched yet.
 * @param songs songs fetched so far, in fetch order.
 */
data class LibraryFetchCheckpoint(
    val lastModified: Long,
    val nextAlbumOffset: Int,
    val songs: List<ProviderSong>
)

/**
 * Runs a paged full library fetch so that it survives the app being killed or the sync being
 * cancelled: after a page the songs fetched so far are checkpointed to [checkpointFile], and
 * the next fetch continues from the first page not yet fetched instead of starting over. The
 * checkpoint is discarded when the server library changed in between (different
 * `lastModified`), and deleted once a fetch completes.
 *
 * Each checkpoint rewrites every song fetched so far (tens of MB for a large library), so it is
 * written at most every [minCheckpointIntervalMs]; a kill loses at most that much work.
 */
internal class ResumableLibraryFetch(
    private val checkpointFile: File,
    private val writer: CatalogCacheWriter,
    private val gson: Gson = Gson(),
    private val minCheckpointIntervalMs: Long = 10_000L,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** One paged fetch starting at `startAlbumOffset`, reporting each page to `onPage`. */
    fun interface PagedFetch {
        suspend fun fetch(
            startAlbumOffset: Int,
            limit: Int,
            onProgress: ((current: Int, total: Int, songsCount: Int) -> Unit)?,
            onPage: suspend (pageSongs: List<ProviderSong>, nextAlbumOffset: Int) -> Unit
        ): Result<List<ProviderSong>>
    }

    /**
     * @param lastModified the server's current library `lastModified`, or null if unknown; then
     *        nothing is checkpointed or resumed and this is a plain full fetch.
     */
    suspend fun fetch(
        lastModified: Long?,
        limit: Int,
        onProgress: ((current: Int, total: Int, songsCount: Int) -> Unit)?,
        pagedFetch: PagedFetch
    ): Result<List<ProviderSong>> {
        val checkpoint = lastModified?.let { readCheckpoint()?.takeIf { it.lastModified == lastModified } }
        val fetched = LinkedHashMap<String, ProviderSong>()
        checkpoint?.songs?.forEach { fetched[it.providerId] = it }
        if (checkpoint != null) {
            Log.d(TAG, "Resuming library fetch at album ${checkpoint.nextAlbumOffset} with ${fetched.size} songs")
        } else {
            writer.delete(checkpointFile)
        }
        val resumedCount = fetched.size
        if (resumedCount >= limit) {
            writer.delete(checkpointFile)
            return Result.success(fetched.values.take(limit))
        }

        var lastCheckpointAt = Long.MIN_VALUE
        val result = pagedFetch.fetch(
            startAlbumOffset = checkpoint?.nextAlbumOffset ?: 0,
            limit = limit - resumedCount,
            onProgress = onProgress?.let { report ->
                { current, total, songsCount -> report(current, total, songsCount + resumedCount) }
            }
        ) { pageSongs, nextAlbumOffset ->
            pageSongs.forEach { fetched.putIfAbsent(it.providerId, it) }
            val now = clock()
            if (lastModified != null && (lastCheckpointAt == Long.MIN_VALUE || now - lastCheckpointAt >= minCheckpointIntervalMs)) {
                lastCheckpointAt = now
                val snapshot = LibraryFetchCheckpoint(lastModified, nextAlbumOffset, fetched.values.toList())
                writer.write(checkpointFile, { snapshot }) { checkpoint, out -> gson.toJson(checkpoint, out) }
            }
        }

        // On failure the checkpoint stays, so the next sync continues from the last page.
        return result.map { songs ->
            songs.forEach { fetched.putIfAbsent(it.providerId, it) }
            writer.delete(checkpointFile)
            fetched.values.take(limit)
        }
    }

    private fun readCheckpoint(): LibraryFetchCheckpoint? {
        if (!checkpointFile.exists()) return null
        return try {
            checkpointFile.bufferedReader().use { gson.fromJson(it, LibraryFetchCheckpoint::class.java) }
                // Gson leaves missing fields null despite the Kotlin types; treat that as corrupt.
                ?.takeIf { it.songs.all { song -> song.providerId.isNotEmpty() } && it.nextAlbumOffset > 0 }
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring unreadable library fetch checkpoint", e)
            null
        }
    }

    private companion object {
        private const val TAG = "ResumableLibraryFetch"
    }
}
