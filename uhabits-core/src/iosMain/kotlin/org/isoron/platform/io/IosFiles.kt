/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package org.isoron.platform.io

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.isoron.platform.gui.Image
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.closeFile
import platform.Foundation.dataWithBytes
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.stringByDeletingLastPathComponent
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

private val fm get() = NSFileManager.defaultManager

private fun mkdirsFor(path: String) {
    @Suppress("CAST_NEVER_SUCCEEDS")
    val parent = (path as NSString).stringByDeletingLastPathComponent
    fm.createDirectoryAtPath(parent, true, null, null)
}

private fun readLines(path: String): List<String> {
    val text = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
        ?: throw RuntimeException("Could not read file: $path")
    return text.lines().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
}

private fun ByteArray.toNSData(): NSData =
    usePinned { NSData.dataWithBytes(if (isEmpty()) null else it.addressOf(0), size.toULong()) }

private fun NSData.toByteArray(): ByteArray {
    val result = ByteArray(length.toInt())
    if (result.isNotEmpty()) result.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return result
}

/**
 * Resource files are looked up in [roots] in order, mirroring JavaResourceFile's
 * assets/main then assets/test fallback.
 */
class IosResourceFile(private val roots: List<String>, private val path: String) : ResourceFile {
    private val resolved: String
        get() = roots.map { "$it/$path" }.firstOrNull { fm.fileExistsAtPath(it) } ?: "${roots.first()}/$path"

    override suspend fun exists() = fm.fileExistsAtPath(resolved)

    override suspend fun lines() = readLines(resolved)

    override suspend fun copyTo(dest: UserFile) {
        if (dest.exists()) dest.delete()
        mkdirsFor(dest.pathString)
        if (!fm.copyItemAtPath(resolved, dest.pathString, null)) {
            throw RuntimeException("Could not copy $resolved to ${dest.pathString}")
        }
    }

    // ponytail: decoding PNGs needs a CoreGraphics Image; add with IosCanvas
    override suspend fun toImage(): Image = TODO("IosImage not implemented yet")
}

class IosUserFile(override val pathString: String) : UserFile {
    override suspend fun delete() {
        fm.removeItemAtPath(pathString, null)
    }

    override suspend fun exists() = fm.fileExistsAtPath(pathString)

    override suspend fun lines() = readLines(pathString)

    override suspend fun writeString(content: String) = writeBytes(content.encodeToByteArray())

    override suspend fun writeBytes(bytes: ByteArray) {
        mkdirsFor(pathString)
        if (!bytes.toNSData().writeToFile(pathString, true)) {
            throw RuntimeException("Could not write file: $pathString")
        }
    }

    override suspend fun readBytes(limit: Int): ByteArray {
        val handle = NSFileHandle.fileHandleForReadingAtPath(pathString)
            ?: throw RuntimeException("Could not open file: $pathString")
        try {
            return handle.readDataOfLength(limit.toULong()).toByteArray()
        } finally {
            handle.closeFile()
        }
    }

    override fun resolve(child: String) = IosUserFile("$pathString/$child")

    override suspend fun listFiles(): List<UserFile>? =
        fm.contentsOfDirectoryAtPath(pathString, null)?.map { IosUserFile("$pathString/$it") }

    override suspend fun mkdirs() {
        fm.createDirectoryAtPath(pathString, true, null, null)
    }
}

/**
 * @param resourceRoots directories containing bundled assets (e.g. migrations/09.sql)
 * @param userRoot directory for user data, such as the app's Documents folder
 */
class IosFileOpener(
    private val resourceRoots: List<String>,
    private val userRoot: String
) : FileOpener {
    override fun openResourceFile(path: String): ResourceFile = IosResourceFile(resourceRoots, path)
    override fun openUserFile(path: String): UserFile = IosUserFile("$userRoot/$path")
}
