#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Fills a Harvest test account with made-up data, so Time's Harvest import can be tested end to end.

Everything it creates is marked: clients, tasks, expense categories and roles end in " (test)",
and every project, time entry, expense, invoice and estimate belongs to one of those clients.
--delete removes exactly that, in an order Harvest accepts.

Reads HARVEST_ACCESS_TOKEN and HARVEST_ACCOUNT_ID from the environment. Python 3, standard
library only. See README.md next to this file.

Fields and endpoints were checked against the Harvest API v2 docs on 2026-10-05. Anything that
could not be confirmed there is marked VERIFY.
"""

import argparse
import collections
import datetime as dt
import json
import os
import random
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from decimal import ROUND_HALF_UP, Decimal

API = "https://api.harvestapp.com/v2"
# Harvest asks for the app's name and a way to reach its makers.
USER_AGENT = "Honest Robin Time test-data script (https://github.com/honestrobin/time)"
MARK = " (test)"
ZERO_DECIMAL = {"JPY"}

# ------------------------------------------------------------------------------------------ data

TASKS = [
    {"key": "design", "name": "Design", "billable": True, "rate": 95},
    {"key": "dev", "name": "Development", "billable": True, "rate": 120},
    {"key": "pm", "name": "Project management", "billable": True, "rate": 80},
    {"key": "research", "name": "Research", "billable": True, "rate": 100},
    {"key": "meetings", "name": "Meetings", "billable": False, "rate": None},
    # Archived, so the import meets an inactive task.
    {"key": "legacy", "name": "Legacy support", "billable": True, "rate": 90, "active": False},
]

CATEGORIES = [
    {"key": "travel", "name": "Travel"},
    {"key": "meals", "name": "Meals"},
    {"key": "software", "name": "Software"},
    # A unit-based category: expenses in it give units, and Harvest computes the cost.
    {"key": "mileage", "name": "Mileage", "unit_name": "km", "unit_price": "0.42"},
    {"key": "printing", "name": "Printing", "active": False},
]

# Made-up companies, addresses and people. Phone numbers use ranges set aside for fiction
# (555-01xx in North America, 0117 496 0xxx in the UK); the others have none.
CLIENTS = [
    {"key": "bw", "name": "Brightwater Analytics", "currency": "EUR",
     "address": "Unit 4, Quayside Works\n12 Harbour Row\nDublin\nIreland",
     "contacts": [
         {"first_name": "Aoife", "last_name": "Brennan", "title": "Head of data", "email": "aoife.brennan@example.com"},
         {"first_name": "Cian", "last_name": "Moran", "title": "Accounts payable", "email": "accounts.brightwater@example.com"},
     ]},
    {"key": "kf", "name": "Kestrel & Finch Architects", "currency": "GBP",
     "address": "3 Ropewalk Yard\nBristol\nUnited Kingdom",
     "contacts": [
         {"first_name": "Harriet", "last_name": "Okafor", "title": "Practice manager", "email": "harriet.okafor@example.com",
          "phone_office": "0117 496 0123"},
     ]},
    {"key": "pc", "name": "Pinecone Robotics", "currency": "USD",
     "address": "1420 Alder Lane, Suite 300\nPortland, OR\nUnited States",
     "contacts": [
         {"first_name": "Dana", "last_name": "Whitfield", "title": "VP engineering", "email": "dana.whitfield@example.com",
          "phone_office": "+1 503 555 0142"},
         {"first_name": "Luis", "last_name": "Ortega", "title": "Finance", "email": "luis.ortega@example.com",
          "phone_mobile": "+1 503 555 0187"},
     ]},
    {"key": "tc", "name": "Tamarack Clinics", "currency": "CAD",
     "address": "88 Prairie Gate Road\nWinnipeg, MB\nCanada",
     "contacts": [
         {"first_name": "Priya", "last_name": "Raman", "title": "Operations director", "email": "priya.raman@example.com",
          "phone_office": "+1 204 555 0119"},
     ]},
    {"key": "sk", "name": "Sakura Tile Works", "currency": "JPY",
     "address": "2-14 Kogane-machi\nKanazawa, Ishikawa\nJapan",
     "contacts": [
         {"first_name": "Haruto", "last_name": "Mori", "title": "Owner", "email": "haruto.mori@example.com"},
         {"first_name": "Yui", "last_name": "Tanaka", "title": "Online shop", "email": "yui.tanaka@example.com"},
     ]},
    {"key": "fb", "name": "Fernblick Verlag", "currency": "EUR",
     "address": "Lindengasse 7\nGraz\nAustria",
     "contacts": [
         {"first_name": "Katharina", "last_name": "Leitner", "title": "Publisher", "email": "katharina.leitner@example.com"},
     ]},
]

# Every billing mode Harvest has (task rate, person rate, project rate, non-billable, fixed fee)
# and every budget kind (hours or fees in total, per task, hours per person; Harvest has no
# fees per person). "window" is the part of the three months the project is worked on.
PROJECTS = [
    {"key": "bw-platform", "client": "bw", "name": "Data platform rebuild", "code": "BWA-01",
     "billable": True, "bill_by": "Tasks", "budget_by": "task_fees", "notify": 80,
     "window": (0.0, 1.0), "weight": 5,
     "notes": "Billed by task rate, with a budget in fees per task.",
     "tasks": [{"task": "design", "weight": 1, "budget": 6000}, {"task": "dev", "rate": 130, "weight": 4, "budget": 18000},
               {"task": "pm", "weight": 1, "budget": 3000}, {"task": "meetings", "weight": 1}],
     "user": {"is_project_manager": True}},
    {"key": "bw-dash", "client": "bw", "name": "Quarterly dashboards", "code": "BWA-02",
     "billable": True, "bill_by": "Project", "hourly_rate": 110, "budget_by": "project", "budget": 40, "monthly": True, "notify": 90,
     "window": (0.0, 1.0), "weekdays": (0, 3), "weight": 2,
     "notes": "Billed by project rate, with a budget of 40 hours a month.",
     "tasks": [{"task": "research", "weight": 1}, {"task": "dev", "weight": 2}, {"task": "meetings", "weight": 1}],
     "user": {}},
    {"key": "kf-portal", "client": "kf", "name": "Client portal", "code": "KFA-01",
     "billable": True, "bill_by": "People", "budget_by": "person", "notify": 80,
     "window": (0.0, 1.0), "weight": 4,
     "notes": "Billed by person rate, with a budget in hours per person.",
     "tasks": [{"task": "design", "weight": 2}, {"task": "dev", "weight": 3}, {"task": "pm", "weight": 1}, {"task": "meetings", "weight": 1}],
     "user": {"use_default_rates": False, "hourly_rate": 95, "budget": 140, "is_project_manager": True}},
    # VERIFY: Harvest's help says fixed-fee projects budgeted in fees can carry task rates; the
    # API docs don't say which bill_by they take.
    {"key": "kf-site", "client": "kf", "name": "Website refresh", "code": "KFA-02",
     "billable": True, "fixed_fee": True, "fee": 8500, "bill_by": "Tasks", "budget_by": "project_cost", "cost_budget": 8500,
     "include_expenses": True, "notify": 80,
     "window": (0.0, 0.6), "weight": 3,
     "notes": "Fixed fee of 8,500, with a budget in total fees that includes expenses.",
     "tasks": [{"task": "design", "rate": 85, "weight": 3}, {"task": "dev", "rate": 105, "weight": 2}, {"task": "pm", "rate": 70, "weight": 1}],
     "user": {}},
    {"key": "pc-firmware", "client": "pc", "name": "Firmware build tools", "code": "PCR-01",
     "billable": True, "bill_by": "Tasks", "budget_by": "task", "notify": 80, "show_budget": True,
     "window": (0.0, 1.0), "weight": 4,
     "notes": "Billed by task rate, with a budget in hours per task.",
     "tasks": [{"task": "dev", "rate": 145, "weight": 4, "budget": 160}, {"task": "research", "weight": 1, "budget": 40},
               {"task": "pm", "rate": 90, "weight": 1, "budget": 20}, {"task": "meetings", "weight": 1}],
     "user": {"is_project_manager": True}},
    {"key": "pc-support", "client": "pc", "name": "Support retainer", "code": "PCR-02",
     "billable": True, "bill_by": "People", "budget_by": "none",
     "window": (0.0, 1.0), "weekdays": (4,), "weight": 2,
     "notes": "Billed by person rate, using the person's own default rate.",
     "tasks": [{"task": "dev", "weight": 3}, {"task": "meetings", "weight": 1}],
     "user": {"use_default_rates": True}},
    {"key": "tc-audit", "client": "tc", "name": "Booking system audit", "code": "TCL-01",
     "billable": True, "bill_by": "Project", "hourly_rate": 140, "budget_by": "project_cost", "cost_budget": 15000,
     "include_expenses": True, "notify": 75,
     "window": (0.1, 0.75), "ends": True, "weight": 3,
     "notes": "Billed by project rate, with a budget in total fees that includes expenses.",
     "tasks": [{"task": "research", "weight": 3}, {"task": "dev", "weight": 1}, {"task": "pm", "weight": 1}, {"task": "meetings", "weight": 1}],
     "user": {}},
    {"key": "sk-catalogue", "client": "sk", "name": "Online catalogue", "code": "STW-01",
     "billable": True, "bill_by": "Tasks", "budget_by": "project", "budget": 160, "notify": 80,
     "window": (0.3, 1.0), "weight": 3,
     "notes": "Billed by task rate in yen, with a budget of 160 hours in total.",
     "tasks": [{"task": "design", "rate": 11000, "weight": 3}, {"task": "dev", "rate": 14000, "weight": 2},
               {"task": "pm", "rate": 9000, "weight": 1}, {"task": "meetings", "weight": 1}],
     "user": {}},
    {"key": "fb-pitch", "client": "fb", "name": "Pitch preparation", "code": "FBV-01",
     "billable": False, "bill_by": "none", "budget_by": "project", "budget": 30, "show_budget": True,
     "window": (0.7, 1.0), "weight": 2,
     "notes": "Not billable, with a budget of 30 hours.",
     "tasks": [{"task": "research", "weight": 2}, {"task": "design", "weight": 2}, {"task": "meetings", "weight": 1}],
     "user": {}},
    {"key": "fb-spring", "client": "fb", "name": "Spring catalogue", "code": "FBV-02",
     "billable": True, "bill_by": "Project", "hourly_rate": 85, "budget_by": "none",
     "window": (0.0, 0.2), "ends": True, "archive": True, "weight": 3,
     "notes": "Billed by project rate; finished, invoiced and archived.",
     "tasks": [{"task": "design", "weight": 3}, {"task": "pm", "weight": 1}],
     "user": {}},
]

NOTES = {
    "design": ["Wireframes for the onboarding flow", "Reviewed feedback on the style guide", "Icon set, second round",
               "Layout for the report pages", "Mobile navigation mock-ups"],
    "dev": ["Import job: retry failed rows", "Fixed date parsing in the CSV export", "Code review and small fixes",
            "Endpoints for the order history", "Faster build pipeline", "Tests for the billing module", "Search page performance"],
    "pm": ["Weekly planning", "Updated the plan and the budget", "Status report for the client", "Scope review with the client"],
    "research": ["Compared hosting options", "Interviewed two users", "Read through the existing data model", "Prototype for the data viewer"],
    "meetings": ["Weekly check-in", "Kick-off call", "Sprint review", "Call about the next phase"],
}

# project, category, where in the three months, cost (or units for mileage), notes, billable, with a receipt
EXPENSES = [
    ("bw-platform", "travel", 0.15, "189.40", "Return flight for the on-site workshop", True, True),
    ("bw-platform", "meals", 0.16, "46.80", "Dinner during the on-site workshop", False, False),
    ("bw-platform", "software", 0.45, "79.00", "Database monitoring tool, one month", True, False),
    ("bw-platform", "mileage", 0.85, 64, "Drive to the client office and back", True, False),
    ("kf-portal", "travel", 0.30, "112.50", "Train to Bristol for user testing", True, True),
    ("kf-portal", "meals", 0.31, "38.20", "Lunch with the test participants", False, False),
    ("kf-site", "software", 0.20, "24.00", "Stock photo licence", True, False),
    ("pc-firmware", "software", 0.10, "249.00", "Logic analyser software licence", True, False),
    ("pc-firmware", "travel", 0.55, "420.00", "Flight to Portland for the hardware review", True, False),
    ("pc-firmware", "mileage", 0.56, 120, "Rental car to the test lab", True, False),
    ("tc-audit", "travel", 0.40, "310.00", "Flight to Winnipeg for the audit interviews", True, False),
    ("tc-audit", "meals", 0.41, "64.15", "Meals during the audit interviews", True, False),
    ("sk-catalogue", "travel", 0.60, "18400", "Train to Kanazawa for the photo shoot", True, False),
    ("sk-catalogue", "meals", 0.61, "3200", "Lunch on the shoot day", False, False),
    ("fb-pitch", "travel", 0.85, "85.00", "Train to Graz for the pitch meeting", False, False),
    ("fb-spring", "software", 0.10, "15.00", "Font licence for the catalogue", True, False),
]

# Invoices built from tracked time and expenses ("projects"), or free-form ("lines").
# States: draft; open (sent, unpaid); partial (sent, half paid); paid; paid_twice; closed.
INVOICES = [
    {"key": "bw-1", "client": "bw", "projects": ["bw-platform", "bw-dash"], "period": ("m1", "m1"), "time": "task",
     "expenses": "category", "tax": "23", "po": "BW-4471", "subject": "Data platform and dashboards", "term": "net 30", "state": "paid"},
    {"key": "bw-2", "client": "bw", "projects": ["bw-platform", "bw-dash"], "period": ("m2", "m2"), "time": "task",
     "expenses": "category", "tax": "23", "po": "BW-4471", "subject": "Data platform and dashboards", "term": "net 30", "state": "partial"},
    {"key": "bw-3", "client": "bw", "projects": ["bw-platform", "bw-dash"], "period": ("m3", "m3"), "time": "task",
     "expenses": "category", "tax": "23", "subject": "Data platform and dashboards", "term": "net 30", "state": "draft",
     "notes": "Waiting for the new purchase order number."},
    {"key": "kf-1", "client": "kf", "projects": ["kf-portal"], "period": ("m1", "m2"), "time": "people", "expenses": "project",
     "tax": "20", "discount": "5", "subject": "Client portal", "term": "net 30", "state": "paid"},
    {"key": "kf-2", "client": "kf", "issue_after": "m2", "tax": "20", "subject": "Website refresh, first milestone", "term": "net 30",
     "state": "open", "lines": [("Service", "Website refresh: first milestone, half of the fixed fee", 1, "4250", "kf-site"),
                                ("Product", "Stock photography, extended licence", 3, "40", "kf-site")]},
    {"key": "pc-1", "client": "pc", "projects": ["pc-firmware", "pc-support"], "period": ("m1", "m2"), "time": "detailed",
     "expenses": "detailed", "tax": "8.25", "tax2": "2", "subject": "Firmware build tools and support", "term": "net 30",
     "state": "paid_twice"},
    {"key": "tc-1", "client": "tc", "projects": ["tc-audit"], "period": ("m1", "m2"), "time": "task", "expenses": "category",
     "tax": "5", "tax2": "7", "subject": "Booking system audit", "term": "net 15", "state": "open"},
    {"key": "sk-1", "client": "sk", "projects": ["sk-catalogue"], "period": ("m1", "m3"), "time": "task", "expenses": "category",
     "tax": "10", "discount": "10", "subject": "Online catalogue", "term": "net 30", "state": "paid"},
    {"key": "sk-2", "client": "sk", "issue_after": "m2", "tax": "10", "subject": "Rush printing", "term": "net 30", "state": "closed",
     "lines": [("Product", "Rush printing of sample tiles", 1, "45000", "sk-catalogue")]},
    {"key": "fb-1", "client": "fb", "projects": ["fb-spring"], "period": ("m1", "m1"), "time": "project", "expenses": "project",
     "tax": "20", "subject": "Spring catalogue", "term": "net 30", "state": "paid"},
]

# Time's importer keeps estimates as read-only documents, so a few are filled too.
ESTIMATES = [
    {"client": "bw", "subject": "Phase 2: data quality checks", "tax": "23", "issue": ("m3", 5), "state": "accepted",
     "lines": [("Service", "Data quality rules and alerts", 60, "120"), ("Service", "Training session for the analytics team", 1, "900")]},
    {"client": "tc", "subject": "Patient reminder messages", "tax": "5", "tax2": "7", "issue": ("m2", 20), "state": "declined",
     "lines": [("Service", "Reminder workflow and message templates", 45, "140")]},
    {"client": "pc", "subject": "Sensor calibration rig", "tax": "8.25", "issue": None, "state": "draft",
     "lines": [("Service", "Calibration software", 80, "145"), ("Product", "Test fixtures", 4, "320")]},
]

ROLE = "Consulting"

TASK = {t["key"]: t for t in TASKS}
CATEGORY = {c["key"]: c for c in CATEGORIES}
CLIENT = {c["key"]: c for c in CLIENTS}
PROJECT = {p["key"]: p for p in PROJECTS}

# ------------------------------------------------------------------------------------------ helpers


def D(value):
    return value if isinstance(value, Decimal) else Decimal(str(value))


def money(value, currency):
    return D(value).quantize(Decimal(1) if currency in ZERO_DECIMAL else Decimal("0.01"), ROUND_HALF_UP)


def fmt(value, currency):
    return f"{money(value, currency):,.0f}" if currency in ZERO_DECIMAL else f"{money(value, currency):,.2f}"


def clean(d):
    return {k: v for k, v in d.items() if v is not None}


def month_start(d):
    return d.replace(day=1)


def add_months(d, n):
    y, m = divmod(d.month - 1 + n, 12)
    return dt.date(d.year + y, m + 1, 1)


def month_end(d):
    return add_months(d, 1) - dt.timedelta(days=1)


def clock(minutes):
    h, m = divmod(minutes, 60)
    return f"{h % 12 or 12}:{m:02d}{'am' if h < 12 else 'pm'}"


def tiny_pdf(lines):
    """A one-page PDF receipt, so the import has a real file to download."""
    esc = lambda s: s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
    text = "BT /F1 12 Tf 60 740 Td 18 TL " + " ".join(f"({esc(line)}) '" for line in lines) + " ET"
    objects = [
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        f"<< /Length {len(text)} >>\nstream\n{text}\nendstream",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    out = b"%PDF-1.4\n"
    offsets = []
    for i, body in enumerate(objects, 1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n{body}\nendobj\n".encode("latin-1")
    xref = len(out)
    out += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode()
    out += b"".join(f"{o:010d} 00000 n \n".encode() for o in offsets)
    out += f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return out


def _json_default(o):
    if isinstance(o, Decimal):
        return float(o)
    if isinstance(o, dt.date):
        return o.isoformat()
    raise TypeError(f"not JSON: {o!r}")


# ------------------------------------------------------------------------------------------ the API


class HarvestError(Exception):
    def __init__(self, method, url, status, body):
        self.status = status
        try:
            parsed = json.loads(body)
            message = parsed.get("message") or parsed.get("error_description") or parsed.get("error") or body
        except ValueError:
            message = body
        super().__init__(f"{method} {url} -> {status}: {str(message)[:500]}")


class Harvest:
    """Bearer token, Harvest-Account-Id and a User-Agent on every request; stays under Harvest's
    rate limits (100 requests per 15 seconds, reports 100 per 15 minutes) and waits out a 429
    for as long as Retry-After says. In a dry run it never touches the network."""

    LIMITS = {"general": (95, 15.0), "reports": (95, 900.0)}

    def __init__(self, token, account_id, dry_run=False, verbose=False):
        self.token, self.account_id = token, account_id
        self.dry, self.verbose = dry_run, verbose
        self.sent = {name: collections.deque() for name in self.LIMITS}
        self.counts = collections.Counter()
        self.requests = 0
        self.fake_id = 0

    def _wait_turn(self, bucket):
        limit, window = self.LIMITS[bucket]
        sent = self.sent[bucket]
        while True:
            now = time.monotonic()
            while sent and now - sent[0] >= window:
                sent.popleft()
            if len(sent) < limit:
                sent.append(now)
                return
            time.sleep(window - (now - sent[0]) + 0.05)

    def request(self, method, path, body=None, params=None, multipart=None):
        if self.dry:
            raise RuntimeError(f"dry run tried to call Harvest: {method} {path}")
        url = path if path.startswith("https://") else API + path
        if params:
            url += ("&" if "?" in url else "?") + urllib.parse.urlencode(clean(params))
        headers = {
            "Authorization": f"Bearer {self.token}",
            "Harvest-Account-Id": self.account_id,
            "User-Agent": USER_AGENT,
            "Accept": "application/json",
        }
        data = None
        if multipart is not None:
            data, headers["Content-Type"] = multipart
        elif body is not None:
            data = json.dumps(body, default=_json_default).encode()
            headers["Content-Type"] = "application/json"
        bucket = "reports" if "/v2/reports/" in url else "general"
        for attempt in range(8):
            self._wait_turn(bucket)
            self.requests += 1
            try:
                with urllib.request.urlopen(urllib.request.Request(url, data=data, method=method, headers=headers), timeout=60) as r:
                    raw = r.read()
                    return json.loads(raw, parse_float=Decimal) if raw.strip() else None
            except urllib.error.HTTPError as e:
                text = e.read().decode("utf-8", "replace")
                if e.code == 429:
                    try:
                        wait = float(e.headers.get("Retry-After") or 15)
                    except ValueError:
                        wait = 15
                    print(f"  Harvest asked us to slow down; waiting {wait:.0f} s", flush=True)
                    time.sleep(wait + 1)
                    continue
                # Only retry writes when Harvest surely didn't process them, so nothing is created twice.
                retryable = (502, 503, 504) if method == "POST" else (500, 502, 503, 504)
                if e.code in retryable and attempt < 4:
                    time.sleep(2 ** attempt)
                    continue
                raise HarvestError(method, url, e.code, text) from None
            except urllib.error.URLError:
                if method != "POST" and attempt < 4:
                    time.sleep(2 ** attempt)
                    continue
                raise
        raise HarvestError(method, url, 429, "still rate-limited after several waits")

    def get(self, path, params=None):
        return self.request("GET", path, params=params)

    def all(self, path, key, params=None):
        """Every page of a list, following links.next."""
        items, url, query = [], path, {"per_page": 2000, **(params or {})}
        while url:
            page = self.request("GET", url, params=query)
            items.extend(page.get(key, []))
            url, query = (page.get("links") or {}).get("next"), None
        return items

    def create(self, what, path, body, label, multipart=None):
        if self.dry:
            self.counts[what] += 1
            self.fake_id -= 1
            if self.verbose or what != "time entry":
                print(f"  would create {what}: {label}")
            return {"id": self.fake_id, **body}
        made = self.request("POST", path, body, multipart=multipart)
        self.counts[what] += 1
        return made

    def update(self, what, path, body, label):
        self.counts[what + " (updated)"] += 1
        if self.dry:
            print(f"  would update {what}: {label}")
            return dict(body)
        return self.request("PATCH", path, body)

    def event(self, what, path, event_type, label):
        """Marks an invoice or estimate sent, accepted, declined or closed. Sends no email."""
        self.counts[f"{what} marked {event_type}"] += 1
        if self.dry:
            print(f"  would mark {what} {event_type}: {label}")
            return None
        return self.request("POST", path, {"event_type": event_type})

    def delete(self, what, path, label):
        self.counts[what + " deleted"] += 1
        print(f"  deleting {what}: {label}", flush=True)
        return self.request("DELETE", path)


# ------------------------------------------------------------------------------------------ the plan


class Plan:
    """Everything the script creates, worked out before anything is sent. Seeded, and relative to
    today: about three months of weekdays, the oldest just past the 90 days Time imports first."""

    def __init__(self, today, seed, owner_rate):
        self.today = today
        self.owner_rate = D(owner_rate) if owner_rate not in (None, "") else None
        first = add_months(month_start(today), -3)
        self.start = first - dt.timedelta(days=9)
        self.end = today - dt.timedelta(days=1)
        self.periods = {
            "m1": (self.start, month_end(first)),
            "m2": (add_months(first, 1), month_end(add_months(first, 1))),
            "m3": (add_months(first, 2), month_end(add_months(first, 2))),
        }
        self.weekdays = [self.start + dt.timedelta(days=i) for i in range((self.end - self.start).days + 1)]
        self.weekdays = [d for d in self.weekdays if d.weekday() < 5]
        rnd = random.Random(seed)
        self.entries = self._time(rnd)
        self.expenses = [self._expense(*x) for x in EXPENSES]
        self.invoices = self._invoices()
        self.estimates = self._estimates()

    def frac(self, day):
        return (day - self.start).days / max(1, (self.end - self.start).days)

    def at(self, frac):
        """The weekday nearest to a point in the three months, never in the future."""
        day = self.start + dt.timedelta(days=round(frac * (self.end - self.start).days))
        while day.weekday() > 4:
            day -= dt.timedelta(days=1)
        return max(self.start, min(day, self.end))

    def _time(self, rnd):
        days = self.weekdays
        off_from = rnd.randrange(len(days) // 3, 2 * len(days) // 3)
        days_off = set(days[off_from:off_from + 3])  # a short break
        entries, issue = [], 4100
        for day in days:
            if day in days_off:
                continue
            active = [p for p in PROJECTS if p["window"][0] <= self.frac(day) <= p["window"][1]
                      and day.weekday() in p.get("weekdays", range(5))]
            if not active:
                continue
            chosen, pool = [], list(active)
            for _ in range(min(len(pool), rnd.choice((2, 3, 3, 4)))):
                p = rnd.choices(pool, [q["weight"] for q in pool])[0]
                pool.remove(p)
                chosen.append(p)
            quarters = rnd.choice((22, 24, 26, 28, 28, 30, 32))  # 5.5 to 8 hours a day
            parts = [2] * len(chosen)
            for _ in range(quarters - 2 * len(chosen)):
                parts[rnd.randrange(len(chosen))] += 1
            minute, lunch = 9 * 60, False
            for p, q in zip(chosen, parts):
                task = rnd.choices(p["tasks"], [t["weight"] for t in p["tasks"]])[0]["task"]
                notes = rnd.choice(NOTES[task]) if rnd.random() > 0.1 else ""
                ext = None
                if p["key"] == "pc-firmware" and task == "dev" and rnd.random() < 0.3:
                    issue += rnd.randrange(1, 9)
                    ext = {"id": str(issue), "group_id": "firmware-tools",
                           "permalink": f"https://tracker.example.com/firmware-tools/issues/{issue}"}
                if not lunch and minute >= 12 * 60:
                    minute, lunch = minute + 45, True
                start, minute = minute, minute + q * 15
                entries.append({"project": p["key"], "task": task, "date": day, "hours": Decimal(q) / 4,
                                "notes": notes, "ext": ext, "start": clock(start), "end": clock(minute)})
        return entries

    def _expense(self, project, category, frac, cost, notes, billable, receipt):
        p = PROJECT[project]
        day = self.at(min(max(frac, p["window"][0]), p["window"][1]))
        cat = CATEGORY[category]
        units = cost if cat.get("unit_price") else None
        amount = D(units) * D(cat["unit_price"]) if units else D(cost)
        return {"project": project, "category": category, "date": day, "units": units, "cost": None if units else D(cost),
                "amount": money(amount, CLIENT[p["client"]]["currency"]), "notes": notes, "billable": billable, "receipt": receipt}

    def _invoices(self):
        out = []
        for inv in INVOICES:
            inv = dict(inv)
            if "period" in inv:
                inv["from"], inv["to"] = self.periods[inv["period"][0]][0], self.periods[inv["period"][1]][1]
                issue = inv["to"] + dt.timedelta(days=1)
            else:
                issue = self.periods[inv["issue_after"]][1] + dt.timedelta(days=1)
            inv["issue_date"] = min(issue, self.today)
            out.append(inv)
        out.sort(key=lambda i: (i["issue_date"], i["key"]))
        for n, inv in enumerate(out, 1):
            inv["number"] = f"HRT-{n:04d}"
        return out

    def _estimates(self):
        out = []
        for n, est in enumerate(ESTIMATES, 1):
            est = dict(est)
            est["number"] = f"HRT-E-{n:03d}"
            est["issue_date"] = self.today if est["issue"] is None else min(
                self.periods[est["issue"][0]][0] + dt.timedelta(days=est["issue"][1]), self.today)
            out.append(est)
        return out

    # What Harvest should compute, for the dry run and as a cross-check.

    def billable(self, e):
        p = PROJECT[e["project"]]
        spec = next(t for t in p["tasks"] if t["task"] == e["task"])
        return p["billable"] and spec.get("billable", TASK[e["task"]]["billable"])

    def rate(self, e):
        p = PROJECT[e["project"]]
        spec = next(t for t in p["tasks"] if t["task"] == e["task"])
        if p["bill_by"] == "Project":
            return D(p["hourly_rate"])
        if p["bill_by"] == "Tasks":
            return D(spec.get("rate") or TASK[e["task"]]["rate"] or 0)
        if p["bill_by"] == "People":
            return D(p["user"]["hourly_rate"]) if not p["user"].get("use_default_rates", True) else (self.owner_rate or Decimal(0))
        return Decimal(0)

    def invoice_amount(self, inv):
        """Subtotal, less the discount, plus each tax on what's left. VERIFY: that Harvest takes
        the discount off before tax; the live run reads Harvest's own amounts instead."""
        currency = CLIENT[inv["client"]]["currency"]
        if "lines" in inv:
            subtotal = sum(D(q) * D(u) for _, _, q, u, _ in inv["lines"])
        else:
            inside = lambda x: x["project"] in inv["projects"] and inv["from"] <= x["date"] <= inv["to"]
            subtotal = sum((e["hours"] * self.rate(e) for e in self.entries if inside(e) and self.billable(e)), Decimal(0))
            if inv.get("expenses"):
                subtotal += sum((x["amount"] for x in self.expenses if inside(x) and x["billable"]), Decimal(0))
        subtotal = money(subtotal, currency)
        discount = money(subtotal * D(inv.get("discount", 0)) / 100, currency)
        base = subtotal - discount
        taxes = sum(money(base * D(inv.get(k, 0)) / 100, currency) for k in ("tax", "tax2"))
        return base + taxes


