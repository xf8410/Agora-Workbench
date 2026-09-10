package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FavoriteSitesStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("favsites").toFile()

    @Test
    fun `first read seeds built-in defaults`() {
        val store = FavoriteSitesStore(tempDir())
        val items = store.list()
        assertEquals(FavoriteSitesStore.DEFAULT_SITES.size, items.size)
        assertTrue(items.all { it.url.startsWith("https://") })
        assertTrue(items.any { it.category == SiteCategory.MINE })
        assertTrue(items.any { it.category == SiteCategory.EXTERNAL })
    }

    @Test
    fun `seed persists across instances and user edits survive`() {
        val dir = tempDir()
        val first = FavoriteSitesStore(dir)
        val seeded = first.list()
        val added = first.add("My tool", "https://example.com/tool", SiteCategory.OTHER, "daily use")
        val second = FavoriteSitesStore(dir)
        val items = second.list()
        assertEquals(seeded.size + 1, items.size)
        assertTrue(items.any { it.id == added.id && it.url == "https://example.com/tool" })
        // Update + remove round-trip.
        second.update(added.id, title = "Renamed")
        assertEquals("Renamed", FavoriteSitesStore(dir).list().first { it.id == added.id }.title)
        assertTrue(second.remove(added.id))
        assertFalse(FavoriteSitesStore(dir).list().any { it.id == added.id })
        // Deleting a seeded entry must not bring it back on next read (no re-seed).
        val seedId = seeded.first().id
        assertTrue(second.remove(seedId))
        assertFalse(FavoriteSitesStore(dir).list().any { it.id == seedId })
    }

    @Test
    fun `corrupt file degrades to empty list without re-seeding`() {
        val dir = tempDir()
        FavoriteSitesStore(dir).list() // seed
        File(dir, "favorite_sites.json").writeText("{not json")
        assertTrue(FavoriteSitesStore(dir).list().isEmpty())
    }
}
