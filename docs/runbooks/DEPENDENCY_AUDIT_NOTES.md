# Dependency Audit Notes

## Scope

This note covers the Stage 9D local demo packaging pass for `apps/web-dashboard`.

Command run:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
npm audit --json
```

The first sandboxed audit attempt could not reach the npm advisory endpoint. The command was rerun with network access and returned the summary below.

## Current Summary

`npm audit --json` reported 5 vulnerabilities:

| Package | Severity | Direct | Notes |
| --- | --- | --- | --- |
| `next` | critical | yes | Multiple advisories affect the current `14.2.18` range. The available npm audit fix points to `next@16.2.6`, a semver-major upgrade. |
| `eslint-config-next` | high | yes | Fix points to `eslint-config-next@16.2.6`, a semver-major upgrade. |
| `@next/eslint-plugin-next` | high | no | Pulled through `eslint-config-next`; affected by transitive `glob`. |
| `glob` | high | no | Transitive through `@next/eslint-plugin-next`; advisory references command injection in CLI usage. |
| `postcss` | moderate | no | Transitive through `next`; fix points through a semver-major Next upgrade. |

Audit metadata:

- Total vulnerabilities: 5.
- Moderate: 1.
- High: 3.
- Critical: 1.
- Total dependencies: 360.

## Recommendation

Do not run `npm audit fix --force` during the Stage 9D demo packaging pass. The automated fix path is a semver-major jump to Next 16 and matching lint packages, which is too broad for a local demo readiness polish task.

Recommended follow-up after Stage 9D:

1. Review whether a targeted Next 14 patch-level upgrade is available and compatible with the current App Router dashboard.
2. Prefer targeted upgrades over force upgrades.
3. Rerun `npm install`, `npm run lint`, `npm run typecheck`, `npm run build`, and `npm test` after any dependency change.
4. Re-run `npm audit --json` and update this note with the new advisory state.
5. Do not expose the local demo dashboard as a public production service until the critical Next advisory path is resolved or formally risk-accepted.