# ------------------------------------------------------------------------------------------ fill


def fill(api, plan, owner, timestamps, running_timer):
    ids = collections.defaultdict(dict)
    oid = owner["id"]

    print("Tasks, expense categories and a role")
    for t in TASKS:
        body = clean({"name": t["name"] + MARK, "billable_by_default": t["billable"], "default_hourly_rate": t["rate"],
                      "is_default": False, "is_active": t.get("active", True)})
        ids["task"][t["key"]] = api.create("task", "/tasks", body, body["name"])["id"]
    for c in CATEGORIES:
        body = clean({"name": c["name"] + MARK, "unit_name": c.get("unit_name"), "unit_price": c.get("unit_price"),
                      "is_active": c.get("active", True)})
        ids["category"][c["key"]] = api.create("expense category", "/expense_categories", body, body["name"])["id"]
    try:
        api.create("role", "/roles", {"name": ROLE + MARK, "user_ids": [oid]}, ROLE + MARK)
    except HarvestError as e:  # roles need an administrator; the rest doesn't depend on it
        print(f"  skipped the role: {e}")

    print("Clients and contacts")
    for c in CLIENTS:
        body = {"name": c["name"] + MARK, "currency": c["currency"], "address": c["address"], "is_active": True}
        ids["client"][c["key"]] = cid = api.create("client", "/clients", body, f"{body['name']}, {c['currency']}")["id"]
        for person in c["contacts"]:
            api.create("contact", "/contacts", {"client_id": cid, **person}, f"{person['first_name']} {person['last_name']}")

    print("Projects, task assignments and user assignments")
    for p in PROJECTS:
        currency = CLIENT[p["client"]]["currency"]
        days = [e["date"] for e in plan.entries if e["project"] == p["key"]] or [plan.start]
        body = clean({
            "client_id": ids["client"][p["client"]], "name": p["name"], "code": p["code"], "is_active": True,
            "is_billable": p["billable"], "is_fixed_fee": p.get("fixed_fee", False), "fee": p.get("fee"),
            "bill_by": p["bill_by"], "hourly_rate": p.get("hourly_rate"),
            "budget_by": p["budget_by"], "budget": p.get("budget"), "cost_budget": p.get("cost_budget"),
            "cost_budget_include_expenses": p.get("include_expenses", False), "budget_is_monthly": p.get("monthly", False),
            "notify_when_over_budget": bool(p.get("notify")), "over_budget_notification_percentage": p.get("notify"),
            "show_budget_to_all": p.get("show_budget", False), "notes": p["notes"],
            "starts_on": min(days).isoformat(), "ends_on": max(days).isoformat() if p.get("ends") else None,
        })
        ids["project"][p["key"]] = pid = api.create("project", "/projects", body, f"{p['name']} ({p['bill_by']}, budget by {p['budget_by']}, {currency})")["id"]

        # VERIFY: the docs don't say whether Harvest assigns default tasks or the creator to a new
        # project, so look first and update what's there instead of adding it twice.
        have_tasks = {} if api.dry else {a["task"]["id"]: a["id"] for a in api.all(f"/projects/{pid}/task_assignments", "task_assignments")}
        for t in p["tasks"]:
            billable = p["billable"] and t.get("billable", TASK[t["task"]]["billable"])
            rate = (t.get("rate") or TASK[t["task"]]["rate"]) if p["bill_by"] == "Tasks" and billable else None
            # billable defaults to false on a new task assignment, so it is always sent.
            body = clean({"is_active": True, "billable": billable, "hourly_rate": rate, "budget": t.get("budget")})
            label = f"{p['name']} / {TASK[t['task']]['name']}" + (f" at {rate}" if rate else "") + (" (not billable)" if not billable else "")
            tid = ids["task"][t["task"]]
            if tid in have_tasks:
                api.update("task assignment", f"/projects/{pid}/task_assignments/{have_tasks[tid]}", body, label)
            else:
                api.create("task assignment", f"/projects/{pid}/task_assignments", {"task_id": tid, **body}, label)
        have_users = {} if api.dry else {a["user"]["id"]: a["id"] for a in api.all(f"/projects/{pid}/user_assignments", "user_assignments")}
        u = p["user"]
        body = clean({"is_active": True, "is_project_manager": u.get("is_project_manager", False),
                      "use_default_rates": u.get("use_default_rates", True), "hourly_rate": u.get("hourly_rate"), "budget": u.get("budget")})
        label = f"{p['name']} / the account owner" + (f" at {u['hourly_rate']}" if u.get("hourly_rate") else "")
        if oid in have_users:
            api.update("user assignment", f"/projects/{pid}/user_assignments/{have_users[oid]}", body, label)
        else:
            api.create("user assignment", f"/projects/{pid}/user_assignments", {"user_id": oid, **body}, label)

    print(f"Time entries ({len(plan.entries)}, {'start and end times' if timestamps else 'durations'})", flush=True)
    send_refs = True
    for i, e in enumerate(plan.entries, 1):
        body = {"user_id": oid, "project_id": ids["project"][e["project"]], "task_id": ids["task"][e["task"]],
                "spent_date": e["date"].isoformat(), "notes": e["notes"]}
        if timestamps:  # VERIFY: the time format Harvest wants for a 24-hour company clock
            body.update(started_time=e["start"], ended_time=e["end"])
        else:
            body["hours"] = e["hours"]
        label = f"{e['date']} {PROJECT[e['project']]['name']} / {TASK[e['task']]['name']} {e['hours']} h"
        if e["ext"] and send_refs:
            try:
                api.create("time entry", "/time_entries", {**body, "external_reference": e["ext"]}, label)
                continue
            except HarvestError as err:  # VERIFY: external references may be reserved for integrations
                if err.status != 422:
                    raise
                print(f"  Harvest refused an external reference ({err}); sending the rest without one")
                send_refs = False
        api.create("time entry", "/time_entries", body, label)
        if not api.dry and i % 50 == 0:
            print(f"  {i} of {len(plan.entries)}", flush=True)

    print("Expenses")
    for n, x in enumerate(plan.expenses):
        p = PROJECT[x["project"]]
        currency = CLIENT[p["client"]]["currency"]
        body = clean({"user_id": oid, "project_id": ids["project"][x["project"]], "expense_category_id": ids["category"][x["category"]],
                      "spent_date": x["date"].isoformat(), "units": x["units"], "total_cost": x["cost"], "notes": x["notes"],
                      "billable": x["billable"]})
        label = (f"{x['date']} {p['name']} / {CATEGORY[x['category']]['name']}: "
                 + (f"{x['units']} {CATEGORY[x['category']]['unit_name']}" if x["units"] else f"{fmt(x['cost'], currency)} {currency}")
                 + ("" if x["billable"] else " (not billable)") + (", with a receipt" if x["receipt"] else ""))
        multipart = None
        if x["receipt"]:  # VERIFY: multipart field names and boolean spelling for a receipt upload
            pdf = tiny_pdf(["Receipt (made-up test data)", x["notes"], f"Date: {x['date']}", f"Amount: {fmt(x['amount'], currency)} {currency}"])
            multipart = _multipart({k: str(v).lower() if isinstance(v, bool) else str(v) for k, v in body.items()},
                                   ("receipt", f"receipt-{n + 1}.pdf", "application/pdf", pdf))
        api.create("expense", "/expenses", body, label, multipart=multipart)

    print("Invoices and payments")
    for inv in plan.invoices:
        currency = CLIENT[inv["client"]]["currency"]
        body = clean({"client_id": ids["client"][inv["client"]], "number": inv["number"], "purchase_order": inv.get("po"),
                      "tax": inv.get("tax"), "tax2": inv.get("tax2"), "discount": inv.get("discount"),
                      "subject": inv["subject"], "notes": inv.get("notes"), "currency": currency,
                      "issue_date": inv["issue_date"].isoformat(), "payment_term": inv["term"]})
        if "lines" in inv:
            body["line_items"] = [{"kind": k, "description": d, "quantity": q, "unit_price": D(u), "taxed": True,
                                   "taxed2": bool(inv.get("tax2")), "project_id": ids["project"][proj]} for k, d, q, u, proj in inv["lines"]]
            what = "free-form"
        else:
            imp = {"project_ids": [ids["project"][k] for k in inv["projects"]],
                   "time": {"summary_type": inv["time"], "from": inv["from"].isoformat(), "to": inv["to"].isoformat()}}
            if inv.get("expenses"):
                imp["expenses"] = {"summary_type": inv["expenses"], "from": inv["from"].isoformat(), "to": inv["to"].isoformat()}
            body["line_items_import"] = imp
            what = f"time {inv['from']} to {inv['to']}" + (" and expenses" if inv.get("expenses") else "")
        extras = ", ".join(f"{k} {inv[k]}%" for k in ("tax", "tax2", "discount") if inv.get(k))
        label = f"{inv['number']} {CLIENT[inv['client']]['name']}, {currency}, {what}, {extras}, will be {inv['state']}"
        obj = api.create("invoice", "/invoices", body, label)
        iid = obj["id"]
        if api.dry:
            amount = plan.invoice_amount(inv)
        else:
            # VERIFY: whether imported lines come taxed; make sure they are, as the invoice says.
            untaxed = [line for line in obj.get("line_items", [])
                       if (inv.get("tax") and not line.get("taxed")) or (inv.get("tax2") and not line.get("taxed2"))]
            if untaxed:
                obj = api.update("invoice", f"/invoices/{iid}", {"line_items": [
                    {"id": line["id"], "taxed": bool(inv.get("tax")), "taxed2": bool(inv.get("tax2"))} for line in untaxed]},
                    f"{inv['number']}: tax on imported lines")
            amount = D(obj["amount"])
        inv["amount"] = amount
        if inv["state"] == "draft":
            continue
        api.event("invoice", f"/invoices/{iid}/messages", "send", inv["number"])
        paid = lambda days: min(inv["issue_date"] + dt.timedelta(days=days), plan.today).isoformat()
        payments = {"paid": [(amount, 14, "Bank transfer")],
                    "partial": [(money(amount / 2, currency), 10, "First half, by bank transfer")],
                    "paid_twice": [(money(amount * Decimal("0.6"), currency), 7, "Deposit"),
                                   (amount - money(amount * Decimal("0.6"), currency), 21, "Balance")]}.get(inv["state"], [])
        for value, days, note in payments:
            # send_thank_you defaults to true, which would email the client's contacts.
            api.create("payment", f"/invoices/{iid}/payments",
                       {"amount": value, "paid_date": paid(days), "notes": note, "send_thank_you": False},
                       f"{inv['number']}: {fmt(value, currency)} {currency} on {paid(days)}")
        if inv["state"] == "closed":
            api.event("invoice", f"/invoices/{iid}/messages", "close", f"{inv['number']} (written off)")

    print("Estimates")
    for est in plan.estimates:
        currency = CLIENT[est["client"]]["currency"]
        body = clean({"client_id": ids["client"][est["client"]], "number": est["number"], "subject": est["subject"],
                      "tax": est.get("tax"), "tax2": est.get("tax2"), "currency": currency, "issue_date": est["issue_date"].isoformat(),
                      "line_items": [{"kind": k, "description": d, "quantity": q, "unit_price": D(u), "taxed": True,
                                      "taxed2": bool(est.get("tax2"))} for k, d, q, u in est["lines"]]})
        eid = api.create("estimate", "/estimates", body, f"{est['number']} {CLIENT[est['client']]['name']}, will be {est['state']}")["id"]
        if est["state"] != "draft":
            api.event("estimate", f"/estimates/{eid}/messages", "send", est["number"])
        if est["state"] in ("accepted", "declined"):
            api.event("estimate", f"/estimates/{eid}/messages", {"accepted": "accept", "declined": "decline"}[est["state"]], est["number"])

    print("Finishing")
    for p in PROJECTS:
        if p.get("archive"):
            api.update("project", f"/projects/{ids['project'][p['key']]}", {"is_active": False}, f"{p['name']}: archived")
    if running_timer:
        p = PROJECTS[0]
        api.create("time entry", "/time_entries", {"user_id": oid, "project_id": ids["project"][p["key"]],
                                                   "task_id": ids["task"]["dev"], "spent_date": plan.today.isoformat(),
                                                   "notes": "Running timer"}, f"{plan.today} {p['name']}: a running timer")
    return ids


