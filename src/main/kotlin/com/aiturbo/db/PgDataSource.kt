package com.aiturbo.db

import javax.sql.DataSource
import org.postgresql.ds.PGSimpleDataSource

/** Makes the application identifiable in `pg_stat_activity` (NFR-03). */
internal const val AI_TURBO_APPLICATION_NAME = "ai-turbo"

/**
 * One physical connection per `getConnection()`: no pool and no connection is
 * opened at construction, so the application starts with the database absent.
 * Uses the PostgreSQL driver's own `DataSource` — the driver is already a
 * dependency, and plain JDBC driver lookups are forbidden in `src/main` (FR-02).
 *
 * A non-null [timeoutSeconds] bounds connect, login and socket waits; the local
 * database keeps its previous connection behaviour and only carries the
 * application name.
 */
internal fun pgDataSource(
    jdbcUrl: String,
    user: String,
    password: String,
    applicationName: String,
    timeoutSeconds: Int? = null,
): DataSource = PGSimpleDataSource().apply {
    setURL(jdbcUrl)
    setUser(user)
    setPassword(password)
    setApplicationName(applicationName)
    if (timeoutSeconds != null) {
        setConnectTimeout(timeoutSeconds)
        setSocketTimeout(timeoutSeconds)
        setLoginTimeout(timeoutSeconds)
    }
}
