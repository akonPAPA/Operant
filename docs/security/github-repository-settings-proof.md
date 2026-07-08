# GitHub Repository Settings Proof — PR #249

## Scope

This document proves the **current** GitHub repository governance state for
`akonPAPA/Operant` from CLI/API evidence, and identifies evidence-backed gaps as
tracked backlog items (`GH-249-XX`).

What this proves:

- repository identity, visibility, merge policy;
- how the default branch (`main`) is protected (classic protection vs rulesets);
- required status checks vs checks that merely exist/pass;
- pull-request review policy (approvals, stale dismissal, code owners, last-push
  approval, conversation resolution);
- force-push / deletion / linear-history / signed-commit rules;
- bypass actors (admin bypass) on the protection mechanism;
- code scanning / dependency (Dependabot) / secret scanning enablement and open
  alert counts.

What this does **not** prove:

- org-level settings (owner org policies not exposed at repo tier);
- any setting only visible in the GitHub UI and not exposed by the REST API;
- that a bypass was *not* exercised on any historical merge (GitHub does not expose
  a reliable "was ruleset bypassed" flag per merge);
- Snyk / Codacy dashboard-side configuration beyond what the repo APIs return;
- correctness of the *rules themselves* as a security policy (this doc records the
  factual state and flags gaps; it does **not** change any setting).

No repository setting was changed by this PR. No branch protection or ruleset was
bypassed. No admin merge was performed.

## Repository identity

| Field | Value | Evidence |
| --- | --- | --- |
| Repository | `akonPAPA/Operant` | `gh api repos/akonPAPA/Operant` |
| Visibility | **public** (`private: false`) | same |
| Default branch | `main` | same |
| Archived / disabled | `false` / `false` | same |
| Issues / Projects / Wiki | enabled / enabled / disabled | same |
| Evidence timestamp (UTC) | `2026-07-06T13:36:40Z` | `date -u` |
| Actor / tooling | `gh` CLI as `akonPAPA` (keyring auth), token scopes `repo, workflow, read:org, project, gist` | `gh auth status` (token value redacted) |
| Viewer permission | `ADMIN` (`viewerCanAdminister: true`, `current_user_can_bypass: always`) | `gh repo view`, ruleset API |
| Default merge method (UI) | `SQUASH` | `gh repo view --json viewerDefaultMergeMethod` |

## Executive result

**Result: PARTIAL.**

`main` **is** protected — via a **repository ruleset** (not classic branch
protection). The ruleset enforces required PR review, required status checks
(strict), signed commits, linear history, and blocks force-push and deletion. That
is a genuinely strong baseline.

However, several governance controls expected for a production-leaning platform are
**missing or weaker than recommended**:

- **Highest severity gap (P1): admin role can bypass the ruleset `always`**, and
  **stale approvals are NOT dismissed on new pushes**. Combined, an admin can push
  new commits after a 1-approval review and merge without re-review.
- **Semgrep** runs on every PR and passes, but is **not a required check**.
- **Snyk** runs on **schedule/dispatch only — not on PRs — and is not required**, so
  dependency vulnerabilities do not gate merges.
- **Conversation resolution** and **last-push approval** are not required.

Merge governance summary: 1 approving review + code-owner review + strict required
checks (`Backend tests`, `Docker Compose config`, `CodeQL`) are enforced for
non-bypassing actors. Admins (`RepositoryRole` id 5) may bypass all of it.

## Evidence commands

All commands run read-only as an authenticated `gh` user. Token values, auth
headers, and secrets were not printed or captured. (`--jq` used to project only
non-secret fields.)

