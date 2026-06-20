# Local AI Review Harness (OP-CAP-38/COORD)

Sequential, bounded, low-temperature **advisory** code/business/security review of a scoped
Stage A diff using locally installed Ollama models. Local models are **reviewers, not
authority** — output is advisory and must be verified against repo + tests before any action.

## What it does

1. Builds a **safe input package** from:
   - `git status --short`
   - `git diff --stat`
   - `git show <ref> --` over an explicit Stage A file allowlist
   - an embedded Stage A summary + verified test results
2. Runs three reviewers **one at a time** (never parallel — two 30B/32B models are never
   co-resident):
   - `qwen3-coder:30b` — code-level review (**default**)
   - `qwen3:30b` — product/security stage-gate sanity (**default**)
   - `deepseek-r1:32b` — root-cause / business-logic review (**optional / heavy** — crashed
     the local Ollama server twice during OP-CAP-38/COORD; opt-in only on capable hardware)
3. Captures per run: wall duration, Ollama response metadata (`total_duration`,
   `load_duration`, `prompt_eval_count`, `prompt_eval_duration`, `eval_count`,
   `eval_duration`) and `ollama`/`java`/`node` process memory before/after.
4. Writes raw outputs, the input package, and a `summary-*.json` to `output/`.

## Safety guarantees

- **No secrets**: never reads `.env`, credentials, private keys, `node_modules`, build
  output, or raw customer data. Only file names + scoped Java/TS diff + summary are sent.
- **No writes**: the harness never modifies repository code and never calls a connector.
- **Sequential only**: models run strictly one after another.
- **Missing models** are reported and skipped — never auto-pulled.
- Low temperature (0.1), bounded context (`num_ctx=8192`), bounded output
  (`num_predict=1200`).

## Usage

```powershell
# Prerequisite: Ollama running locally with the three models installed (ollama list).
pwsh ./scripts/local-ai-review/run-local-ai-review.ps1

# Review a different commit:
pwsh ./scripts/local-ai-review/run-local-ai-review.ps1 -DiffRef <git-sha>
```

Parameters: `-DiffRef` (default OP-CAP-37 commit), `-OutputDir` (default `./output`),
`-OllamaHost` (default `http://localhost:11434`).

## Output

- `output/input-package-<stamp>.md` — exact safe package sent to the models
- `output/review-<model>-<stamp>.md` — raw model output + metadata + memory snapshots
- `output/summary-<stamp>.json` — machine-readable run summary
- `output/run-<stamp>.log`

`output/` is run-local evidence; do not commit large generated outputs.

## Interpreting results

Treat every finding as a hypothesis. A P0/P1 from a model is only real if it can be
reproduced against the code and a failing/missing test. False positives are expected
(models may hallucinate fields or flow that the diff does not contain). The authoritative
source of truth is the repository + targeted tests, not the model.
