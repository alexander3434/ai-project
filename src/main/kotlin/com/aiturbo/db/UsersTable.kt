package com.aiturbo.db

import java.sql.Timestamp
import java.time.LocalDateTime
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.IDateColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.api.RowApi

/**
 * The local `users` table. The DDL already exists (FR-02, out of scope): the
 * mapping is read/insert-only and no schema-management call ever runs.
 *
 * `created_at` is a PostgreSQL `timestamp` with a `now()` default; it is left
 * unset on insert so the database default applies.
 */
internal object UsersTable : Table("users") {
    val id = integer("id").autoIncrement()
    val data = varchar("data", 255)

    /** Local time in the requested region ("19:45:03"). */
    val time = varchar("time", 100).nullable()

    /** The moment the user asked, formatted with [DATA_FORMAT] when read. */
    val createdAt = registerColumn("created_at", TimestampColumnType).nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Maps `java.sql.Timestamp` (what the PostgreSQL driver returns for a
 * `timestamp` column) to [LocalDateTime], reproducing the previous JDBC read
 * path. The column DSL factory (`datetime`) ships in a separate Exposed module
 * that is not on the classpath (NFR-02 keeps `exposed-core` + `exposed-jdbc`
 * only), so the type is registered explicitly.
 */
private object TimestampColumnType : ColumnType<LocalDateTime>(), IDateColumnType {

    override val hasTimePart: Boolean = true

    override fun sqlType(): String = "TIMESTAMP"

    override fun valueFromDB(value: Any): LocalDateTime = when (value) {
        is LocalDateTime -> value
        is Timestamp -> value.toLocalDateTime()
        is String -> Timestamp.valueOf(value).toLocalDateTime()
        else -> error("Unexpected value for a timestamp column: ${value::class.qualifiedName}")
    }

    override fun notNullValueToDB(value: LocalDateTime): Any = Timestamp.valueOf(value)

    override fun readObject(rs: RowApi, index: Int): Any? = rs.getObject(index)?.let(::valueFromDB)
}