```
gh auth status                                   # token value redacted
gh repo view akonPAPA/Operant --json nameWithOwner,visibility,defaultBranchRef,\
  viewerPermission,viewerCanAdminister,viewerDefaultMergeMethod,mergeCommitAllowed,\
  squashMergeAllowed,rebaseMergeAllowed,deleteBranchOnMerge
gh api repos/akonPAPA/Operant --jq '{full_name,private,visibility,default_branch,\
  allow_merge_commit,allow_squash_merge,allow_rebase_merge,allow_auto_merge,\
  allow_update_branch,delete_branch_on_merge,archived,disabled,has_issues,\
  has_projects,has_wiki,security_and_analysis}'
gh api repos/akonPAPA/Operant/branches/main/protection           # -> 404 (classic disabled)
gh api repos/akonPAPA/Operant/rulesets
gh api repos/akonPAPA/Operant/rulesets/17327601
gh api repos/akonPAPA/Operant/rules/branches/main
gh api repos/akonPAPA/Operant/code-scanning/default-setup
gh api "repos/akonPAPA/Operant/code-scanning/alerts?state=open" --paginate    # count only
gh api "repos/akonPAPA/Operant/dependabot/alerts?state=open"                  # count only
gh api "repos/akonPAPA/Operant/secret-scanning/alerts?state=open"             # count only
gh pr view 248 --repo akonPAPA/Operant --json number,state,mergedAt,headRefOid,\
  baseRefName,mergedBy,mergeCommit
gh api repos/akonPAPA/Operant/commits/<HEAD_SHA>/check-runs
ls .github/workflows/ ; grep -nE '^name:|^on:|schedule|pull_request' .github/workflows/*.yml
```

## Branch protection / rulesets

**Classic branch protection: DISABLED.**

```
GET repos/akonPAPA/Operant/branches/main/protection
-> 404 "Branch protection has been disabled on this repository."
```

This is an authoritative "disabled" response (not a permission denial — the caller is
ADMIN). Protection is therefore implemented **entirely through a repository ruleset**.

**Ruleset: `akonya tigr` (id `17327601`) — enforcement `active`.**

| Property | Value |
| --- | --- |
| Target | `branch`, condition `include: ~DEFAULT_BRANCH` (i.e. `main`) |
| Enforcement | `active` |
| `creation` | restricted |
| `deletion` | **blocked** (branch cannot be deleted) |
| `non_fast_forward` | **blocked** (force-push disabled) |
| `required_linear_history` | **true** |
| `required_signatures` | **true** (signed commits required) |
| `required_status_checks.strict` | **true** (branch must be up to date) |
| Required checks | `Docker Compose config` (Actions), `Backend tests` (Actions), `CodeQL` (Advanced Security) |
| `code_quality` | severity `warnings` |
| `code_scanning` | CodeQL `medium_or_higher`; Codacy tools `high_or_higher` (Bandit, Pylint, Stylelint, Shellcheck, etc.) |
| **PR: required approvals** | **1** |
| **PR: require_code_owner_review** | **true** |
| **PR: dismiss_stale_reviews_on_push** | **false** ⚠ |
| **PR: require_last_push_approval** | **false** ⚠ |
| **PR: required_review_thread_resolution** | **false** ⚠ |
| PR: allowed_merge_methods | `merge`, `squash`, `rebase` |
| **bypass_actors** | `RepositoryRole` id `5` (Admin), `bypass_mode: always` ⚠ |

No other rulesets exist. All `main` rules derive from ruleset `17327601`
(confirmed via `rules/branches/main`, every rule reports `ruleset_id: 17327601`).

Note: `allowed_merge_methods` still lists `merge`, but `required_linear_history: true`
would reject a true merge commit — a minor policy inconsistency (see GH-249-06).

## Required checks

Checks that **exist and passed** on reference merged PR **#248**
(head `a0a49dc`, merged `2026-07-06T13:28Z` by `akonPAPA`, squash into `38bd67c`):

| Check name | App | Conclusion | Required? |
| --- | --- | --- | --- |
| `Backend tests` | github-actions | success | **YES** |
| `Docker Compose config` | github-actions | success | **YES** |
| `CodeQL` | github-advanced-security | neutral¹ | **YES** |
| `Analyze (java-kotlin)` | github-actions | success | no (CodeQL matrix leg) |
| `Analyze (python)` | github-actions | success | no |
| `Analyze (actions)` | github-actions | success | no |
| `Analyze (javascript-typescript)` | github-actions | success | no |
| `Semgrep SAST / OP policy scan` | github-actions | success | **no** ⚠ |
| `backend-integration-tests` | github-actions | success | no |
| `Stage 11C acceptance preflight` | github-actions | success | no |
| `Core API release Docker guard` | github-actions | success | no |
| `build` / `test` / `changes` | github-actions | success | no |

