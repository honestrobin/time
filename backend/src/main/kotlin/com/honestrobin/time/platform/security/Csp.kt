// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.security

/** Extra origins a module needs in the Content-Security-Policy, e.g. a payment provider's checkout. */
data class CspSources(
    val script: List<String> = emptyList(),
    val frame: List<String> = emptyList(),
    val connect: List<String> = emptyList(),
    val img: List<String> = emptyList(),
)

fun interface CspContributor {
    fun sources(): CspSources
}

/** The policy: everything from our own origin, plus what contributing modules ask for. */
fun contentSecurityPolicy(extra: List<CspSources>): String {
    fun list(base: String, more: List<String>) = (listOf(base) + more.distinct()).joinToString(" ")
    return "default-src 'self'; script-src ${list("'self'", extra.flatMap { it.script })}; style-src 'self' 'unsafe-inline'; " +
        "img-src ${list("'self' data: blob:", extra.flatMap { it.img })}; font-src 'self'; connect-src ${list("'self'", extra.flatMap { it.connect })}; " +
        "frame-src ${list("'self'", extra.flatMap { it.frame })}; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'"
}
