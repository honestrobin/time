-- SPDX-License-Identifier: AGPL-3.0-only
-- The database itself refuses a row that points into another account (security review,
-- 3 October). Every foreign key from one account's table to another now includes account_id,
-- so a time entry can only name a project of its own account, an invoice line only an invoice
-- of its own account, and so on. Row-level security already hides other accounts' rows; this
-- also stops a reference to one whose id is known. Generated from the catalog: every
-- single-column key from a table with account_id to the id of another table with account_id.

-- What the keys point at: (account_id, id) on each referenced table.
alter table clients add constraint clients_account_id_id_key unique (account_id, id);
alter table expense_categories add constraint expense_categories_account_id_id_key unique (account_id, id);
alter table expenses add constraint expenses_account_id_id_key unique (account_id, id);
alter table files add constraint files_account_id_id_key unique (account_id, id);
alter table import_jobs add constraint import_jobs_account_id_id_key unique (account_id, id);
alter table invoice_lines add constraint invoice_lines_account_id_id_key unique (account_id, id);
alter table invoice_sequences add constraint invoice_sequences_account_id_id_key unique (account_id, id);
alter table invoices add constraint invoices_account_id_id_key unique (account_id, id);
alter table memberships add constraint memberships_account_id_id_key unique (account_id, id);
alter table projects add constraint projects_account_id_id_key unique (account_id, id);
alter table tasks add constraint tasks_account_id_id_key unique (account_id, id);
alter table teams add constraint teams_account_id_id_key unique (account_id, id);
alter table time_entries add constraint time_entries_account_id_id_key unique (account_id, id);

alter table account_exports drop constraint account_exports_requested_by_fkey,
    add constraint account_exports_requested_by_fkey foreign key (account_id, requested_by) references memberships (account_id, id) on delete set null (requested_by);
alter table api_tokens drop constraint api_tokens_membership_id_fkey,
    add constraint api_tokens_membership_id_fkey foreign key (account_id, membership_id) references memberships (account_id, id) on delete cascade;
alter table archived_documents drop constraint archived_documents_client_id_fkey,
    add constraint archived_documents_client_id_fkey foreign key (account_id, client_id) references clients (account_id, id) on delete set null (client_id);
alter table budget_alerts drop constraint budget_alerts_project_id_fkey,
    add constraint budget_alerts_project_id_fkey foreign key (account_id, project_id) references projects (account_id, id) on delete cascade;
alter table client_contacts drop constraint client_contacts_client_id_fkey,
    add constraint client_contacts_client_id_fkey foreign key (account_id, client_id) references clients (account_id, id) on delete cascade;
alter table einvoice_transmissions drop constraint einvoice_transmissions_invoice_id_fkey,
    add constraint einvoice_transmissions_invoice_id_fkey foreign key (account_id, invoice_id) references invoices (account_id, id) on delete cascade;
alter table expenses drop constraint expenses_category_id_fkey,
    add constraint expenses_category_id_fkey foreign key (account_id, category_id) references expense_categories (account_id, id);
alter table expenses drop constraint expenses_invoice_fk,
    add constraint expenses_invoice_fk foreign key (account_id, invoice_id) references invoices (account_id, id) on delete set null (invoice_id);
alter table expenses drop constraint expenses_membership_id_fkey,
    add constraint expenses_membership_id_fkey foreign key (account_id, membership_id) references memberships (account_id, id);
alter table expenses drop constraint expenses_project_id_fkey,
    add constraint expenses_project_id_fkey foreign key (account_id, project_id) references projects (account_id, id);
alter table expenses drop constraint expenses_receipt_file_id_fkey,
    add constraint expenses_receipt_file_id_fkey foreign key (account_id, receipt_file_id) references files (account_id, id) on delete set null (receipt_file_id);
alter table files drop constraint files_uploaded_by_fkey,
    add constraint files_uploaded_by_fkey foreign key (account_id, uploaded_by) references memberships (account_id, id) on delete set null (uploaded_by);
alter table import_files drop constraint import_files_file_id_fkey,
    add constraint import_files_file_id_fkey foreign key (account_id, file_id) references files (account_id, id) on delete cascade;
alter table import_files drop constraint import_files_job_id_fkey,
    add constraint import_files_job_id_fkey foreign key (account_id, job_id) references import_jobs (account_id, id) on delete cascade;
alter table import_issues drop constraint import_issues_job_id_fkey,
    add constraint import_issues_job_id_fkey foreign key (account_id, job_id) references import_jobs (account_id, id) on delete cascade;
alter table import_jobs drop constraint import_jobs_started_by_fkey,
    add constraint import_jobs_started_by_fkey foreign key (account_id, started_by) references memberships (account_id, id) on delete set null (started_by);
alter table integrations drop constraint integrations_connected_by_fkey,
    add constraint integrations_connected_by_fkey foreign key (account_id, connected_by) references memberships (account_id, id) on delete set null (connected_by);
alter table invoice_expense_links drop constraint invoice_expense_links_expense_id_fkey,
    add constraint invoice_expense_links_expense_id_fkey foreign key (account_id, expense_id) references expenses (account_id, id) on delete cascade;
alter table invoice_expense_links drop constraint invoice_expense_links_invoice_line_id_fkey,
    add constraint invoice_expense_links_invoice_line_id_fkey foreign key (account_id, invoice_line_id) references invoice_lines (account_id, id) on delete cascade;
