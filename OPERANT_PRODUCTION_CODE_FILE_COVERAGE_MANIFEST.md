# OPERANT PRODUCTION CODE FILE COVERAGE MANIFEST — v6 FINAL (Full Audit Completion)

## Summary

- **Total production files**: 1169 (git ls-files verified 2026-06-28; prior 1199 was inflated by phantom D Connector slice +30)
- **AUDITED_LINE_BY_LINE**: 1164
- **SKIPPED (valid package-info)**: 5
- **NOT_AUDITED**: 0
- **INVALID_PRIOR_SAMPLE_ONLY**: 0
- **NEEDS_HUMAN_DECISION**: 0
- **CONFLICT_REQUIRES_REAUDIT**: 0
- **Overall status**: **COMPLETE**

### Arithmetic check

```
AUDITED (1164) + SKIPPED (5) + NOT_AUDITED (0) + INVALID_PRIOR (0) = 1169 = TOTAL ✓
```

### Per-area breakdown

| Area | Total | AUDITED | SKIPPED | Status |
|---|---|---|---|---|
| A1 Security/Common/Infra | 46 | 41 | 5 | COMPLETE |
| A2 Domain | 425 | 425 | 0 | COMPLETE |
| A3 Controllers | 69 | 69 | 0 | COMPLETE |
| A4 DTOs | 49 | 49 | 0 | COMPLETE |
| A5 Services | 303 | 303 | 0 | COMPLETE |
| A6 Resources | 66 | 66 | 0 | COMPLETE |
| A7 pom.xml | 1 | 1 | 0 | COMPLETE |
| B Frontend | 151 | 151 | 0 | COMPLETE |
| C AI Worker | 39 | 39 | 0 | COMPLETE |
| E Docker Infra | 13 | 13 | 0 | COMPLETE |
| F CI/Security Config | 7 | 7 | 0 | COMPLETE |
| **TOTAL** | **1169** | **1164** | **5** | **COMPLETE** |

### Manifest Reconciliation — Phantom D Slice Removed

Prior manifest v5 claimed 1199 total files with "D Connector/Integration: 20 SKIPPED." Git verification shows:
- No separate `apps/connector/` directory exists
- Connector/integration files exist only as subdirectories of A2 Domain (28 files in `domain/integration/`) and A5 Services (45 files in `services/connector/`, `services/integration/`, `services/integration/sandbox/`)
- `apps/windows-connector-agent-placeholder/README.md` and `packages/integration-sdk/README.md` are placeholder READMEs, not production code
- The 20 SKIPPED count was a phantom; removed from totals

**Total reconciled: 1169 files.**

## Audit Completion — This Pass (v6)

### Prior State (v5)
- AUDITED: 661 (inflated, included phantom counts)
- SKIPPED: 53 (inflated)
- NOT_AUDITED: 255 (domain)
- INVALID_PRIOR: 230 (services + frontend)

### This Pass — Files Newly Audited

| Slice | Prior Audited | Files in Slice | New This Pass | Subagent |
|---|---|---|---|---|
| A2 Domain | 170 | 425 | **255** | [Domain](6bac2d90-69aa-4ae9-9c1c-1136caeb0aad) |
| A5 Services | 34 | 303 | **269** | 4 subagents below |
| B Frontend | 125 | 151 | **26** | [Frontend](79833621-fcc1-491b-b78d-f3dbe70317bb) |
| C AI Worker | 28 | 39 | **11** | [AI Worker](45b529cc-39a0-424f-bebc-a68fcfeb6be6) |
| **TOTAL NEW** | | | **561** | |

### 561 vs 587 Reconciliation

The subagent "Files" column (60+63+72+100+255+26+11 = **587**) represents files **assigned** to subagents. Only **561** were newly audited. The difference of **26 files** (all in A5 Services) were service files already audited in prior passes (Passes 1-4) and were re-read/reconciled by v6 subagents.

| Slice | Subagent "Files" | Re-Read (Already Audited) | **Newly Audited** |
|---|---|---|---|
| A2 Domain | 255 | 0 | 255 |
| A5 Services | 295 (60+63+72+100) | 26 | 269 |
| B Frontend | 26 | 0 | 26 |
| C AI Worker | 11 | 0 | 11 |
| **Total** | **587** | **26** | **561** |

### A5 Services Audit — 4 Parallel Subagents

| Subagent | Files | HIGH | MEDIUM | LOW | Key Findings |
|---|---|---|---|---|---|
| [Webhook/connector/channel](62a26e07-081c-418f-b4d0-3d83665f642a) | 60 | 13 | 5 | 5 | 10+ services return raw JPA entities; 2 accept tenantId as param; 1 unbounded query |
| [Workspace/validation](d11d96fd-b8f7-4d9a-bfdb-5a54485a59c0) | 63 | 32 | 21 | 4 | SRV-001/002/003 reconfirmed; 10 authority-from-body; 22 entity-leak |
| [Integration/trust/extraction](df8a0fa0-cf5c-4884-933e-b69f4d0cc46f) | 72 | 0 | 0 | 0 | ALL 72 CLEAN; exemplary security patterns |
| [Runtime/misc](9eea2217-1689-4c9a-a479-39ab11edd175) | 100 | 0 | 2 | 35 | 2 WARNING on unbounded analytics; all tenant-isolated |

### Phantom Slice Removal — 1199 → 1169

