// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.db

import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/** Programmatic transactions for code that is not a Spring bean method (jobs, filters). */
@Component
class Tx(private val template: TransactionTemplate) {
    fun <T> run(block: () -> T): T = template.execute { block() } as T

    fun <T> system(block: () -> T): T = DbContext.system { run(block) }
}