def _multipart(fields, file):
    boundary = uuid.uuid4().hex
    out = b""
    for name, value in fields.items():
        out += f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode()
    name, filename, ctype, data = file
    out += f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; filename="{filename}"\r\nContent-Type: {ctype}\r\n\r\n'.encode()
    out += data + f"\r\n--{boundary}--\r\n".encode()
    return out, f"multipart/form-data; boundary={boundary}"


# ------------------------------------------------------------------------------------------ find and delete


def marked(api):
    """What an earlier run created, found by the marker."""
    found = {
        "clients": [c for c in api.all("/clients", "clients") if c["name"].endswith(MARK)],
        "tasks": [t for t in api.all("/tasks", "tasks") if t["name"].endswith(MARK)],
        "expense categories": [c for c in api.all("/expense_categories", "expense_categories") if c["name"].endswith(MARK)],
    }
    try:
        found["roles"] = [r for r in api.all("/roles", "roles") if r["name"].endswith(MARK)]
    except HarvestError:
        found["roles"] = []
    return found


def delete_all(api, assume_yes):
    found = marked(api)
    per_client = []
    for c in found["clients"]:
        cid = c["id"]
        per_client.append((c, api.all("/invoices", "invoices", {"client_id": cid}), api.all("/estimates", "estimates", {"client_id": cid}),
                           api.all("/projects", "projects", {"client_id": cid}), api.all("/contacts", "contacts", {"client_id": cid})))
    counts = {k: len(v) for k, v in found.items()}
    for i, name in enumerate(("invoices", "estimates", "projects", "contacts"), 1):
        counts[name] = sum(len(row[i]) for row in per_client)
    if not any(counts.values()):
        print(f"Nothing marked{MARK} was found; there's nothing to delete.")
        return
    print("Found what an earlier run created: " + ", ".join(f"{n} {k}" for k, n in counts.items() if n))
    print("Deleting a project also deletes its time entries and expenses.")
    if not confirm("Delete all of it?", assume_yes):
        print("Nothing was deleted.")
        return
    # Invoices and estimates first, so time and expenses are no longer billed; then projects
    # (with their time and expenses), contacts and clients; then what they used.
    for c, invoices, estimates, projects, contacts in per_client:
        for inv in invoices:
            for pay in api.all(f"/invoices/{inv['id']}/payments", "invoice_payments"):
                api.delete("payment", f"/invoices/{inv['id']}/payments/{pay['id']}", f"{inv.get('number')}: {pay['amount']}")
            api.delete("invoice", f"/invoices/{inv['id']}", f"{inv.get('number')} ({inv['state']})")
        for est in estimates:
            api.delete("estimate", f"/estimates/{est['id']}", est.get("number") or est["id"])
        for p in projects:
            if not p["is_active"]:  # VERIFY: whether Harvest deletes an archived project as it is
                api.request("PATCH", f"/projects/{p['id']}", {"is_active": True})
            api.delete("project", f"/projects/{p['id']}", p["name"])
        for person in contacts:
            api.delete("contact", f"/contacts/{person['id']}", f"{person.get('first_name')} {person.get('last_name') or ''}".strip())
        api.delete("client", f"/clients/{c['id']}", c["name"])
    for t in found["tasks"]:
        api.delete("task", f"/tasks/{t['id']}", t["name"])
    for cat in found["expense categories"]:
        api.delete("expense category", f"/expense_categories/{cat['id']}", cat["name"])
    for r in found["roles"]:
        api.delete("role", f"/roles/{r['id']}", r["name"])
    print("Done: " + ", ".join(f"{n} {k}" for k, n in sorted(api.counts.items())))