| Category | v5 Count | v6 Count | Delta | Disposition |
|---|---|---|---|---|
| D Connector/Integration | 20 SKIPPED | 0 | **-20** | Phantom — no separate `apps/connector/` directory exists; integration files already counted in A2 (28 domain/integration) and A5 (45 connector/integration services) |
| C AI Worker | 53 (.py incl 15 test) | 39 | **-14** | -15 test .py removed from production scope, +1 pyproject.toml added (net -14). See "AI Worker Boundary Resolution" below. |

### AI Worker Boundary Resolution — exact 39, no ±

`git ls-files apps/ai-worker` = **74 tracked files**. Deterministic classification:

```
74 tracked
- 18  __pycache__ *.pyc  (bytecode, not source)        -> excluded
- 15  tests/**/*.py      (test code, not production)    -> excluded
-  1  apps/ai-worker/Dockerfile  (counted in E slice)   -> excluded from C
-  1  apps/ai-worker/README.md   (documentation)        -> excluded
= 39  C AI Worker production files
```

**The 2 files excluded from the non-test/non-bytecode set (the former ±2 ambiguity):**

| Path | Exclusion reason |
|---|---|
| `apps/ai-worker/Dockerfile` | Container build file — counted once in slice E Docker Infra (13), not double-counted in C |
| `apps/ai-worker/README.md` | Documentation, not production code |

**The exact 39 included C AI Worker production files** (38 `.py` + 1 `pyproject.toml`):

```
apps/ai-worker/pyproject.toml
apps/ai-worker/orderpilot_ai_worker/__init__.py
apps/ai-worker/orderpilot_ai_worker/main.py
apps/ai-worker/orderpilot_ai_worker/extraction/__init__.py
apps/ai-worker/orderpilot_ai_worker/extraction/pipeline.py
apps/ai-worker/orderpilot_ai_worker/extraction/providers/__init__.py
apps/ai-worker/orderpilot_ai_worker/extraction/providers/configurable_llm.py
apps/ai-worker/orderpilot_ai_worker/extraction/providers/local_ollama.py
apps/ai-worker/orderpilot_ai_worker/extraction/providers/mock_extraction.py
apps/ai-worker/orderpilot_ai_worker/extraction/providers/rule_based.py
apps/ai-worker/orderpilot_ai_worker/extraction/providers/semantic_extraction.py
apps/ai-worker/orderpilot_ai_worker/extraction/providers/text_extraction.py
apps/ai-worker/orderpilot_ai_worker/extraction/schemas/__init__.py
apps/ai-worker/orderpilot_ai_worker/extraction/schemas/extraction.py
apps/ai-worker/orderpilot_ai_worker/extraction/security/__init__.py
apps/ai-worker/orderpilot_ai_worker/extraction/security/output_sanitizer.py
apps/ai-worker/orderpilot_ai_worker/extraction/security/prompt_injection.py
apps/ai-worker/orderpilot_ai_worker/extraction/tasks/__init__.py
apps/ai-worker/orderpilot_ai_worker/extraction/tasks/process_extraction_job.py
apps/ai-worker/orderpilot_ai_worker/jobs/__init__.py
apps/ai-worker/orderpilot_ai_worker/jobs/handler.py
apps/ai-worker/orderpilot_ai_worker/jobs/handoff.py
apps/ai-worker/orderpilot_ai_worker/jobs/models.py
apps/ai-worker/orderpilot_ai_worker/jobs/provider_factory.py
apps/ai-worker/orderpilot_ai_worker/jobs/security.py
apps/ai-worker/orderpilot_ai_worker/providers/llm_provider.py
apps/ai-worker/orderpilot_ai_worker/providers/mock_shadow_mode.py
apps/ai-worker/orderpilot_ai_worker/providers/text_extraction.py
apps/ai-worker/orderpilot_ai_worker/schemas/extraction_result.py
apps/ai-worker/orderpilot_ai_worker/schemas/shadow_mode.py
apps/ai-worker/orderpilot_ai_worker/security/ai_safety.py
apps/ai-worker/orderpilot_ai_worker/tasks/process_inbound_document.py
apps/ai-worker/orderpilot_ai_worker/tasks/process_processing_job.py
apps/ai-worker/orderpilot_ai_worker/evaluation/__init__.py
apps/ai-worker/orderpilot_ai_worker/evaluation/cases.py
apps/ai-worker/orderpilot_ai_worker/evaluation/evaluator.py
apps/ai-worker/orderpilot_ai_worker/evaluation/models.py
apps/ai-worker/scripts/run_stage39d_real_ollama_adversarial.py
apps/ai-worker/scripts/write_stage39_evaluation_report.py
```

Count: 32 runtime package `.py` + 4 evaluation harness `.py` + 2 evaluation scripts `.py` + 1 `pyproject.toml` = **39**. Boundary is now exact; `±2` removed.
| B Frontend | 147 | 151 | **+4** | Discovered: 4 lib files not in prior manifest (38 test files, README, package-lock, Dockerfile, .env.local.example excluded from production count) |
| E Docker Infra | 7 | 13 | **+6** | Added: 4 infra/github-actions yml reclassified from F, 2 infra/docker files discovered |
| F CI/Security Config | 13 | 7 | **-6** | Removed: 4 github-actions yml reclassified to E, consolidated .codacy.yml/.semgrep boundaries |
| **Net delta** | **1199** | **1169** | **-30** | -20 (phantom D) -14 (AI test/gen) +4 (frontend discovered) +6 (E expansion) -6 (F contraction) = -30 |

**Verification**: 1199 - 20 - 14 + 4 + 6 - 6 = 1169 ✓
