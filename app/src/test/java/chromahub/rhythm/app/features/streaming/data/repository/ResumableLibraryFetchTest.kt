/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.repository

import chromahub.rhythm.app.features.streaming.data.provider.ProviderSong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections

class ResumableLibraryFetchTest {

    private lateinit var dir: File
    private lateinit var checkpointFile: File
    private val writer = CatalogCacheWriter()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("library-fetch").toFile()
        checkpointFile = File(dir, "streaming_catalog_subsonic.partial.json")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /**
     * Pretends to be the server's album list: [PAGES] pages of [PAGE_SIZE] albums with one song
     * each, fetched page by page like SubsonicApiClient.fetchLibrarySongs. Records the pages it
     * fetched; after [stallAfterPages] pages it hangs until cancelled (the app being killed).
     */
    private class FakeServer(private val stallAfterPages: Int = Int.MAX_VALUE) : ResumableLibraryFetch.PagedFetch {
        val fetchedPages: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val pagesCheckpointed = CompletableDeferred<Unit>()
        var startOffsets = mutableListOf<Int>()

        override suspend fun fetch(
            startAlbumOffset: Int,
            limit: Int,
            onProgress: ((current: Int, total: Int, songsCount: Int) -> Unit)?,
            onPage: suspend (pageSongs: List<ProviderSong>, nextAlbumOffset: Int) -> Unit
        ): Result<List<ProviderSong>> {
            startOffsets.add(startAlbumOffset)
            val songs = mutableListOf<ProviderSong>()
            var offset = startAlbumOffset
            var pagesThisRun = 0
            while (offset < PAGES * PAGE_SIZE && songs.size < limit) {
                if (pagesThisRun == stallAfterPages) {
                    pagesCheckpointed.complete(Unit)
                    awaitCancellation()
                }
                val page = (offset until offset + PAGE_SIZE).map { song(it) }
                fetchedPages.add(offset / PAGE_SIZE)
                songs += page
                offset += PAGE_SIZE
                pagesThisRun++
                onPage(page, offset)
            }
            return Result.success(songs.take(limit))
        }
    }

    /** Checkpoints after every page, so the tests see each one. */
    private fun fetcher(intervalMs: Long = 0L, clock: () -> Long = System::currentTimeMillis) =
        ResumableLibraryFetch(checkpointFile, writer, minCheckpointIntervalMs = intervalMs, clock = clock)

    @Test
    fun killedFetchResumesWithoutRefetchingCompletedPages() = runBlocking {
        // First run: two pages are fetched and checkpointed, then the app is killed.
        val first = FakeServer(stallAfterPages = 2)
        val job = launch(Dispatchers.IO) {
            fetcher().fetch(LAST_MODIFIED, LIMIT, null, first)
        }
        first.pagesCheckpointed.await()
        job.cancelAndJoin()
        assertEquals(listOf(0, 1), first.fetchedPages)
        assertTrue(checkpointFile.exists())

        // Restart: a new instance continues at page 2 and returns the whole library.
        val second = FakeServer()
        val songs = fetcher()
            .fetch(LAST_MODIFIED, LIMIT, null, second)
            .getOrThrow()

        assertEquals(listOf(2 * PAGE_SIZE), second.startOffsets)
        assertEquals(listOf(2, 3, 4), second.fetchedPages)
        assertEquals((0 until PAGES * PAGE_SIZE).map { song(it) }, songs)
        assertFalse("checkpoint must be deleted after a complete fetch", checkpointFile.exists())
    }

    @Test
    fun changedServerLibraryDiscardsCheckpointAndFetchesEverything() = runBlocking {
        val first = FakeServer(stallAfterPages = 2)
        val job = launch(Dispatchers.IO) {
            fetcher().fetch(LAST_MODIFIED, LIMIT, null, first)
        }
        first.pagesCheckpointed.await()
        job.cancelAndJoin()

        val second = FakeServer()
        val songs = fetcher()
            .fetch(LAST_MODIFIED + 1, LIMIT, null, second)
            .getOrThrow()

        assertEquals(listOf(0), second.startOffsets)
        assertEquals(listOf(0, 1, 2, 3, 4), second.fetchedPages)
        assertEquals(PAGES * PAGE_SIZE, songs.size)
    }

    @Test
    fun failedFetchKeepsCheckpointForTheNextSync() = runBlocking {
        val failing = ResumableLibraryFetch.PagedFetch { start, _, _, onPage ->
            onPage((start until start + PAGE_SIZE).map { song(it) }, start + PAGE_SIZE)
            Result.failure(java.io.IOException("connection lost"))
        }

        val result = fetcher().fetch(LAST_MODIFIED, LIMIT, null, failing)

        assertTrue(result.isFailure)
        assertTrue(checkpointFile.exists())
    }

    @Test
    fun unknownLastModifiedNeitherCheckpointsNorResumes() = runBlocking {
        val server = FakeServer()
        val songs = fetcher()
            .fetch(null, LIMIT, null, server)
            .getOrThrow()

        assertEquals(PAGES * PAGE_SIZE, songs.size)
        assertFalse(checkpointFile.exists())
    }

    @Test
    fun resumedSongsCountTowardsTheLimitAndProgress() = runBlocking {
        val first = FakeServer(stallAfterPages = 1)
        val job = launch(Dispatchers.IO) {
            fetcher().fetch(LAST_MODIFIED, LIMIT, null, first)
        }
        first.pagesCheckpointed.await()
        job.cancelAndJoin()

        var lastReportedSongs = 0
        val second = object : ResumableLibraryFetch.PagedFetch {
            override suspend fun fetch(
                startAlbumOffset: Int,
                limit: Int,
                onProgress: ((current: Int, total: Int, songsCount: Int) -> Unit)?,
                onPage: suspend (pageSongs: List<ProviderSong>, nextAlbumOffset: Int) -> Unit
            ): Result<List<ProviderSong>> {
                assertEquals(150 - PAGE_SIZE, limit)
                val page = (startAlbumOffset until startAlbumOffset + limit).map { song(it) }
                onProgress?.invoke(1, 1, page.size)
                return Result.success(page)
            }
        }
        val songs = fetcher()
            .fetch(LAST_MODIFIED, 150, { _, _, count -> lastReportedSongs = count }, second)
            .getOrThrow()

        assertEquals(150, songs.size)
        assertEquals(150, lastReportedSongs)
    }

    @Test
    fun checkpointsAreThrottled() = runBlocking {
        var now = 0L
        var writes = 0
        val server = ResumableLibraryFetch.PagedFetch { start, _, _, onPage ->
            for (page in 0 until PAGES) {
                onPage((page * PAGE_SIZE until (page + 1) * PAGE_SIZE).map { song(it) }, (page + 1) * PAGE_SIZE)
                if (checkpointFile.exists()) {
                    writes++
                    checkpointFile.delete()
                }
                now += 4_000L // each page takes 4 s
            }
            Result.success(emptyList())
        }

        fetcher(intervalMs = 10_000L, clock = { now }).fetch(LAST_MODIFIED, LIMIT, null, server)

        // Pages at 0, 4, 8, 12, 16 s: checkpoints at 0 and 12 s only.
        assertEquals(2, writes)
    }

    private companion object {
        const val PAGES = 5
        const val PAGE_SIZE = 100
        const val LIMIT = 5_000
        const val LAST_MODIFIED = 1_700_000_000_000L

        fun song(index: Int) = ProviderSong(
            providerId = "song-$index",
            title = "Song $index",
            artist = "Artist",
            album = "Album $index",
            durationMs = 180_000L,
            albumId = "album-$index"
        )
    }
}