¹ CodeQL surfaced as `neutral` on #248; the ruleset requires the `CodeQL` context,
which GitHub treats as satisfied by a non-failing conclusion.

**Existence ≠ required.** Only three contexts are enforced by the ruleset:
`Backend tests`, `Docker Compose config`, `CodeQL`. `Semgrep`, `Frontend`,
`AI Worker`, and `Snyk` are **not** required, even when they run and pass.

`Frontend` and `AI Worker` checks did not appear on #248 because #248 touched only
backend + docs and those workflows are path-filtered (`changes` job). They are
conditional, not required.

## Code scanning / dependency / secret scanning

| Feature | State | Evidence |
| --- | --- | --- |
| CodeQL | **configured** via default setup; query suite `extended`; langs actions/java-kotlin/javascript/typescript/python; schedule weekly + PR | `code-scanning/default-setup` |
| CodeQL required on `main` | **yes** | ruleset required checks |
| Open code-scanning alerts | **386 open** — 362 `Pylint (Codacy)`, 24 `Remark-lint (Codacy)`; **0 CodeQL**, **0 with a security-severity level** | `code-scanning/alerts?state=open --paginate` |
| Semgrep workflow | **present** (`semgrep.yml`, "Semgrep Security Scan", runs on PR/push/schedule) | `.github/workflows/semgrep.yml` |
| Semgrep required on `main` | **no** | ruleset required checks |
| Snyk workflow | **present** (`snyk.yml`) but triggers = **`schedule` + `workflow_dispatch` only — NOT `pull_request`** | `.github/workflows/snyk.yml` |
| Snyk required on `main` | **no** (and does not run on PRs) | ruleset + workflow triggers |
| Dependabot security updates | **enabled** | `security_and_analysis` |
| Open Dependabot alerts | **0** | `dependabot/alerts?state=open` |
| Secret scanning | **enabled** | `security_and_analysis` |
| Secret scanning push protection | **enabled** | same |
| Secret scanning non-provider patterns | disabled | same |
| Secret scanning validity checks | disabled | same |
| Open secret-scanning alerts | **0** | `secret-scanning/alerts?state=open` |
| Any open alert that blocks merge | **no** — no CodeQL/security-severity open alerts; the 386 open are Codacy code-quality (no security severity), and the ruleset code_scanning threshold gates only *new* alerts at/above threshold introduced by a PR | derived from counts above |

No alert secret values were retrieved or recorded; only counts, tools, and severity
levels were queried.

## Merge policy

| Setting | Value |
| --- | --- |
| `allow_merge_commit` | true |
| `allow_squash_merge` | true |
| `allow_rebase_merge` | true |
| `allow_auto_merge` | **false** |
| `allow_update_branch` ("Update branch" button) | **false** |
| `delete_branch_on_merge` | **false** |
| Default merge method (UI) | `SQUASH` |
| Merge blocked without review | **yes** for non-bypassing actors (ruleset PR rule, 1 approval + code owner) |
| Admin override possible | **yes** — `RepositoryRole` 5 bypass `always`; `current_user_can_bypass: always` |

PR #248 was merged by `akonPAPA` (repo owner/admin, a bypass-capable actor). The
required checks (`Backend tests`, `Docker Compose config`, `CodeQL`) all reported
non-failing on the head SHA, so the merge is consistent with the required-checks
policy. GitHub does not expose a per-merge "bypass was used" flag, so whether review/
checks were enforced or bypassed on #248 **cannot be asserted from the API** — this is
recorded as an unproven item rather than guessed. **This PR did not use bypass.**

## Review policy

| Control | Value | Evidence |
| --- | --- | --- |
| Required approving reviews | **1** | ruleset `pull_request` |
| Code-owner review required | **true** | same |
| Dismiss stale reviews on push | **false** ⚠ | same |
| Require last-push approval | **false** ⚠ | same |
| Require conversation resolution | **false** ⚠ | same |
| Self-approval blocked | not directly enforced (no `require_last_push_approval`; author can push after approval without re-review) | same |
| Bypass allowances | Admin role bypass `always` ⚠ | ruleset `bypass_actors` |

Recommended Operant policy vs current:

