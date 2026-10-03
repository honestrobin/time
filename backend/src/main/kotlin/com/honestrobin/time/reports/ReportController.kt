// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.reports

import com.honestrobin.time.platform.security.Current
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/**
 * Reports (spec §12). Every report takes the same filters; list filters accept repeated
 * parameters (`project_id=a&project_id=b`). Each report also exports as CSV or XLSX.
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "reports", description = "Time, detailed time, uninvoiced, budget and expense reports")
class ReportController(private val reports: ReportService, private val exports: ReportExportService) {

    @GetMapping("/time")
    @Operation(summary = "Hours and amounts grouped by client, project, task or person")
    fun time(
        @RequestParam(name = "group_by", defaultValue = "project") groupBy: String,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(name = "client_id", required = false) clientIds: List<UUID>?,
        @RequestParam(name = "project_id", required = false) projectIds: List<UUID>?,
        @RequestParam(name = "task_id", required = false) taskIds: List<UUID>?,
        @RequestParam(name = "membership_id", required = false) membershipIds: List<UUID>?,
        @RequestParam(name = "team_id", required = false) teamIds: List<UUID>?,
        @RequestParam(required = false) billable: Boolean?,
        @RequestParam(name = "approval_state", required = false) approvalStates: List<String>?,
        @RequestParam(required = false) invoiced: Boolean?,
    ): TimeReport = reports.time(
        Current.member(), groupBy,
        filter(from, to, clientIds, projectIds, taskIds, membershipIds, teamIds, null, billable, approvalStates, invoiced),
    )

    @GetMapping("/detailed")
    @Operation(summary = "Time entries, oldest first, with the time report's filters")
    fun detailed(
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(name = "client_id", required = false) clientIds: List<UUID>?,
        @RequestParam(name = "project_id", required = false) projectIds: List<UUID>?,
        @RequestParam(name = "task_id", required = false) taskIds: List<UUID>?,
        @RequestParam(name = "membership_id", required = false) membershipIds: List<UUID>?,
        @RequestParam(name = "team_id", required = false) teamIds: List<UUID>?,
        @RequestParam(required = false) billable: Boolean?,
        @RequestParam(name = "approval_state", required = false) approvalStates: List<String>?,
        @RequestParam(required = false) invoiced: Boolean?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ) = reports.detailed(
        Current.member(),
        filter(from, to, clientIds, projectIds, taskIds, membershipIds, teamIds, null, billable, approvalStates, invoiced),
        cursor, limit,
    )

    @GetMapping("/uninvoiced")
    @Operation(summary = "Billable time and expenses not invoiced yet, by client and project")
    fun uninvoiced(
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(name = "client_id", required = false) clientIds: List<UUID>?,
        @RequestParam(name = "project_id", required = false) projectIds: List<UUID>?,
    ): UninvoicedReport = reports.uninvoiced(Current.member(), filter(from, to, clientIds, projectIds))

    @GetMapping("/budget")
    @Operation(summary = "Budget, spent, remaining and % used per project")
    fun budget(
        @RequestParam(name = "client_id", required = false) clientIds: List<UUID>?,
        @RequestParam(name = "include_archived", defaultValue = "false") includeArchived: Boolean,
    ): BudgetReport = reports.budget(Current.member(), clientIds.orEmpty(), includeArchived)

    @GetMapping("/expenses")
    @Operation(summary = "Expenses grouped by category, client, project or person")
    fun expenses(
        @RequestParam(name = "group_by", defaultValue = "category") groupBy: String,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(name = "client_id", required = false) clientIds: List<UUID>?,
        @RequestParam(name = "project_id", required = false) projectIds: List<UUID>?,
        @RequestParam(name = "membership_id", required = false) membershipIds: List<UUID>?,
        @RequestParam(name = "team_id", required = false) teamIds: List<UUID>?,
        @RequestParam(name = "category_id", required = false) categoryIds: List<UUID>?,
        @RequestParam(required = false) billable: Boolean?,
        @RequestParam(name = "approval_state", required = false) approvalStates: List<String>?,
        @RequestParam(required = false) invoiced: Boolean?,
    ): ExpenseReport = reports.expenses(
        Current.member(), groupBy,
        filter(from, to, clientIds, projectIds, null, membershipIds, teamIds, categoryIds, billable, approvalStates, invoiced),
    )

    /**
     * Downloads a report as CSV (UTF-8 with BOM) or XLSX. `kind` is time, detailed, uninvoiced,
     * budget, expenses or expense_items; the other parameters are the report's own.
     */
    @GetMapping("/{kind}/export")
    @Operation(summary = "Export a report as CSV or XLSX")
    fun export(
        @PathVariable kind: String,
        @RequestParam(defaultValue = "csv") format: String,
        @RequestParam(name = "group_by", required = false) groupBy: String?,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(name = "client_id", required = false) clientIds: List<UUID>?,
        @RequestParam(name = "project_id", required = false) projectIds: List<UUID>?,
        @RequestParam(name = "task_id", required = false) taskIds: List<UUID>?,
        @RequestParam(name = "membership_id", required = false) membershipIds: List<UUID>?,
        @RequestParam(name = "team_id", required = false) teamIds: List<UUID>?,
        @RequestParam(name = "category_id", required = false) categoryIds: List<UUID>?,
        @RequestParam(required = false) billable: Boolean?,
        @RequestParam(name = "approval_state", required = false) approvalStates: List<String>?,
        @RequestParam(required = false) invoiced: Boolean?,
        @RequestParam(name = "include_archived", defaultValue = "false") includeArchived: Boolean,
        response: HttpServletResponse,
    ) {
        val req = ExportRequest(
            kind, format, groupBy,
            filter(from, to, clientIds, projectIds, taskIds, membershipIds, teamIds, categoryIds, billable, approvalStates, invoiced),
            includeArchived,
        )
        // Validate before any header is set, so errors still come back as JSON.
        req.validate()
        response.contentType = req.contentType
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(req.filename).build().toString())
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        exports.write(Current.member(), req, response.outputStream)
    }

    private fun filter(
        from: LocalDate?,
        to: LocalDate?,
        clientIds: List<UUID>?,
        projectIds: List<UUID>?,
        taskIds: List<UUID>? = null,
        membershipIds: List<UUID>? = null,
        teamIds: List<UUID>? = null,
        categoryIds: List<UUID>? = null,
        billable: Boolean? = null,
        approvalStates: List<String>? = null,
        invoiced: Boolean? = null,
    ) = ReportFilter(
        from, to, clientIds.orEmpty(), projectIds.orEmpty(), taskIds.orEmpty(), membershipIds.orEmpty(), teamIds.orEmpty(),
        categoryIds.orEmpty(), billable, approvalStates.orEmpty(), invoiced,
    )
}
