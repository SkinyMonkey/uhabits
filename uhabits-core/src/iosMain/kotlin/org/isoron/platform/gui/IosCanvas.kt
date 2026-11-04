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

@file:OptIn(ExperimentalForeignApi::class)

package org.isoron.platform.gui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFURLRef
import platform.CoreGraphics.CGContextAddArc
import platform.CoreGraphics.CGContextAddLineToPoint
import platform.CoreGraphics.CGContextAddPath
import platform.CoreGraphics.CGContextClosePath
import platform.CoreGraphics.CGContextFillEllipseInRect
import platform.CoreGraphics.CGContextFillPath
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextMoveToPoint
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextSetLineCap
import platform.CoreGraphics.CGContextSetLineWidth
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGContextSetRGBStrokeColor
import platform.CoreGraphics.CGContextStrokePath
import platform.CoreGraphics.CGContextStrokeRect
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGLineCap
import platform.CoreGraphics.CGPathCreateWithRoundedRect
import platform.CoreGraphics.CGPathRelease
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreText.CTFontManagerRegisterFontsForURL
import platform.CoreText.kCTFontManagerScopeProcess
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.UIKit.NSFontAttributeName
import platform.UIKit.NSForegroundColorAttributeName
import platform.UIKit.UIColor
import platform.UIKit.UIFont
import platform.UIKit.UIGraphicsPopContext
import platform.UIKit.UIGraphicsPushContext
import platform.UIKit.drawAtPoint
import platform.UIKit.sizeWithAttributes
import kotlin.math.PI
import kotlin.math.roundToInt

/** Registers NotoSans-Regular, NotoSans-Bold and FontAwesome from [fontsDir] for this process. */
fun registerFonts(fontsDir: String) {
    for (name in listOf("NotoSans-Regular", "NotoSans-Bold", "FontAwesome")) {
        val url = CFBridgingRetain(NSURL.fileURLWithPath("$fontsDir/$name.ttf"))
        // Fails harmlessly if already registered
        CTFontManagerRegisterFontsForURL(url as CFURLRef, kCTFontManagerScopeProcess, null)
        CFRelease(url)
    }
}

