/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.shared.data.repository

import chromahub.rhythm.app.shared.data.repository.PlaybackStatsRepository.PlaybackEvent
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory

/**
 * playback_history.json is read by every loadSummary call. These tests pin that the parsed events
 * are reused until the file really changes, that our own writes and external changes are seen, and
 * how much garbage repeated reads make. Figures are printed; assertions are ceilings with margin.
 */
class PlaybackHistoryStoreTest {

    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("playback-history-test").toFile()
        file = File(dir, "playback_history.json")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun event(i: Int, base: Long = 1_700_000_000_000L) = PlaybackEvent(
        songId = "song-$i",
        timestamp = base + i * 240_000L,
        durationMs = 215_000L,
        startTimestamp = base + i * 240_000L - 215_000L,
        endTimestamp = base + i * 240_000L,
        songTitle = "Song title $i",
        artistName = "Artist ${i / 10}",
        albumName = "Album ${i / 10}",
        genre = "Hip-Hop"
    )

    private fun events(n: Int) = List(n) { event(it) }

    private fun writeExternally(events: List<PlaybackEvent>, modified: Long? = null) {
        file.writeText(Gson().toJson(events))
        if (modified != null) file.setLastModified(modified)
    }

    private fun allocatedBytes(block: () -> Unit): Long {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        block()
        return bean.getThreadAllocatedBytes(id) - before
    }

    @Test
    fun repeatedReadsParseOnceAndShareTheSnapshot() {
        writeExternally(events(300))
        val store = PlaybackHistoryStore(file)
        val first = store.snapshot()
        repeat(50) { assertSame(first, store.snapshot()) }
        assertEquals(1, store.parseCount)
        assertEquals(events(300), first)
    }

    @Test
    fun missingBlankAndGarbageFilesReadAsEmptyLikeBefore() {
        val store = PlaybackHistoryStore(file)
        assertTrue(store.snapshot().isEmpty())
        file.writeText("   ")
        assertTrue(store.snapshot().isEmpty())
        file.writeText("{\"not\":\"an array\"}")
        assertTrue(store.snapshot().isEmpty())
        file.writeText("[{\"songId\": ")
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun theMutableCopyIsPrivateAndTheSnapshotUnaffected() {
        writeExternally(events(5))
        val store = PlaybackHistoryStore(file)
        val snapshot = store.snapshot()
        val copy = store.readMutable()
        copy.removeAll { true }
        copy += event(99)
        assertEquals(events(5), store.snapshot())
        assertSame(snapshot, store.snapshot())
        assertNotSame(snapshot, copy)
    }

    @Test
    fun ownWriteInvalidatesTheCache() {
        writeExternally(events(5))
        val store = PlaybackHistoryStore(file)
        assertEquals(5, store.snapshot().size)
        val updated = store.readMutable().also { it += event(5) }
        store.write(updated)
        val afterWrite = store.snapshot()
        assertEquals(updated, afterWrite)
        assertEquals(2, store.parseCount)
        // Reads after that are cached again.
        assertSame(afterWrite, store.snapshot())
        assertEquals(2, store.parseCount)
        // The file really holds what was written.
        assertEquals(updated, PlaybackHistoryStore(file).snapshot())
    }

    @Test
    fun deleteInvalidatesTheCache() {
        writeExternally(events(5))
        val store = PlaybackHistoryStore(file)
        assertEquals(5, store.snapshot().size)
        store.delete()
        assertTrue(store.snapshot().isEmpty())
        assertTrue(!file.exists())
    }

    @Test
    fun anExternalChangeOfLengthIsSeen() {
        // A backup restore writes the file behind the repository's back.
        writeExternally(events(5))
        val store = PlaybackHistoryStore(file)
        assertEquals(5, store.snapshot().size)
        writeExternally(events(8))
        assertEquals(events(8), store.snapshot())
        assertEquals(2, store.parseCount)
    }

    @Test
    fun anExternalChangeOfSameLengthButNewerTimestampIsSeen() {
        writeExternally(events(5), modified = 1_000_000_000_000L)
        val store = PlaybackHistoryStore(file)
        val before = store.snapshot()
        // Same size content (only digits differ), later modification time.
        val changed = events(5).map { it.copy(durationMs = it.durationMs + 1) }
        writeExternally(changed, modified = 1_000_000_005_000L)
        assertEquals(file.length(), Gson().toJson(events(5)).length.toLong())
        val after = store.snapshot()
        assertNotSame(before, after)
        assertEquals(changed, after)
    }

    @Test
    fun anExternalDeleteIsSeen() {
        writeExternally(events(5))
        val store = PlaybackHistoryStore(file)
        assertEquals(5, store.snapshot().size)
        file.delete()
        assertTrue(store.snapshot().isEmpty())
        writeExternally(events(2))
        assertEquals(2, store.snapshot().size)
    }

    @Test
    fun repeatedLoadSummaryReadsAllocateFarLessThanReparsingEveryTime() {
        val n = 1000
        val calls = 100
        writeExternally(events(n))
        // Before: every read built a new reader, i.e. read and parse the file (a fresh store has
        // no cache, which is exactly the old behaviour).
        repeat(3) { PlaybackHistoryStore(file).snapshot() }
        val uncached = allocatedBytes { repeat(calls) { PlaybackHistoryStore(file).snapshot() } }

        val store = PlaybackHistoryStore(file)
        store.snapshot()
        repeat(3) { store.snapshot() }
        val cached = allocatedBytes { repeat(calls) { store.snapshot() } }

        println(
            "playback history, $n events, $calls reads: before ${uncached / 1_000} kB total " +
                "(${uncached / calls / 1_000} kB/read), after ${cached / 1_000} kB total " +
                "(${cached / calls} B/read); file ${file.length() / 1_000} kB"
        )
        assertTrue("uncached reads should be the expensive path, got ${uncached / calls} B/read", uncached / calls > 200_000)
        // The cached path is a stat() and a compare: well under 2 kB per read (File.length and
        // lastModified allocate a little path state on some JVMs).
        assertTrue("cached read allocated ${cached / calls} B/read", cached / calls < 2_000)
        assertTrue("cached ($cached) should be under 1% of uncached ($uncached)", cached * 100 < uncached)
    }
}
