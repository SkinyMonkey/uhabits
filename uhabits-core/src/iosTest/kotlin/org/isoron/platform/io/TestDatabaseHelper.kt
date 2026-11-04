package org.isoron.platform.io

import org.isoron.uhabits.core.DATABASE_VERSION

actual object TestDatabaseHelper {
    actual suspend fun createEmptyDatabase(): Database {
        val db = IosDatabaseOpener().open(":memory:")
        db.setVersion(8)
        db.migrateTo(DATABASE_VERSION) { v -> loadMigrationSQL(v) }
        return db
    }

    actual suspend fun loadMigrationSQL(version: Int): String {
        val path = "migrations/${version.toString().padStart(2, '0')}.sql"
        return createTestFileOpener().openResourceFile(path).lines().joinToString("\n")
    }
}