| Recommended | Current | Aligned? |
| --- | --- | --- |
| ≥1 approving review | 1 | ✅ |
| Dismiss stale approvals on new commits | false | ❌ GH-249-02 |
| Require conversation resolution | false | ❌ GH-249-04 |
| Guard self-approval / require last-push approval | false | ❌ GH-249-03 |
| No admin bypass on protected `main` | Admin bypass `always` | ❌ GH-249-01 |
| Strict / up-to-date required checks | true | ✅ |

## Acceptance matrix

| Control | Current state | Evidence command | Risk if missing | Recommendation | Blocking? |
| --- | --- | --- | --- | --- | --- |
| `main` protected | YES (via ruleset `17327601`) | `gh api .../rules/branches/main` | unrestricted history | keep | — |
| Required PR before merge | YES (creation/PR rule) | ruleset | direct pushes | keep | — |
| Required approving reviews | YES (1) | ruleset PR | unreviewed merge | consider 2 for high-risk paths | P3 |
| Stale review dismissal | **NO** | ruleset PR `dismiss_stale_reviews_on_push=false` | approve-then-change | enable | **P1** (GH-249-02) |
| Last-push / self-approval guard | **NO** | `require_last_push_approval=false` | self-approve own new push | enable last-push approval | P2 (GH-249-03) |
| Conversation resolution | **NO** | `required_review_thread_resolution=false` | unresolved threads merged | enable | P2 (GH-249-04) |
| Required status checks | YES (3, strict) | ruleset `required_status_checks` | unverified merge | keep | — |
| Strict / up-to-date branch | YES | `strict_required_status_checks_policy=true` | stale-base merge | keep | — |
| Backend required | YES (`Backend tests`) | ruleset | broken backend | keep | — |
| Docker Compose config required | YES | ruleset | broken compose | keep | — |
| CodeQL required | YES | ruleset + default setup | SAST regressions | keep | — |
| Frontend required | NO (path-filtered) | check-runs / ruleset | broken FE merged | require conditionally (skip-safe) | P2 (GH-249-05) |
| AI Worker required | NO (path-filtered) | check-runs / ruleset | broken worker merged | require conditionally | P2 (GH-249-05) |
| Semgrep required | **NO** (runs & passes) | check-runs vs ruleset | SAST bypass | add to required checks | **P1** (GH-249-07) |
| Snyk required | **NO** (schedule/dispatch only, not on PR) | `snyk.yml` triggers | vuln deps merged | run on PR + require | P2 (GH-249-08) |
| Force push disabled | YES | `non_fast_forward` | history rewrite | keep | — |
| Branch deletion disabled | YES | `deletion` | branch loss | keep | — |
| Admins included / bypass disabled | **NO** (Admin bypass `always`) | ruleset `bypass_actors` | governance bypass | remove admin bypass or restrict to `pull_request` | **P1** (GH-249-01) |
| Signed commits | YES | `required_signatures` | unverified authorship | keep | — |
| Linear history | YES | `required_linear_history` | tangled history | keep | — |
| Merge-method consistency | PARTIAL (`merge` allowed but linear history on) | ruleset PR + linear | operator confusion | drop `merge` from allowed methods | P3 (GH-249-06) |
| Dependency (Dependabot) alerts | ENABLED, 0 open | `security_and_analysis`, alerts | unseen vuln deps | keep | — |
| Secret scanning + push protection | ENABLED, 0 open | `security_and_analysis` | leaked secrets | consider non-provider patterns + validity checks | P3 (GH-249-09) |
| Rulesets present | YES (1 active) | `rulesets` | — | keep | — |
| Delete branch on merge | NO | repo API | branch clutter | optional enable | P3 |
| Open code-scanning hygiene | 386 Codacy code-quality alerts open | alerts count | noise hides real findings | triage/reduce | P3 (GH-249-10) |

Blocking legend: **P1** = high-priority governance gap to fix before relying on
protected `main` for high-risk merges; **P2** = important hardening; **P3** = hygiene;
**UNKNOWN** = needs manual UI verification.

## Gaps and recommendations

All gaps are evidence-backed and tracked in
[`docs/backlog/fix-notebook.md`](../backlog/fix-notebook.md):

