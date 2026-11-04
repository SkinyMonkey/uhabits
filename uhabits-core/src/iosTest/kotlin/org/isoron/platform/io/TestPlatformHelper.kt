package org.isoron.platform.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import org.isoron.platform.gui.Canvas
import org.isoron.platform.time.IosLocalDateFormatter
import org.isoron.platform.time.LocalDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTemporaryDirectory
import platform.posix.getenv

// Set by the iosSimulatorArm64Test task (SIMCTL_CHILD_UHABITS_ASSETS); simulator shares the host filesystem
@OptIn(ExperimentalForeignApi::class)
private val assetsDir = getenv("UHABITS_ASSETS")?.toKString() ?: error("UHABITS_ASSETS not set")

actual fun createTestFileOpener(): FileOpener =
    IosFileOpener(listOf("$assetsDir/main", "$assetsDir/test"), NSTemporaryDirectory().trimEnd('/'))

actual fun createTestDatabaseOpener(): DatabaseOpener = IosDatabaseOpener()

actual suspend fun createTestDatabaseOpenerSuspend(): DatabaseOpener = IosDatabaseOpener()

// ponytail: canvas tests are excluded on iOS (see build.gradle.kts) until IosCanvas exists
actual fun createTestCanvas(width: Int, height: Int): Canvas = TODO("IosCanvas not implemented yet")

actual fun createTestDateFormatter(): LocalDateFormatter = IosLocalDateFormatter(NSLocale("en_US"))

actual suspend fun ensureFontsLoaded() {}