/** Mirrors JavaCanvas: coordinates are in dp, y grows downwards, angles are counterclockwise degrees. */
class IosCanvas(
    val image: IosImage,
    val pixelScale: Double = 2.0
) : Canvas {
    private val ctx = image.context
    private var color = Color.BLACK
    private var fontSize = 12.0
    private var font = Font.REGULAR
    private var textAlign = TextAlign.CENTER

    init {
        CGContextTranslateCTM(ctx, 0.0, image.height.toDouble())
        CGContextScaleCTM(ctx, 1.0, -1.0)
        // BasicStroke default
        CGContextSetLineCap(ctx, CGLineCap.kCGLineCapSquare)
    }

    // ponytail: truncates to whole pixels like JavaCanvas so output matches the JVM fixtures
    private fun toPixel(x: Double): Double = (pixelScale * x).toInt().toDouble()

    private fun toDp(x: Double): Double = x / pixelScale

    override fun toImage(): Image = image

    override fun setColor(color: Color) {
        this.color = color
        CGContextSetRGBFillColor(ctx, color.red, color.green, color.blue, color.alpha)
        CGContextSetRGBStrokeColor(ctx, color.red, color.green, color.blue, color.alpha)
    }

    override fun drawLine(x1: Double, y1: Double, x2: Double, y2: Double) {
        CGContextMoveToPoint(ctx, toPixel(x1), toPixel(y1))
        CGContextAddLineToPoint(ctx, toPixel(x2), toPixel(y2))
        CGContextStrokePath(ctx)
    }

    private fun attributes() = mapOf<Any?, Any?>(
        NSFontAttributeName to uiFont(),
        NSForegroundColorAttributeName to UIColor.colorWithRed(color.red, color.green, color.blue, color.alpha)
    )

    private fun uiFont(): UIFont {
        val name = when (font) {
            Font.REGULAR -> "NotoSans"
            Font.BOLD -> "NotoSans-Bold"
            Font.FONT_AWESOME -> "FontAwesome5Free-Solid"
        }
        return UIFont.fontWithName(name, fontSize * pixelScale) ?: error("Font not registered: $name")
    }

    @Suppress("CAST_NEVER_SUCCEEDS")
    override fun drawText(text: String, x: Double, y: Double) {
        val attrs = attributes()
        val f = attrs[NSFontAttributeName] as UIFont
        val width = (text as NSString).sizeWithAttributes(attrs).useContents { width }.roundToInt()
        // Same as JavaCanvas: logical bounds (ascent + descent + leading) centered on y
        val ascent = f.ascender.roundToInt()
        val height = (f.ascender - f.descender + f.leading).roundToInt()
        val baseline = toPixel(y) + ascent - height / 2
        val left = when (textAlign) {
            TextAlign.CENTER -> toPixel(x) - width / 2
            TextAlign.LEFT -> toPixel(x)
            TextAlign.RIGHT -> toPixel(x) - width
        }
        UIGraphicsPushContext(ctx)
        (text as NSString).drawAtPoint(CGPointMake(left, baseline - f.ascender), attrs)
        UIGraphicsPopContext()
    }

    override fun fillRect(x: Double, y: Double, width: Double, height: Double) {
        CGContextFillRect(ctx, CGRectMake(toPixel(x), toPixel(y), toPixel(width), toPixel(height)))
    }

    override fun fillRoundRect(x: Double, y: Double, width: Double, height: Double, cornerRadius: Double) {
        // RoundRectangle2D takes the arc diameter, so the radius is half of it
        val w = toPixel(width)
        val h = toPixel(height)
        val r = minOf(toPixel(cornerRadius) / 2, w / 2, h / 2)
        val path = CGPathCreateWithRoundedRect(CGRectMake(toPixel(x), toPixel(y), w, h), r, r, null)
        CGContextAddPath(ctx, path)
        CGContextFillPath(ctx)
        CGPathRelease(path)
    }

    override fun drawRect(x: Double, y: Double, width: Double, height: Double) {
        CGContextStrokeRect(ctx, CGRectMake(toPixel(x), toPixel(y), toPixel(width), toPixel(height)))
    }

    override fun getHeight(): Double = toDp(image.height.toDouble())

    override fun getWidth(): Double = toDp(image.width.toDouble())

    override fun setFont(font: Font) {
        this.font = font
    }

    override fun setFontSize(size: Double) {
        fontSize = size
    }

    override fun setStrokeWidth(size: Double) {
        CGContextSetLineWidth(ctx, size * pixelScale)
    }

    override fun fillCircle(centerX: Double, centerY: Double, radius: Double) {
        CGContextFillEllipseInRect(
            ctx,
            CGRectMake(toPixel(centerX - radius), toPixel(centerY - radius), toPixel(radius * 2), toPixel(radius * 2))
        )
    }

    override fun fillArc(centerX: Double, centerY: Double, radius: Double, startAngle: Double, swipeAngle: Double) {
        // y points down, so counterclockwise on screen means decreasing angles
        val r = toPixel(radius * 2) / 2
        val cx = toPixel(centerX - radius) + r
        val cy = toPixel(centerY - radius) + r
        val start = -startAngle.roundToInt() * PI / 180
        val end = -(startAngle.roundToInt() + swipeAngle.roundToInt()) * PI / 180
        CGContextMoveToPoint(ctx, cx, cy)
        CGContextAddArc(ctx, cx, cy, r, start, end, if (swipeAngle > 0) 1 else 0)
        CGContextClosePath(ctx)
        CGContextFillPath(ctx)
    }

    override fun setTextAlign(align: TextAlign) {
        this.textAlign = align
    }

    @Suppress("CAST_NEVER_SUCCEEDS")
    override fun measureText(text: String): Double =
        toDp((text as NSString).sizeWithAttributes(attributes()).useContents { width })
}
