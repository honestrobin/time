# 0001. Licence identifier: AGPL-3.0-only

- Status: accepted
- Date: 2026-09-30
- Spec reference: §1.2.5, §16

## Context

The spec says "AGPL-3.0" without choosing between `AGPL-3.0-only` and `AGPL-3.0-or-later`.

## Decision

Use `AGPL-3.0-only` in SPDX headers, `package.json` files and image labels. Contributions are accepted under the DCO, with no CLA.

## Consequences

Moving to a later AGPL version would need the consent of all copyright holders. Because there is no CLA, that is deliberate: nobody can relicense unilaterally. The owner can switch to `-or-later` before outside contributions arrive by editing the headers.
