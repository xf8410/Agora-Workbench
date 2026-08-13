package com.newoether.agora.tool

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubRepositoryMutationToolProviderTest {
    @Test
    fun `accepts only explicit public and private visibility`() {
        assertEquals("public", requireRepositoryVisibility(" PUBLIC "))
        assertEquals("private", requireRepositoryVisibility("private"))
        assertThrows(IllegalArgumentException::class.java) {
            requireRepositoryVisibility("internal")
        }
    }

    @Test
    fun `visibility patch uses GitHub visibility field`() {
        val patch = repositoryVisibilityPatch("public")
        assertEquals("public", (patch["visibility"] as JsonPrimitive).content)
        assertEquals(setOf("visibility"), patch.keys)
    }
}
