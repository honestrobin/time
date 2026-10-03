// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.web

/** Cursor-paginated list. Pass `next_cursor` back as `cursor` to fetch the next page; null means the end. */
data class Page<T>(val data: List<T>, val nextCursor: String?) {
    companion object {
        /** [rows] must have been fetched with `limit + 1`; the extra row signals another page. */
        fun <T> of(rows: List<T>, limit: Int, cursorOf: (T) -> String): Page<T> {
            val page = rows.take(limit)
            return Page(page, if (rows.size > limit) cursorOf(page.last()) else null)
        }
    }
}

fun clampLimit(limit: Int?, default: Int = 100, max: Int = 2000) = (limit ?: default).coerceIn(1, max)

private fun badCursor(): Nothing = throw BadRequestException("bad_cursor", "This cursor isn't valid; start again from the first page")

/** A cursor that is a row id. A malformed one is the client's mistake: 400, not 500. */
fun idCursor(cursor: String): java.util.UUID = runCatching { java.util.UUID.fromString(cursor) }.getOrElse { badCursor() }

/** A `date_id` cursor, for lists ordered by a date and then the id. */
fun dateIdCursor(cursor: String): Pair<java.time.LocalDate, java.util.UUID> = runCatching {
    val (d, id) = cursor.split('_', limit = 2)
    java.time.LocalDate.parse(d) to java.util.UUID.fromString(id)
}.getOrElse { badCursor() }