# ------------------------------------------------------------------------------------------ totals


def planned_totals(plan):
    rows = {c["key"]: {"client": c["name"] + MARK, "currency": c["currency"], "hours": Decimal(0), "billable_hours": Decimal(0),
                       "billable_amount": Decimal(0), "invoices": 0, "invoiced": Decimal(0)} for c in CLIENTS}
    for e in plan.entries:
        p = PROJECT[e["project"]]
        row = rows[p["client"]]
        row["hours"] += e["hours"]
        if plan.billable(e):
            row["billable_hours"] += e["hours"]
            # Harvest's time report leaves fixed-fee amounts out unless asked (include_fixed_fee),
            # and Time's verification asks it the same way.
            if not p.get("fixed_fee"):
                row["billable_amount"] += e["hours"] * plan.rate(e)
    for inv in plan.invoices:
        row = rows[inv["client"]]
        row["invoices"] += 1
        row["invoiced"] += plan.invoice_amount(inv)
    for row in rows.values():
        row["billable_amount"] = money(row["billable_amount"], row["currency"])
    return {"source": "planned (dry run: what Harvest should show)", "from": plan.start.isoformat(), "to": plan.today.isoformat(),
            "clients": list(rows.values())}


def harvest_totals(api, start, end):
    """Harvest's own figures, asked for the way Time's verification asks."""
    ours = {c["id"]: c for c in api.all("/clients", "clients") if c["name"].endswith(MARK)}
    span = {"from": start.strftime("%Y%m%d"), "to": end.strftime("%Y%m%d")}
    rows = {cid: {"client": c["name"], "currency": c["currency"], "hours": Decimal(0), "billable_hours": Decimal(0),
                  "billable_amount": Decimal(0), "invoices": 0, "invoiced": Decimal(0), "due": Decimal(0), "invoice_states": {}}
            for cid, c in ours.items()}
    for r in api.all("/reports/time/clients", "results", span):
        if r["client_id"] in rows:
            row = rows[r["client_id"]]
            row["hours"] += D(r["total_hours"])
            row["billable_hours"] += D(r["billable_hours"])
            row["billable_amount"] += D(r.get("billable_amount") or 0)
    months = []
    m = month_start(start)
    while m <= end:
        to = min(month_end(m), end)
        for r in api.all("/reports/time/projects", "results", {"from": max(m, start).strftime("%Y%m%d"), "to": to.strftime("%Y%m%d")}):
            if r["client_id"] in rows:
                months.append({"client": r["client_name"], "project": r["project_name"], "month": m.strftime("%Y-%m"),
                               "currency": r.get("currency"), "hours": D(r["total_hours"]), "billable_hours": D(r["billable_hours"]),
                               "billable_amount": D(r.get("billable_amount") or 0)})
        m = add_months(m, 1)
    invoices = []
    for cid, row in rows.items():
        for inv in api.all("/invoices", "invoices", {"client_id": cid}):
            row["invoices"] += 1
            row["invoiced"] += D(inv["amount"])
            row["due"] += D(inv["due_amount"])
            row["invoice_states"][inv["state"]] = row["invoice_states"].get(inv["state"], 0) + 1
            invoices.append({"client": row["client"], "number": inv.get("number"), "currency": inv["currency"], "state": inv["state"],
                             "amount": D(inv["amount"]), "due_amount": D(inv["due_amount"]), "tax_amount": inv.get("tax_amount"),
                             "tax2_amount": inv.get("tax2_amount"), "discount_amount": inv.get("discount_amount")})
    return {"source": "Harvest (time report and invoices, read back after the fill)", "from": start.isoformat(), "to": end.isoformat(),
            "clients": sorted(rows.values(), key=lambda r: r["client"]),
            "projects_by_month": sorted(months, key=lambda r: (r["client"], r["project"], r["month"])), "invoices": invoices}


