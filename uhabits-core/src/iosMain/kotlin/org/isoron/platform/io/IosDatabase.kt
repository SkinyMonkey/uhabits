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

package org.isoron.platform.io

import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import sqlite3.SQLITE_DONE
import sqlite3.SQLITE_NULL
import sqlite3.SQLITE_OK
import sqlite3.SQLITE_ROW
import sqlite3.sqlite3_bind_double
import sqlite3.sqlite3_bind_int
import sqlite3.sqlite3_bind_int64
import sqlite3.sqlite3_bind_null
import sqlite3.sqlite3_bind_text
import sqlite3.sqlite3_clear_bindings
import sqlite3.sqlite3_close
import sqlite3.sqlite3_column_double
import sqlite3.sqlite3_column_int
import sqlite3.sqlite3_column_int64
import sqlite3.sqlite3_column_text
import sqlite3.sqlite3_column_type
import sqlite3.sqlite3_destructor_type
import sqlite3.sqlite3_errmsg
import sqlite3.sqlite3_finalize
import sqlite3.sqlite3_open
import sqlite3.sqlite3_prepare_v2
import sqlite3.sqlite3_reset
import sqlite3.sqlite3_step

@OptIn(ExperimentalForeignApi::class)
class IosPreparedStatement(
    private val db: CPointer<sqlite3>,
    sql: String
) : PreparedStatement {
    private val stmt: CPointer<sqlite3_stmt> = memScoped {
        val out = alloc<CPointerVar<sqlite3_stmt>>()
        check(db, sqlite3_prepare_v2(db, sql, -1, out.ptr, null))
        out.value ?: error("sqlite3_prepare_v2 returned no statement for: $sql")
    }

    override fun step(): StepResult = when (val rc = sqlite3_step(stmt)) {
        SQLITE_ROW -> StepResult.ROW
        SQLITE_DONE -> StepResult.DONE
        else -> throw RuntimeException("sqlite3_step failed ($rc): ${errorMessage(db)}")
    }

    // SQLite columns are 0-indexed, bindings are 1-indexed (same as the PreparedStatement contract)
    override fun getInt(index: Int): Int = sqlite3_column_int(stmt, index)
    override fun getLong(index: Int): Long = sqlite3_column_int64(stmt, index)
    override fun getReal(index: Int): Double = sqlite3_column_double(stmt, index)
    override fun getText(index: Int): String =
        sqlite3_column_text(stmt, index)?.reinterpret<ByteVar>()?.toKString() ?: ""

    private fun isNull(index: Int) = sqlite3_column_type(stmt, index) == SQLITE_NULL
    override fun getIntOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)
    override fun getLongOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
    override fun getRealOrNull(index: Int): Double? = if (isNull(index)) null else getReal(index)
    override fun getTextOrNull(index: Int): String? = if (isNull(index)) null else getText(index)

    override fun bindInt(index: Int, value: Int) = check(db, sqlite3_bind_int(stmt, index, value))
    override fun bindLong(index: Int, value: Long) = check(db, sqlite3_bind_int64(stmt, index, value))
    override fun bindReal(index: Int, value: Double) = check(db, sqlite3_bind_double(stmt, index, value))
    override fun bindText(index: Int, value: String) =
        check(db, sqlite3_bind_text(stmt, index, value, -1, SQLITE_TRANSIENT))
    override fun bindNull(index: Int) = check(db, sqlite3_bind_null(stmt, index))

    override fun reset() {
        sqlite3_reset(stmt)
        sqlite3_clear_bindings(stmt)
    }

    override fun finalize() {
        sqlite3_finalize(stmt)
    }
}

@OptIn(ExperimentalForeignApi::class)
class IosDatabase(private val db: CPointer<sqlite3>) : Database {
    override fun prepareStatement(sql: String): PreparedStatement = IosPreparedStatement(db, sql)

    override fun close() {
        sqlite3_close(db)
    }
}

@OptIn(ExperimentalForeignApi::class)
class IosDatabaseOpener : DatabaseOpener {
    override fun open(path: String): Database = memScoped {
        val out = alloc<CPointerVar<sqlite3>>()
        val rc = sqlite3_open(path, out.ptr)
        val db = out.value ?: error("sqlite3_open returned no handle for $path")
        if (rc != SQLITE_OK) {
            val msg = errorMessage(db)
            sqlite3_close(db)
            throw RuntimeException("Could not open database $path: $msg")
        }
        IosDatabase(db)
    }
}

// SQLITE_TRANSIENT is a C cast macro ((sqlite3_destructor_type)-1), which cinterop cannot import
@OptIn(ExperimentalForeignApi::class)
private val SQLITE_TRANSIENT: sqlite3_destructor_type = (-1L).toCPointer<CFunction<(COpaquePointer?) -> Unit>>()!!

@OptIn(ExperimentalForeignApi::class)
private fun errorMessage(db: CPointer<sqlite3>): String = sqlite3_errmsg(db)?.toKString() ?: "unknown error"

@OptIn(ExperimentalForeignApi::class)
private fun check(db: CPointer<sqlite3>, rc: Int) {
    if (rc != SQLITE_OK) throw RuntimeException("SQLite error ($rc): ${errorMessage(db)}")
}
