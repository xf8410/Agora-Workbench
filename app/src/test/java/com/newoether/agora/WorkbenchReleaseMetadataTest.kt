package com.newoether.agora

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkbenchReleaseMetadataTest {
    @Test
    fun releaseVersion_isWorkbench151() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("versionCode = 36"))
        assertTrue(gradle.contains("versionName = \"1.5.1-workbench\""))
    }
}
