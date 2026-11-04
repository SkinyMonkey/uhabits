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

@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package org.isoron.platform.gui

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import org.isoron.platform.io.mkdirsFor
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGBitmapContextGetBytesPerRow
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextClearRect
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.Foundation.writeToFile
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import kotlin.experimental.ExperimentalNativeApi
import kotlin.math.roundToInt
import kotlin.native.ref.createCleaner

/** RGBA bitmap backed by a CoreGraphics context. Row 0 is the top of the image. */
class IosImage(override val width: Int, override val height: Int) : Image {
    val context: CGContextRef = CGColorSpaceCreateDeviceRGB().let { space ->
        val ctx = CGBitmapContextCreate(
            null,
            width.toULong(),
            height.toULong(),
            8u,
            (width * 4).toULong(),
            space,
            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
        ) ?: error("Could not create bitmap context")
        CGColorSpaceRelease(space)
        ctx
    }

    @Suppress("unused")
    private val cleaner = createCleaner(context) { CGContextRelease(it) }

    init {
        CGContextClearRect(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
    }

    private val data: CPointer<UByteVar> = CGBitmapContextGetData(context)!!.reinterpret()
    private val bytesPerRow = CGBitmapContextGetBytesPerRow(context).toInt()

    // Pixels are stored premultiplied; expose them straight, like BufferedImage and ImageData
    override fun getPixel(x: Int, y: Int): Color {
        val i = y * bytesPerRow + x * 4
        val a = data[i + 3].toDouble()
        if (a == 0.0) return Color(0.0, 0.0, 0.0, 0.0)
        return Color(data[i].toDouble() / a, data[i + 1].toDouble() / a, data[i + 2].toDouble() / a, a / 255.0)
    }

    override fun setPixel(x: Int, y: Int, color: Color) {
        val i = y * bytesPerRow + x * 4
        val a = color.alpha
        data[i] = (color.red * a * 255).roundToInt().toUByte()
        data[i + 1] = (color.green * a * 255).roundToInt().toUByte()
        data[i + 2] = (color.blue * a * 255).roundToInt().toUByte()
        data[i + 3] = (a * 255).roundToInt().toUByte()
    }

    override suspend fun export(path: String) {
        val cgImage = CGBitmapContextCreateImage(context) ?: error("Could not create image")
        val png = UIImagePNGRepresentation(UIImage.imageWithCGImage(cgImage))
        CGImageRelease(cgImage)
        mkdirsFor(path)
        if (png?.writeToFile(path, true) != true) throw RuntimeException("Could not write image: $path")
    }

    companion object {
        fun fromPng(path: String): IosImage {
            val cgImage = UIImage.imageWithContentsOfFile(path)?.CGImage
                ?: throw RuntimeException("Could not decode image: $path")
            val w = CGImageGetWidth(cgImage).toInt()
            val h = CGImageGetHeight(cgImage).toInt()
            return IosImage(w, h).also {
                CGContextDrawImage(it.context, CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()), cgImage)
            }
        }
    }
}
