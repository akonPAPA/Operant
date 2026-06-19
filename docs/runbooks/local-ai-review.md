# Runbook - Local AI Review (OP-CAP-38/COORD)

Operational runbook for running the local Ollama review/metrics gate against a scoped
OrderPilot Stage diff. The harness is an **advisory reviewer**, not an authority. It never
modifies code, never calls a connector, and never sends secrets to a model.

## When To Run

- After completing a small, scoped slice and its targeted tests pass.
- Before promoting to the next roadmap gate, as a second-opinion quality/security pass.
- Not as a replacement for repository tests; tests remain the source of truth.

## Prerequisites

- Ollama installed and running locally (`ollama serve` / Ollama desktop).
- Models installed (verify with `ollama list`):
  - `qwen3-coder:30b` - **default** reviewer (code-level)
  - `qwen3:30b` - **default** reviewer (product/security gate)
  - `deepseek-r1:32b` - **optional / heavy** reviewer (root-cause / business-logic)
- Enough free RAM/VRAM to load one 30B/32B model at a time. The harness runs models
  **sequentially**, so only one is co-resident.
- Windows PowerShell 5.1 or PowerShell 7.

## Default Local Reviewer Policy

- **Default reviewers: `qwen3-coder:30b` and `qwen3:30b`.** Both ran reliably one-at-a-time
  during OP-CAP-38/COORD and are the recommended local advisory gate.
- **`deepseek-r1:32b` is optional and heavy.** During OP-CAP-38/COORD it failed twice and
  crashed the local Ollama server. Treat it as opt-in only, and only with a reduced
  context/output budget on hardware proven to sustain a 32B model.
- **Models must run sequentially.** Never load two 30B/32B models in parallel.
- **No secrets / `.env` / credentials / raw customer data** in prompts or the input package.
  The harness sends only file names + scoped diff + summary; verify
  `output/input-package-*.md` before each run.
- Local models are **advisory reviewers, not authority**. Every finding is re-checked
  against the repository + a test before action.

## Run

```powershell
# From the repository root:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/local-ai-review/run-local-ai-review.ps1
```

Optional parameters:

- `-DiffRef <git-sha>` - git ref whose Stage diff is reviewed (default: OP-CAP-37 commit).
- `-OutputDir <path>` - output directory (default: `scripts/local-ai-review/output`).
- `-OllamaHost <url>` - Ollama base URL (default: `http://localhost:11434`).
- `-IncludeHeavyReviewer` - opt in to `deepseek-r1:32b`; default is off.
- `-SelfTest` - validate deny-list/default-reviewer safety without contacting Ollama.

## What It Captures

Per model: wall duration, Ollama metadata (`total_duration`, `load_duration`,
`prompt_eval_count`, `prompt_eval_duration`, `eval_count`, `eval_duration`) and
`ollama`/`java`/`node` process memory before/after. All written to
`scripts/local-ai-review/output/`.

## Safety Checklist

- [ ] Input package contains only file names + scoped diff + summary.
- [ ] No `.env`, credentials, private keys, `node_modules`, build output, DB dump, or raw
      customer data is referenced.
- [ ] Denied input path filters are active for `.env`, secrets/credentials, private keys,
      dependency folders, build output, caches, DB/dump files, and local artifacts.
- [ ] Models run sequentially.
- [ ] `deepseek-r1:32b` is absent unless `-IncludeHeavyReviewer` was explicitly passed.
- [ ] Output is treated as advisory; every P0/P1 is re-checked against code + a test.

## Known Operational Notes

- Reasoning models (`deepseek-r1`, `qwen3`) emit their review in the `thinking` field; the
  harness captures both `thinking` and `response`.
- Windows PowerShell 5.1: the harness sends the request body as UTF-8 bytes. A plain string
  body in 5.1 mis-sets `Content-Length` for multi-byte content and yields HTTP 400 from
  Ollama on large packages.
- First run is slower: cold model load adds `load_duration`.
- `output/` holds run-local evidence and is not intended to be committed.

## Troubleshooting

| Symptom | Cause | Action |
| --- | --- | --- |
| `MODEL MISSING` | model not pulled | `ollama pull <model>` manually; the harness never auto-pulls |
| HTTP 400 on large package | string body / encoding | ensure body is sent as UTF-8 bytes |
| Connection refused | Ollama not running | start Ollama, retry |
| Empty `Answer`, only `Reasoning` | reasoning model spent budget thinking | review the `Reasoning` block or raise `num_predict` |
