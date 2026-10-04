// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform

/**
 * Spring 7 declares MessageSource arguments as non-null. Ours may hold nulls, which format as
 * "null" as they always did, so the array is passed through unchanged.
 */
@Suppress("UNCHECKED_CAST")
fun Array<out Any?>.forMessage(): Array<Any> = this as Array<Any>
