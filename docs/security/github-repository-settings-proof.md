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
