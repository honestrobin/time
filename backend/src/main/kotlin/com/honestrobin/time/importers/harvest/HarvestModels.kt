// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.importers.harvest

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// Shapes of Harvest API v2 resources (verified against the API docs on 2026-09-30/10-01).
// Only the fields the importer uses are declared; unknown fields are ignored.

@JsonIgnoreProperties(ignoreUnknown = true)
data class HRef(val id: Long, val name: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HClientRef(val id: Long, val name: String? = null, val currency: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HCompany(
    val name: String? = null,
    val weekStartDay: String? = null,
    val timeFormat: String? = null,
    val clock: String? = null,
    val weeklyCapacity: Int? = null,
    val approvalFeature: Boolean? = null,
    val wantsTimestampTimers: Boolean? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HUser(
    val id: Long,
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val timezone: String? = null,
    val hasAccessToAllFutureProjects: Boolean = false,
    val isContractor: Boolean = false,
    val isActive: Boolean = true,
    val weeklyCapacity: Int? = null,
    val defaultHourlyRate: BigDecimal? = null,
    val costRate: BigDecimal? = null,
    val roles: List<String> = emptyList(),
    val accessRoles: List<String> = emptyList(),
) {
    val fullName get() = listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { email ?: "Harvest user $id" }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class HRole(val id: Long, val name: String, val userIds: List<Long> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class HClient(val id: Long, val name: String, val isActive: Boolean = true, val address: String? = null, val currency: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HContact(
    val id: Long,
    val client: HRef,
    val title: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val phoneOffice: String? = null,
    val phoneMobile: String? = null,
    val fax: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HTask(
    val id: Long,
    val name: String,
    val billableByDefault: Boolean = true,
    val defaultHourlyRate: BigDecimal? = null,
    val isDefault: Boolean = false,
    val isActive: Boolean = true,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HProject(
    val id: Long,
    val client: HClientRef,
    val name: String,
    val code: String? = null,
    val isActive: Boolean = true,
    val isBillable: Boolean = true,
    val isFixedFee: Boolean = false,
    val billBy: String = "none",
    val hourlyRate: BigDecimal? = null,
    val budgetBy: String = "none",
    val budgetIsMonthly: Boolean = false,
    val budget: BigDecimal? = null,
    val costBudget: BigDecimal? = null,
    val costBudgetIncludeExpenses: Boolean = false,
    val notifyWhenOverBudget: Boolean = false,
    val overBudgetNotificationPercentage: BigDecimal? = null,
    val showBudgetToAll: Boolean = false,
    val fee: BigDecimal? = null,
    val notes: String? = null,
    val startsOn: LocalDate? = null,
    val endsOn: LocalDate? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HTaskAssignment(
    val id: Long,
    val project: HRef,
    val task: HRef,
    val isActive: Boolean = true,
    val billable: Boolean = true,
    val hourlyRate: BigDecimal? = null,
    val budget: BigDecimal? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HUserAssignment(
    val id: Long,
    val project: HRef,
    val user: HRef,
    val isActive: Boolean = true,
    val isProjectManager: Boolean = false,
    val useDefaultRates: Boolean = true,
    val hourlyRate: BigDecimal? = null,
    val budget: BigDecimal? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HExpenseCategory(val id: Long, val name: String, val unitName: String? = null, val unitPrice: BigDecimal? = null, val isActive: Boolean = true)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HExternalReference(
    val id: String? = null,
    val groupId: String? = null,
    val accountId: String? = null,
    val permalink: String? = null,
    val service: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HInvoiceRef(val id: Long, val number: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HTimeEntry(
    val id: Long,
    val spentDate: LocalDate,
    val user: HRef,
    val client: HRef? = null,
    val project: HRef,
    val task: HRef,
    val externalReference: HExternalReference? = null,
    val invoice: HInvoiceRef? = null,
    val hours: BigDecimal = BigDecimal.ZERO,
    val roundedHours: BigDecimal? = null,
    val notes: String? = null,
    val isLocked: Boolean = false,
    val lockedReason: String? = null,
    /** unsubmitted, submitted or approved. */
    val approvalStatus: String? = null,
    val isBilled: Boolean = false,
    val timerStartedAt: Instant? = null,
    val startedTime: String? = null,
    val endedTime: String? = null,
    val isRunning: Boolean = false,
    val billable: Boolean = true,
    val billableRate: BigDecimal? = null,
    val costRate: BigDecimal? = null,
    val updatedAt: Instant? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HReceipt(val url: String? = null, val fileName: String? = null, val fileSize: Long? = null, val contentType: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HExpense(
    val id: Long,
    val project: HRef,
    val expenseCategory: HRef,
    val user: HRef,
    val receipt: HReceipt? = null,
    val invoice: HInvoiceRef? = null,
    val notes: String? = null,
    val units: BigDecimal? = null,
    val totalCost: BigDecimal = BigDecimal.ZERO,
    val billable: Boolean = true,
    val isLocked: Boolean = false,
    val lockedReason: String? = null,
    val approvalStatus: String? = null,
    val isBilled: Boolean = false,
    val spentDate: LocalDate,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HLineItem(
    val id: Long,
    val project: HRef? = null,
    val kind: String? = null,
    val description: String? = null,
    val quantity: BigDecimal = BigDecimal.ONE,
    val unitPrice: BigDecimal = BigDecimal.ZERO,
    val amount: BigDecimal = BigDecimal.ZERO,
    val taxed: Boolean = false,
    val taxed2: Boolean = false,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HInvoice(
    val id: Long,
    val client: HRef,
    val lineItems: List<HLineItem> = emptyList(),
    val number: String? = null,
    val purchaseOrder: String? = null,
    val amount: BigDecimal = BigDecimal.ZERO,
    val dueAmount: BigDecimal = BigDecimal.ZERO,
    val tax: BigDecimal? = null,
    val taxAmount: BigDecimal? = null,
    val tax2: BigDecimal? = null,
    val tax2Amount: BigDecimal? = null,
    val discount: BigDecimal? = null,
    val discountAmount: BigDecimal? = null,
    val subject: String? = null,
    val notes: String? = null,
    val currency: String = "USD",
    /** draft, open, paid or closed. */
    val state: String = "draft",
    val periodStart: LocalDate? = null,
    val periodEnd: LocalDate? = null,
    val issueDate: LocalDate? = null,
    val dueDate: LocalDate? = null,
    val paymentTerm: String? = null,
    val sentAt: Instant? = null,
    val paidAt: Instant? = null,
    val paidDate: LocalDate? = null,
    val closedAt: Instant? = null,
    val createdAt: Instant? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HPayment(
    val id: Long,
    val amount: BigDecimal,
    val paidAt: Instant? = null,
    val paidDate: LocalDate? = null,
    val recordedBy: String? = null,
    val notes: String? = null,
    val transactionId: String? = null,
    val paymentGateway: HRef? = null,
)

/** GET /v2/reports/time/projects */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HTimeReportRow(
    val clientId: Long,
    val clientName: String? = null,
    val projectId: Long? = null,
    val projectName: String? = null,
    val currency: String? = null,
    val totalHours: BigDecimal = BigDecimal.ZERO,
    val billableHours: BigDecimal = BigDecimal.ZERO,
    val billableAmount: BigDecimal = BigDecimal.ZERO,
)

/** GET /v2/reports/uninvoiced */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HUninvoicedRow(
    val clientId: Long,
    val projectId: Long,
    val currency: String? = null,
    val totalHours: BigDecimal = BigDecimal.ZERO,
    val uninvoicedHours: BigDecimal = BigDecimal.ZERO,
    val uninvoicedExpenses: BigDecimal = BigDecimal.ZERO,
    val uninvoicedAmount: BigDecimal = BigDecimal.ZERO,
)

data class HPage<T>(val items: List<T>, val nextUrl: String?, val raw: List<JsonNode>)
