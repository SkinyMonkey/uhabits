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

package org.isoron.platform.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.crc32
import platform.zlib.deflate
import platform.zlib.deflateBound
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

// Minimal zip (DEFLATE via the system zlib). Reads STORE and DEFLATE entries, no zip64 or encryption.

private const val LOCAL_HEADER = 0x04034b50
private const val CENTRAL_HEADER = 0x02014b50
private const val END_OF_CENTRAL_DIR = 0x06054b50
private const val FLAG_UTF8_NAMES = 0x0800
private const val DOS_DATE_1980_01_01 = 0x21

private fun crc(data: ByteArray): Int =
    if (data.isEmpty()) {
        0
    } else {
        data.usePinned { crc32(0u, it.addressOf(0).reinterpret(), data.size.toUInt()).toInt() }
    }

private const val METHOD_STORE = 0
private const val METHOD_DEFLATE = 8
private const val RAW_DEFLATE_WINDOW_BITS = -15

// Points a fresh z_stream at pinned buffers. Callers pass orDummy() arrays since addressOf(0) needs an element.
private fun MemScope.stream(input: Pinned<ByteArray>, inSize: Int, output: Pinned<ByteArray>, outSize: Int): z_stream {
    val strm = alloc<z_stream>()
    strm.zalloc = null
    strm.zfree = null
    strm.opaque = null
    strm.next_in = input.addressOf(0).reinterpret()
    strm.avail_in = inSize.toUInt()
    strm.next_out = output.addressOf(0).reinterpret()
    strm.avail_out = outSize.toUInt()
    return strm
}

private fun ByteArray.orDummy() = if (isEmpty()) ByteArray(1) else this

private fun deflateRaw(data: ByteArray): ByteArray = memScoped {
    val bound = data.size + data.size / 1000 + 64
    val out = ByteArray(bound)
    val written = data.orDummy().usePinned { inp ->
        out.usePinned { o ->
            val strm = stream(inp, data.size, o, bound)
            val init = deflateInit2_(
                strm.ptr,
                Z_DEFAULT_COMPRESSION,
                Z_DEFLATED,
                RAW_DEFLATE_WINDOW_BITS,
                8,
                Z_DEFAULT_STRATEGY,
                ZLIB_VERSION,
                sizeOf<z_stream>().toInt()
            )
            check(init == Z_OK) { "deflateInit2 failed: $init" }
            check(deflateBound(strm.ptr, data.size.toULong()).toInt() <= bound) { "deflate bound too small" }
            val rc = deflate(strm.ptr, Z_FINISH)
            deflateEnd(strm.ptr)
            check(rc == Z_STREAM_END) { "deflate failed: $rc" }
            strm.total_out.toInt()
        }
    }
    out.copyOf(written)
}

private fun inflateRaw(data: ByteArray, size: Int): ByteArray = memScoped {
    val out = ByteArray(size).orDummy()
    data.orDummy().usePinned { inp ->
        out.usePinned { o ->
            val strm = stream(inp, data.size, o, size)
            val init = inflateInit2_(strm.ptr, RAW_DEFLATE_WINDOW_BITS, ZLIB_VERSION, sizeOf<z_stream>().toInt())
            check(init == Z_OK) { "inflateInit2 failed: $init" }
            val rc = inflate(strm.ptr, Z_FINISH)
            inflateEnd(strm.ptr)
            check(rc == Z_STREAM_END) { "inflate failed: $rc" }
        }
    }
    out.copyOf(size)
}

private class Bytes {
    var buf = ByteArray(1024)
    var size = 0

    private fun ensure(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }

    fun u16(v: Int) {
        ensure(2)
        buf[size++] = v.toByte()
        buf[size++] = (v shr 8).toByte()
    }

    fun u32(v: Int) {
        u16(v and 0xffff)
        u16(v ushr 16)
    }

    fun bytes(b: ByteArray) {
        ensure(b.size)
        b.copyInto(buf, size)
        size += b.size
    }
}

private fun ByteArray.u16(at: Int) = (this[at].toInt() and 0xff) or ((this[at + 1].toInt() and 0xff) shl 8)
private fun ByteArray.u32(at: Int) = u16(at) or (u16(at + 2) shl 16)

actual class ZipReader actual constructor(private val bytes: ByteArray) {
    actual suspend fun entries(): List<ZipEntry> {
        val result = mutableListOf<ZipEntry>()
        var pos = 0
        while (pos + 30 <= bytes.size && bytes.u32(pos) == LOCAL_HEADER) {
            val method = bytes.u16(pos + 8)
            val compressedSize = bytes.u32(pos + 18)
            val size = bytes.u32(pos + 22)
            val nameLen = bytes.u16(pos + 26)
            val extraLen = bytes.u16(pos + 28)
            val name = bytes.decodeToString(pos + 30, pos + 30 + nameLen)
            val start = pos + 30 + nameLen + extraLen
            val raw = bytes.copyOfRange(start, start + compressedSize)
            val data = when (method) {
                METHOD_STORE -> raw
                METHOD_DEFLATE -> inflateRaw(raw, size)
                else -> throw IllegalArgumentException("Unsupported zip compression method $method for $name")
            }
            if (!name.endsWith("/")) result.add(ZipEntry(name, data.decodeToString()))
            pos = start + compressedSize
        }
        return result
    }
}

actual class ZipWriter actual constructor() {
    private class Entry(val name: ByteArray, val data: ByteArray, val size: Int, val crc: Int, val offset: Int)

    private val out = Bytes()
    private val entries = mutableListOf<Entry>()

    private fun Bytes.commonHeader(e: Entry) {
        u16(20) // version needed to extract
        u16(FLAG_UTF8_NAMES)
        u16(METHOD_DEFLATE)
        u16(0) // mod time
        u16(DOS_DATE_1980_01_01)
        u32(e.crc)
        u32(e.data.size) // compressed size
        u32(e.size) // uncompressed size
        u16(e.name.size)
        u16(0) // extra field length
    }

    actual fun addEntry(name: String, content: String) {
        val data = content.encodeToByteArray()
        val e = Entry(name.encodeToByteArray(), deflateRaw(data), data.size, crc(data), out.size)
        entries.add(e)
        out.u32(LOCAL_HEADER)
        out.commonHeader(e)
        out.bytes(e.name)
        out.bytes(e.data)
    }

    actual suspend fun toBytes(): ByteArray {
        val result = Bytes()
        result.bytes(out.buf.copyOf(out.size))
        val cdStart = result.size
        for (e in entries) {
            result.u32(CENTRAL_HEADER)
            result.u16(20) // version made by
            result.commonHeader(e)
            result.u16(0) // comment length
            result.u16(0) // disk number
            result.u16(0) // internal attributes
            result.u32(0) // external attributes
            result.u32(e.offset)
            result.bytes(e.name)
        }
        val cdSize = result.size - cdStart
        result.u32(END_OF_CENTRAL_DIR)
        result.u16(0)
        result.u16(0)
        result.u16(entries.size)
        result.u16(entries.size)
        result.u32(cdSize)
        result.u32(cdStart)
        result.u16(0) // comment length
        return result.buf.copyOf(result.size)
    }
}