alter table invoice_lines drop constraint invoice_lines_invoice_id_fkey,
    add constraint invoice_lines_invoice_id_fkey foreign key (account_id, invoice_id) references invoices (account_id, id) on delete cascade;
alter table invoice_lines drop constraint invoice_lines_membership_id_fkey,
    add constraint invoice_lines_membership_id_fkey foreign key (account_id, membership_id) references memberships (account_id, id) on delete set null (membership_id);
alter table invoice_lines drop constraint invoice_lines_project_id_fkey,
    add constraint invoice_lines_project_id_fkey foreign key (account_id, project_id) references projects (account_id, id) on delete set null (project_id);
alter table invoice_lines drop constraint invoice_lines_task_id_fkey,
    add constraint invoice_lines_task_id_fkey foreign key (account_id, task_id) references tasks (account_id, id) on delete set null (task_id);
alter table invoice_time_links drop constraint invoice_time_links_invoice_line_id_fkey,
    add constraint invoice_time_links_invoice_line_id_fkey foreign key (account_id, invoice_line_id) references invoice_lines (account_id, id) on delete cascade;
alter table invoice_time_links drop constraint invoice_time_links_time_entry_id_fkey,
    add constraint invoice_time_links_time_entry_id_fkey foreign key (account_id, time_entry_id) references time_entries (account_id, id) on delete cascade;
alter table invoices drop constraint invoices_client_id_fkey,
    add constraint invoices_client_id_fkey foreign key (account_id, client_id) references clients (account_id, id);
alter table invoices drop constraint invoices_created_by_fkey,
    add constraint invoices_created_by_fkey foreign key (account_id, created_by) references memberships (account_id, id) on delete set null (created_by);
alter table invoices drop constraint invoices_sequence_id_fkey,
    add constraint invoices_sequence_id_fkey foreign key (account_id, sequence_id) references invoice_sequences (account_id, id);
alter table payments drop constraint payments_invoice_id_fkey,
    add constraint payments_invoice_id_fkey foreign key (account_id, invoice_id) references invoices (account_id, id) on delete cascade;
alter table project_members drop constraint project_members_membership_id_fkey,
    add constraint project_members_membership_id_fkey foreign key (account_id, membership_id) references memberships (account_id, id) on delete cascade;
alter table project_members drop constraint project_members_project_id_fkey,
    add constraint project_members_project_id_fkey foreign key (account_id, project_id) references projects (account_id, id) on delete cascade;
alter table project_tasks drop constraint project_tasks_project_id_fkey,
    add constraint project_tasks_project_id_fkey foreign key (account_id, project_id) references projects (account_id, id) on delete cascade;
alter table project_tasks drop constraint project_tasks_task_id_fkey,
    add constraint project_tasks_task_id_fkey foreign key (account_id, task_id) references tasks (account_id, id);
alter table projects drop constraint projects_client_id_fkey,
    add constraint projects_client_id_fkey foreign key (account_id, client_id) references clients (account_id, id);
alter table team_memberships drop constraint team_memberships_membership_id_fkey,
    add constraint team_memberships_membership_id_fkey foreign key (account_id, membership_id) references memberships (account_id, id) on delete cascade;
alter table team_memberships drop constraint team_memberships_team_id_fkey,
    add constraint team_memberships_team_id_fkey foreign key (account_id, team_id) references teams (account_id, id) on delete cascade;
alter table time_entries drop constraint time_entries_invoice_fk,
    add constraint time_entries_invoice_fk foreign key (account_id, invoice_id) references invoices (account_id, id) on delete set null (invoice_id);
alter table time_entries drop constraint time_entries_membership_id_fkey,
    add constraint time_entries_membership_id_fkey foreign key (account_id, membership_id) references memberships (account_id, id);
alter table time_entries drop constraint time_entries_project_id_fkey,
    add constraint time_entries_project_id_fkey foreign key (account_id, project_id) references projects (account_id, id);
alter table time_entries drop constraint time_entries_task_id_fkey,
    add constraint time_entries_task_id_fkey foreign key (account_id, task_id) references tasks (account_id, id);
alter table timesheet_rows drop constraint timesheet_rows_membership_id_fkey,
    add constraint timesheet_rows_membership_id_fkey foreign key (account_id, membership_id) references memberships (account_id, id) on delete cascade;
alter table timesheet_rows drop constraint timesheet_rows_project_id_fkey,
    add constraint timesheet_rows_project_id_fkey foreign key (account_id, project_id) references projects (account_id, id) on delete cascade;
alter table timesheet_rows drop constraint timesheet_rows_task_id_fkey,
    add constraint timesheet_rows_task_id_fkey foreign key (account_id, task_id) references tasks (account_id, id) on delete cascade;
alter table timesheet_submissions drop constraint timesheet_submissions_decided_by_fkey,
    add constraint timesheet_submissions_decided_by_fkey foreign key (account_id, decided_by) references memberships (account_id, id) on delete set null (decided_by);
alter table timesheet_submissions drop constraint timesheet_submissions_membership_id_fkey,
    add constraint timesheet_submissions_membership_id_fkey foreign key (account_id, membership_id) references memberships (account_id, id) on delete cascade;
