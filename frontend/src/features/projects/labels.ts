// SPDX-License-Identifier: AGPL-3.0-only
export const BILL_BY = ["project", "tasks", "people", "none"] as const;
export const BUDGET_BY = ["none", "project", "project_cost", "task", "task_fees", "person"] as const;

export type BillBy = (typeof BILL_BY)[number];
export type BudgetBy = (typeof BUDGET_BY)[number];

/** Budgets measured in money rather than hours. */
export const isMoneyBudget = (budgetBy: string) => budgetBy === "project_cost" || budgetBy === "task_fees";

/** Budgets split across the project's tasks or people. */
export const isLineBudget = (budgetBy: string) => budgetBy === "task" || budgetBy === "task_fees" || budgetBy === "person";
