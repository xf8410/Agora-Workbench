package com.newoether.agora

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkbenchReleaseMetadataTest {
    @Test
    fun releaseVersion_isWorkbench147() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("versionCode = 35"))
        assertTrue(gradle.contains("versionName = \"1.4.7-workbench\""))
    }
}