def by_currency(clients):
    out = {}
    for row in clients:
        cur = out.setdefault(row["currency"], {"currency": row["currency"], "hours": Decimal(0), "billable_hours": Decimal(0),
                                               "billable_amount": Decimal(0), "invoices": 0, "invoiced": Decimal(0)})
        for k in ("hours", "billable_hours", "billable_amount", "invoices", "invoiced"):
            cur[k] += row[k]
    return sorted(out.values(), key=lambda r: r["currency"])


def report(totals, path):
    totals["currencies"] = by_currency(totals["clients"])
    print(f"\nTotals to compare with Time's verification screen. Source: {totals['source']}.")
    print(f"Time entries from {totals['from']} to {totals['to']}.")
    head = f"{'Client':<36}{'Cur':<5}{'Hours':>9}{'Billable h':>12}{'Billable amount':>17}{'Invoices':>10}{'Invoiced':>15}"
    print(head)
    print("-" * len(head))
    for r in totals["clients"]:
        print(f"{r['client']:<36}{r['currency']:<5}{r['hours']:>9.2f}{r['billable_hours']:>12.2f}"
              f"{fmt(r['billable_amount'], r['currency']):>17}{r['invoices']:>10}{fmt(r['invoiced'], r['currency']):>15}")
    print("-" * len(head))
    for r in totals["currencies"]:
        print(f"{'All clients in ' + r['currency']:<36}{r['currency']:<5}{r['hours']:>9.2f}{r['billable_hours']:>12.2f}"
              f"{fmt(r['billable_amount'], r['currency']):>17}{r['invoices']:>10}{fmt(r['invoiced'], r['currency']):>15}")
    print("Billable amounts leave out the fixed-fee project, as Harvest's time report does by default.")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(totals, f, indent=2, default=str)
    print(f"Saved to {path}")


