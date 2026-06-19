# Local AI Review Harness (OP-CAP-38/COORD)

Sequential, bounded, low-temperature **advisory** code/business/security review of a scoped
Stage diff using locally installed Ollama models. Local models are **reviewers, not authority**:
output is advisory and must be verified against repo + tests before any action.

## What It Does

1. Builds a **safe input package** from:
   - filtered `git status --short`
   - filtered `git diff --stat` for the allowlisted files
   - `git show <ref> --` over an explicit Stage file allowlist
   - an embedded Stage summary + verified test results
2. Runs default reviewers **one at a time**:
   - `qwen3-coder:30b` - code-level review (**default**)
   - `qwen3:30b` - product/security stage-gate sanity (**default**)
   - `deepseek-r1:32b` - root-cause / business-logic review (**optional / heavy**; use
     `-IncludeHeavyReviewer`; crashed local Ollama twice during OP-CAP-38/COORD)
3. Captures per run: wall duration, Ollama response metadata (`total_duration`,
   `load_duration`, `prompt_eval_count`, `prompt_eval_duration`, `eval_count`,
   `eval_duration`) and `ollama`/`java`/`node` process memory before/after.
4. Writes raw outputs, the input package, and a `summary-*.json` to `output/`.

## Safety Guarantees

- **No secrets**: never reads `.env`, credentials, private keys, `node_modules`, build
  output, raw customer data, DB dumps, or local artifacts. Only file names + scoped diff +
  summary are sent.
- **Denied input paths**: input package path filters reject `.env`, secrets/credentials,
  private-key material, dependency folders, build output, caches, database/dump files, and
  local artifacts.
- **No writes**: the harness never modifies repository code and never calls a connector.
- **Sequential only**: models run strictly one after another.
- **Missing models** are reported and skipped; the harness never auto-pulls models.
- Default qwen reviewers use low temperature (0.1), bounded context (`num_ctx=8192`),
  bounded output (`num_predict=3000`), and timeout 1800 seconds.
- The heavy opt-in reviewer uses reduced `num_ctx=6144`, `num_predict=1500`, and timeout
  900 seconds.

## Usage

```powershell
# Prerequisite: Ollama running locally with default qwen models installed.
pwsh ./scripts/local-ai-review/run-local-ai-review.ps1

# Review a different commit:
pwsh ./scripts/local-ai-review/run-local-ai-review.ps1 -DiffRef <git-sha>

# Opt into the heavy reviewer only on hardware proven to sustain it:
pwsh ./scripts/local-ai-review/run-local-ai-review.ps1 -IncludeHeavyReviewer

# Validate deny-list/default-reviewer safety without contacting Ollama:
pwsh ./scripts/local-ai-review/run-local-ai-review.ps1 -SelfTest
```

Parameters: `-DiffRef` (default OP-CAP-37 commit), `-OutputDir` (default `./output`),
`-OllamaHost` (default `http://localhost:11434`), `-IncludeHeavyReviewer` (default off),
`-SelfTest` (default off).

## Output

- `output/input-package-<stamp>.md` - exact safe package sent to the models
- `output/review-<model>-<stamp>.md` - raw model output + metadata + memory snapshots
- `output/summary-<stamp>.json` - machine-readable run summary
- `output/run-<stamp>.log`

`output/` is run-local evidence; do not commit large generated outputs.

## Interpreting Results

Treat every finding as a hypothesis. A P0/P1 from a model is only real if it can be
reproduced against the code and a failing/missing test. False positives are expected. The
authoritative source of truth is the repository + targeted tests, not the model.
