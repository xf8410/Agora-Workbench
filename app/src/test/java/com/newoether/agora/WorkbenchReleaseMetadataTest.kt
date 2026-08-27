package com.newoether.agora

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkbenchReleaseMetadataTest {
    @Test
    fun releaseMetadata_isParallelInstallableV3() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("applicationId = \"com.newoether.agora.workbench.v3\""))
        assertTrue(gradle.contains("versionCode = 100"))
        assertTrue(gradle.contains("versionName = \"1.5.0-v3\""))
        assertTrue(gradle.contains("signingConfigs.getByName(\"workbenchV3\")"))
    }
}