# ------------------------------------------------------------------------------------------ main


def confirm(question, assume_yes):
    if assume_yes:
        return True
    if not sys.stdin.isatty():
        print("Not asking without a terminal; add --yes to go ahead.")
        return False
    return input(f"{question} [y/N] ").strip().lower() in ("y", "yes")


def main():
    ap = argparse.ArgumentParser(description="Fill a Harvest test account with made-up data for Time's Harvest import.")
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--delete", action="store_true", help=f"delete everything an earlier run created (marked{MARK})")
    mode.add_argument("--totals", action="store_true", help="only print and save Harvest's totals for the test data")
    ap.add_argument("--dry-run", action="store_true", help="print what would be created and the planned totals; sends nothing")
    ap.add_argument("--yes", action="store_true", help="don't ask before filling or deleting")
    ap.add_argument("--seed", type=int, default=2026, help="seed for the made-up time entries (default 2026)")
    ap.add_argument("--with-running-timer", action="store_true", help="also leave a timer running today")
    ap.add_argument("--verbose", action="store_true", help="in a dry run, list every time entry too")
    ap.add_argument("--totals-file", help="where to save the totals (default: a new file in the system's temp folder)")
    args = ap.parse_args()

    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    totals_file = args.totals_file or os.path.join(tempfile.gettempdir(), f"harvest-fill-totals-{stamp}{'-dry-run' if args.dry_run else ''}.json")
    today = dt.date.today()

    if args.dry_run:
        print("Dry run: nothing is sent to Harvest, and no token is needed.\n")
        if args.delete or args.totals:
            print(f"--delete finds clients, tasks, expense categories and roles whose names end in '{MARK}', lists them,")
            print("asks, then deletes: payments, invoices, estimates, projects (with their time and expenses),")
            print("contacts, clients, tasks, expense categories, roles. --totals reads Harvest's time report and invoices.")
            return 0
        api = Harvest("", "", dry_run=True, verbose=args.verbose)
        owner = {"id": 0, "first_name": "Account", "last_name": "owner", "default_hourly_rate": None}
        plan = Plan(today, args.seed, None)
        fill(api, plan, owner, timestamps=False, running_timer=args.with_running_timer)
        summary(api, plan)
        report(planned_totals(plan), totals_file)
        print("The person-rate project that uses default rates (Support retainer) shows 0 here: the dry run")
        print("doesn't know the account owner's default hourly rate. A live run reads it.")
        return 0

    token, account = os.environ.get("HARVEST_ACCESS_TOKEN", ""), os.environ.get("HARVEST_ACCOUNT_ID", "")
    if not token or not account:
        print("Set HARVEST_ACCESS_TOKEN and HARVEST_ACCOUNT_ID first (see README.md), or try --dry-run.", file=sys.stderr)
        return 2
    api = Harvest(token, account)
    try:
        if args.delete:
            delete_all(api, args.yes)
            return 0
        if args.totals:
            report(harvest_totals(api, today - dt.timedelta(days=364), today), totals_file)
            return 0

        me = api.get("/users/me")
        company = api.get("/company")
        print(f"Harvest account {account}: {company.get('name')}, signed in as {me.get('first_name')} {me.get('last_name')}.")
        if "administrator" not in (me.get("access_roles") or []):
            print("This person isn't an administrator; invoices, estimates and roles need one.", file=sys.stderr)
            return 2
        found = marked(api)
        if any(found.values()):
            print(f"This account already has test data (names ending in '{MARK}'). Run with --delete first.", file=sys.stderr)
            return 2
        plan = Plan(today, args.seed, me.get("default_hourly_rate"))
        print(f"This adds made-up clients, projects, about {len(plan.entries)} time entries, expenses, invoices and estimates.")
        print("It creates no people and sends no email.")
        if not confirm("Fill this account?", args.yes):
            print("Nothing was created.")
            return 1
        started = time.monotonic()
        fill(api, plan, me, timestamps=bool(company.get("wants_timestamp_timers")), running_timer=args.with_running_timer)
        print(f"\nFilled in {time.monotonic() - started:.0f} s with {api.requests} requests.")
        summary(api, plan)
        harvest = harvest_totals(api, plan.start, today)
        harvest["planned"] = planned_totals(plan)["clients"]
        report(harvest, totals_file)
        planned = {r["client"]: r for r in harvest["planned"]}
        for r in harvest["clients"]:
            want = planned.get(r["client"])
            if want and (r["hours"] != want["hours"] or r["invoices"] != want["invoices"]):
                print(f"Note: for {r['client']}, Harvest shows {r['hours']} hours and {r['invoices']} invoice(s); the plan had "
                      f"{want['hours']} and {want['invoices']}. Time's import compares against Harvest, so Harvest's figures count.")
        if args.with_running_timer:
            print("A timer is running on Data platform rebuild, so its hours keep growing until you stop it.")
        return 0
    except HarvestError as e:
        print(f"\nHarvest said no: {e}", file=sys.stderr)
        print("Anything already created can be removed with --delete; then run again.", file=sys.stderr)
        return 1


def summary(api, plan):
    print("\nCreated" + (" (dry run, so nothing really)" if api.dry else "") + ":")
    for what, n in sorted(api.counts.items()):
        print(f"  {n:>4} {what}")
    print(f"Time entries run from {plan.start} to {plan.end}, weekdays only; Time imports the 90 days up to today first.")


if __name__ == "__main__":
    sys.exit(main())