- **GH-249-01 (P1)** — Admin role can bypass the ruleset `always`.
- **GH-249-02 (P1)** — Stale reviews are not dismissed on new pushes.
- **GH-249-03 (P2)** — Last-push approval / self-approval guard disabled.
- **GH-249-04 (P2)** — Conversation resolution not required.
- **GH-249-05 (P2)** — Frontend / AI Worker checks not required.
- **GH-249-06 (P3)** — `merge` allowed while linear history is required.
- **GH-249-07 (P1)** — Semgrep runs and passes but is not a required check.
- **GH-249-08 (P2)** — Snyk runs only on schedule/dispatch, not on PRs, not required.
- **GH-249-09 (P3)** — Secret-scanning non-provider patterns + validity checks off.
- **GH-249-10 (P3)** — 386 open Codacy code-quality code-scanning alerts.

No setting was changed in this PR. Each gap includes a recommended exact setting in
the fix-notebook; applying them requires a separate, explicitly authorized change.

## What remains not proven

- Whether ruleset bypass was exercised on any historical merge (including #248) —
  GitHub does not expose a reliable per-merge bypass flag.
- Org-level policies (the repo owner may be an org with additional constraints not
  visible at repo API tier).
- Snyk / Codacy dashboard-side configuration beyond repo APIs.
- Any UI-only setting not exposed by REST (see manual checklist below).
- No destructive or setting-changing operation was performed; no admin merge or
  bypass was exercised to "test" enforcement.

## Manual UI verification checklist

Where the API cannot confirm, verify in the GitHub UI (do not commit screenshots
containing private UI data):

- **Settings → Rules → Rulesets → `akonya tigr`**: confirm bypass list = Admin only,
  and consider changing bypass mode from *Always* to *Pull requests* or removing it.
- **Settings → Branches**: confirm no hidden classic protection (API says disabled).
- **Settings → Code security and analysis**: confirm CodeQL default setup, Dependabot,
  secret scanning + push protection (API-confirmed enabled).
- **Settings → Actions → General**: confirm workflow permissions and whether
  `Semgrep`/`Snyk` should be promoted to required checks.
- **Settings → Collaborators and teams**: confirm who holds Admin (bypass-capable).
- **Settings → Webhooks / Secrets and variables**: confirm no unexpected webhooks;
  do not print secret values.

## Final recommendation

- **Safe to rely on current settings? PARTIAL.** `main` is genuinely protected with
  required review, required strict checks, signed commits, linear history, and
  force-push/deletion blocked. That is a solid baseline.
- **Must-fix before the next high-risk PR:** GH-249-01 (admin bypass `always`),
  GH-249-02 (stale-review dismissal off), and GH-249-07 (Semgrep not required). Until
  these land, a bypass-capable actor can merge unreviewed/changed code, and SAST
  passing is advisory rather than enforced.
- **Next PR recommendation:** a separately authorized governance-hardening PR that
  edits ruleset `17327601` to: dismiss stale reviews on push, require last-push
  approval + conversation resolution, add `Semgrep SAST / OP policy scan` to required
  checks, run Snyk on `pull_request` and require it, and restrict/remove the admin
  bypass — each change verified by re-reading the ruleset via the same API commands
  used here.

---

# Stage 30B — Production Delivery Governance Hardening (after-state)

## Scope of Stage 30B

Stage 30A (above) documented the current state and opened `GH-249-XX`. Stage 30B
**applies** a bounded, authorized subset of those hardening controls to ruleset
`17327601` on `akonPAPA/Operant`, then re-reads the live API to prove the new state.

This stage is **settings + docs only**. No application code, workflow YAML, migration,
DTO, connector, or payment/reconciliation code was touched. Repository visibility,
secret scanning, push protection, Dependabot, and CodeQL enforcement were **not**
weakened. No admin merge or protection bypass was exercised to make these changes; the
ruleset was edited via the authenticated REST API (`PUT .../rulesets/17327601`) as an
ADMIN user, which is a normal settings write, not a protection bypass.

Evidence timestamps (UTC): before re-read `2026-07-06T15:18:05Z`; change applied
`2026-07-06T20:21:36+05:00` (ruleset `updated_at`); after re-read `2026-07-06T15:22:57Z`.

## Controls changed in Stage 30B

Exactly three ruleset fields were changed. Everything else in the ruleset is
byte-for-byte preserved (verified by diffing the before/after JSON snapshots).

| # | Control | Before | After | Backlog item | API path |
| --- | --- | --- | --- | --- | --- |
| 1 | Admin bypass mode | `always` | **`pull_request`** | GH-249-01 (partial) | `bypass_actors[0].bypass_mode` |
| 2 | Dismiss stale approvals on push | `false` | **`true`** | GH-249-02 (resolved) | `rules[pull_request].dismiss_stale_reviews_on_push` |
| 3 | Require conversation resolution | `false` | **`true`** | GH-249-04 (resolved) | `rules[pull_request].required_review_thread_resolution` |

`current_user_can_bypass` correspondingly moved from `always` to **`pull_requests_only`**.

## Before / after governance matrix

| Control | Before (Stage 30A) | After (Stage 30B) | Status |
| --- | --- | --- | --- |
| `main` protected via ruleset `17327601` | YES | YES | unchanged |
| Classic branch protection | DISABLED (404) | DISABLED (404) | unchanged |
| Required approving reviews | 1 | 1 | unchanged |
| Require code-owner review | true | true | unchanged |
| **Dismiss stale reviews on push** | **false** ⚠ | **true** ✅ | **hardened (GH-249-02)** |
| **Require conversation resolution** | **false** ⚠ | **true** ✅ | **hardened (GH-249-04)** |
| Require last-push approval | false | false | deferred (GH-249-03) |
| **Admin bypass mode** | **`always`** ⚠ | **`pull_request`** ✅ | **hardened (GH-249-01 partial)** |
| Required status checks (strict) | `Backend tests`, `Docker Compose config`, `CodeQL` | same 3, still strict | unchanged (not weakened) |
| Semgrep required | no | no | deferred (GH-249-07) |
| Frontend / AI Worker required | no | no | deferred (GH-249-05) |
| Snyk required / on PR | no | no | deferred (GH-249-08) |
| Signed commits (`required_signatures`) | true | true | unchanged |
| Linear history | true | true | unchanged |
| Force push (`non_fast_forward`) | blocked | blocked | unchanged |
| Branch deletion | blocked | blocked | unchanged |
| `allowed_merge_methods` | `merge,squash,rebase` | `merge,squash,rebase` | deferred (GH-249-06) |
| CodeQL / Codacy `code_scanning` thresholds | unchanged | unchanged | not weakened |
| Secret scanning + push protection | enabled | enabled | not touched |
| Dependabot alerts | enabled, 0 open | enabled | not touched |
| Repository visibility | public | public | not changed |

## What the three changes actually buy

- **Admin bypass `always` → `pull_request`** — the Admin role can no longer bypass the
  ruleset on a **direct push** to `main`; admin changes must now flow through a pull
  request. This closes the "push straight to `main` bypassing everything" hole. It does
  **not** fully close admin bypass *inside* the PR flow (see deferral GH-249-01 below).
- **Dismiss stale approvals on push** — once a second reviewer exists, an approval of
  version A is invalidated when version B is pushed, defeating approve-then-change.
- **Require conversation resolution** — open review threads now block merge, so reviewer
  objections cannot be silently merged over.

## Controls intentionally deferred (and why)

These were **not** fully applied in Stage 30B because doing so safely required changes
outside that stage's allowed scope (workflow YAML edits) or would have created a
no-recovery merge lockout for the current single-maintainer (solo-founder) workflow.
Each remains tracked in the fix-notebook with an explicit reason; PR #261 updates only
the CI/workflow side and leaves ruleset governance changes for a dedicated follow-up.

- **GH-249-01 residual (full admin-bypass removal)** — required approvals = 1 +
  code-owner review, with **no second reviewer** on the repo. A PR author cannot approve
  their own PR, so *fully* removing admin bypass would make **every** PR unmergeable with
  no recovery path (a hard stop condition). Bypass was therefore **restricted** to
  `pull_request` rather than removed. Full removal is contingent on adding a second
  approver/team. **PR #261 does not change this; it remains deferred.**
- **GH-249-03 (last-push approval)** — same single-maintainer constraint: last-push
  approval demands approval from someone other than the pusher; with no second reviewer
  this blocks all merges. Deferred until a second reviewer exists. **PR #261 does not
  change this; it remains deferred.**
- **GH-249-07 (Semgrep required)** — **workflow-level support added by PR #261;
  governance required check still deferred.** `.github/workflows/semgrep.yml` now
  runs an always-present skip-safe gate context `Semgrep Security Scan / Semgrep Gate`
  on every PR. The gate fail-closes if the `changes` job did not succeed, reports
  success as "not applicable" when no Semgrep-relevant paths change, and otherwise
  fails unless the real `Semgrep SAST / OP policy scan` job completed successfully
  (including treating `failure`, `cancelled`, or `skipped` as failures). **Ruleset
  `17327601` was NOT updated in this PR.** After merge, the repo owner must add
  `Semgrep Security Scan / Semgrep Gate` as a required check and re-prove.
- **GH-249-05 (Frontend / AI Worker required)** — **workflow-level support added by
  PR #261; governance required check still deferred.** `.github/workflows/frontend.yml`
  and `.github/workflows/ai-worker.yml` now emit stable skip-safe gate contexts
  `Frontend / Frontend Gate` and `AI Worker / AI Worker Gate` on every PR. Each gate
  fail-closes if its `changes` job did not succeed, reports success as "not applicable"
  when no relevant paths change, and otherwise fails unless the real validation job
  (`build` / `test`) completed successfully. **Ruleset `17327601` was NOT updated in
  this PR.** After merge, the repo owner must add both gate contexts as required checks
  and re-prove.
- **GH-249-08 (Snyk required)** — **workflow-level support added by PR #261 for
  core-api + web-dashboard dependency manifest changes only; governance required check
  still deferred.** `.github/workflows/snyk.yml` now runs on `pull_request` as well as
  schedule and manual dispatch, with an internal `changes` detector and a skip-safe gate
  context `Snyk Dependency Scan / Snyk Gate`.
  - PR gating is **bootstrap/honest**: it separates **dependency relevance**
    (`snyk_dependency_relevant`) from **workflow relevance** (`snyk_workflow_relevant`).
    A workflow-only change (this PR) does not automatically enforce the existing core-api
    high/critical Snyk baseline.
  - When dependency manifests change, it enforces only the touched surface:
    `Snyk Open Source - core-api` runs only when `apps/core-api/**/pom.xml` changes, and
    `Snyk Open Source - web-dashboard` runs only when `apps/web-dashboard/package*.json`
    changes.
  - When only `.github/workflows/snyk.yml` changes and no dependency manifests change, the
    gate reports success as workflow-support-only and explicitly states dependency
    enforcement is not applicable for that PR (scheduled/manual Snyk still covers baseline).
  - AI Worker Python dependency scanning (`apps/ai-worker/pyproject.toml`,
    `requirements*.txt`) is **not** claimed and remains a residual follow-up.
  The gate fail-closes if `changes` did not succeed; fails closed on fork PRs with
  dependency changes and no `SNYK_TOKEN`; and does not use `continue-on-error` for
  high/critical enforcement. **Ruleset `17327601` was NOT updated in this PR.**
  After merge, do not add `Snyk Dependency Scan / Snyk Gate` as required until the repo
  owner either (a) remediates existing core-api high/critical Snyk findings, or (b)
  explicitly accepts a documented baseline/fail policy strategy; then add the gate context
  as required and re-prove.
- **GH-249-06 (drop `merge` method)** — P3 consistency only; left unchanged in Stage 30B
  and **not** modified by PR #261. The ruleset still reports
  `allowed_merge_methods: [merge, squash, rebase]` while linear history is required.
- **GH-249-09 / GH-249-10** — repo-security-setting and dashboard-triage hygiene items,
  out of scope for both Stage 30B and PR #261; they remain deferred.

## Stage 30B verification commands

All read-only re-reads used to prove the after-state:

```
gh api repos/akonPAPA/Operant/rulesets/17327601
gh api repos/akonPAPA/Operant/rules/branches/main
gh api repos/akonPAPA/Operant/branches/main/protection    # -> 404, classic still disabled
gh repo view akonPAPA/Operant --json nameWithOwner,visibility,defaultBranchRef,\
  viewerPermission,viewerCanAdminister,viewerDefaultMergeMethod,mergeCommitAllowed,\
  squashMergeAllowed,rebaseMergeAllowed,deleteBranchOnMerge
```

Change applied with (authorized settings write, not a bypass):

```
gh api --method PUT repos/akonPAPA/Operant/rulesets/17327601 --input <payload.json>
# payload = before-snapshot with only bypass_mode->pull_request,
# dismiss_stale_reviews_on_push->true, required_review_thread_resolution->true
```

After-state key fields (from the re-read):

```
bypass_actors            = [{actor_type: RepositoryRole, actor_id: 5, bypass_mode: pull_request}]
current_user_can_bypass  = pull_requests_only
dismiss_stale_reviews_on_push      = true
required_review_thread_resolution  = true
require_last_push_approval         = false   (deferred, GH-249-03)
required_status_checks   = [Backend tests, Docker Compose config, CodeQL], strict = true
required_signatures / required_linear_history / non_fast_forward / deletion = unchanged
```

## What remains not proven after Stage 30B

- Whether a ruleset bypass was exercised on any historical merge — GitHub still exposes
  no per-merge bypass flag.
- The **effectiveness** of dismiss-stale-reviews and last-push-approval-style controls in
  practice, because the repo currently has a single maintainer and no second-reviewer flow
  to exercise them against. They are correctly *configured*; they are not yet *exercised*.
- That admin bypass inside the PR flow is closed — it is **not**; `pull_request` bypass
  mode still allows an admin to merge a PR that does not meet review/status requirements.
  This is the unavoidable solo-founder residual tracked as GH-249-01.
- Org-level policies, and Snyk/Codacy dashboard-side configuration, remain outside repo
  API visibility (unchanged from Stage 30A).

---

# PR #261 — Pilot Release Gate (workflow-level skip-safe required checks)

## Scope of PR #261

PR #261 is **CI/workflow + documentation only**. It does **not** change repository
ruleset `17327601`. No product/backend/frontend runtime-control behavior is changed by
the final PR surface (any accidental runtime-control drift was reverted to `origin/main`).

## Current required checks before PR #261 (ruleset unchanged)

Ruleset `17327601` required status checks (strict):

- `Backend tests`
- `Docker Compose config`
- `CodeQL`

## New required-check candidates after PR #261 (workflow support only)

Stable check contexts added by workflows (not yet in the ruleset):

- `Frontend / Frontend Gate`
- `AI Worker / AI Worker Gate`
- `Semgrep Security Scan / Semgrep Gate`
- `Snyk Dependency Scan / Snyk Gate`

## Ruleset change status

**No.** PR #261 does **not** update ruleset `17327601`. After this PR merges, the repo
owner must add the four stable contexts above as required checks and re-prove via:

```
gh api repos/akonPAPA/Operant/rulesets/17327601
gh api repos/akonPAPA/Operant/rules/branches/main
```

Expected after ruleset follow-up (not done here):
`required_status_checks` includes the four gate contexts above, still `strict: true`.

## Gate semantics (fail-closed)

Each gate job:

1. Fail-closes if its `changes` job result is not `success` (no empty-output bypass).
2. Passes as "not applicable" when `changes` succeeds and relevant paths are untouched.
3. Fails if relevant paths changed and the real validation job is not `success`
   (`failure` / `cancelled` / `skipped` all fail the gate).

## Snyk coverage honesty

Snyk Gate covers:

- `apps/core-api` (`pom.xml` and nested `pom.xml`)
- `apps/web-dashboard` (`package.json`, `package-lock.json`)
- `.github/workflows/snyk.yml`

AI Worker Python dependency scanning (`apps/ai-worker/pyproject.toml`,
`apps/ai-worker/requirements*.txt`) is **not** claimed as Snyk-covered and remains a
residual follow-up unless another tool already covers it.

## What remains not proven after PR #261

- GitHub ruleset update adding the four gate contexts as required checks.
- End-to-end fork-PR Snyk fail-closed behavior on GitHub (logic is in YAML; not exercised
  here with a real fork PR).
- Seeded failing Semgrep / Snyk findings blocking merge unless exercised on GitHub.
- GH-249-01 full admin-bypass removal and GH-249-03 last-push approval (solo-maintainer
  deferred).
- GH-249-06 merge-method consistency (left OPEN; not changed).
