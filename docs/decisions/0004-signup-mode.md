# 0004. Sign-up policy is configuration, not edition

- Status: accepted
- Date: 2026-09-30
- Spec reference: §1.2.3, §3.3, AT-0.1

## Context

A public cloud needs open sign-up. A self-hosted instance should not let strangers create accounts. The edition flag must not switch product behaviour.

## Decision

`HONESTROBIN_SIGNUP_MODE` (`first_user_only` | `open` | `invite_only`) is independent of `HONESTROBIN_EDITION`. The default is `first_user_only`: the first person to sign up creates the first account, becomes its admin and becomes the instance admin. After that, people join by invitation. The instance admin can still create further accounts. Honest Robin Cloud sets `open`.

## Consequences

Secure by default for self-hosters, and fully configurable. AT-0.1 is covered by `FirstUserTest` (API level) and `e2e/tests/00-first-user.spec.ts` (browser, against docker compose).
