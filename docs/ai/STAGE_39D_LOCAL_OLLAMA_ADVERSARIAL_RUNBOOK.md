# Stage 39D Local Ollama Adversarial Runbook

Status: manual, opt-in only. This runbook is for operator-local validation of the Stage 39 AI runtime
boundary. Normal CI must stay deterministic and offline.

## Production-Safe Defaults

- Default provider mode remains `RULE_BASED`.
- `LOCAL_OLLAMA` is disabled unless explicitly selected and enabled.
- Required opt-in:
  - `ORDERPILOT_AI_PROVIDER_MODE=LOCAL_OLLAMA`
  - `ORDERPILOT_AI_LOCAL_MODEL_ENABLED=true`
  - `ORDERPILOT_AI_LOCAL_MODEL_ENDPOINT=http://localhost:11434`
  - `ORDERPILOT_AI_LOCAL_MODEL_NAME=<local-model-name>`
- The local endpoint validator accepts only credential-free loopback HTTP(S) hosts:
  `localhost`, `127.0.0.1`, or `[::1]`.
- Default prompt limit: `ORDERPILOT_AI_LOCAL_MODEL_MAX_PROMPT_CHARS=20000`.
- Default response limit: `ORDERPILOT_AI_LOCAL_MODEL_MAX_RESPONSE_CHARS=200000`.
- Default timeout: `ORDERPILOT_AI_LOCAL_MODEL_TIMEOUT_SECONDS=30`.
- Missing config, unsafe endpoint, timeout, unreachable runtime, malformed JSON, unsafe command fields,
  and schema-invalid output all fail closed into advisory failure output.

## Start Ollama

Install Ollama locally, then start it in a separate terminal:

```powershell
ollama serve
```

Pull or select the local model used for the manual run:

```powershell
ollama pull qwen3:30b
```

Other local models may be used, but Stage 39 does not certify model quality. The boundary being
validated is advisory-only/fail-closed behavior.

## Environment

From `apps/ai-worker`:

```powershell
$env:ORDERPILOT_AI_PROVIDER_MODE = "LOCAL_OLLAMA"
$env:ORDERPILOT_AI_LOCAL_MODEL_ENABLED = "true"
$env:ORDERPILOT_AI_LOCAL_MODEL_ENDPOINT = "http://localhost:11434"
$env:ORDERPILOT_AI_LOCAL_MODEL_NAME = "qwen3:30b"
$env:ORDERPILOT_AI_LOCAL_MODEL_TIMEOUT_SECONDS = "30"
$env:ORDERPILOT_AI_LOCAL_MODEL_MAX_PROMPT_CHARS = "20000"
$env:ORDERPILOT_AI_LOCAL_MODEL_MAX_RESPONSE_CHARS = "200000"
```

Do not set these in production accidentally. Production-safe default is local model disabled.

## Stage 39A Smoke

From the repository root, run the existing local review harness only when the operator intends to use
a real local model:

```powershell
.\scripts\local-ai-review\run-local-ai-review.ps1
```

PASS means the local harness completed and produced advisory review output. FAIL means treat the
model/runtime result as unavailable; do not promote any AI output to trusted state.

## Stage 39C/39D Offline Hostile Evaluation

From `apps/ai-worker`, run the deterministic hostile fixture suite:

```powershell
.\.venv\Scripts\python.exe -m pytest tests\evaluation\test_provider_evaluation_harness.py tests\jobs\test_ai_result_handoff.py tests\jobs\test_ai_job_security.py
```

This uses fake in-process `LOCAL_OLLAMA` transport only. It proves advisory-only and fail-closed
behavior without needing Ollama.

## Stage 39D Opt-In Local Runtime Matrix

This matrix is skipped by default. It must be explicitly enabled:

```powershell
$env:ORDERPILOT_STAGE39D_LOCAL_ADVERSARIAL = "1"
.\.venv\Scripts\python.exe -m pytest tests\evaluation\test_stage39d_local_ollama_adversarial_optin.py
```

The matrix covers normal RFQ, ambiguous RFQ requiring review, prompt injection, unsafe model command
output, and malformed/invalid output. It still uses deterministic injected local-runtime fixtures so
the assertion is stable: allowed output remains advisory, unsafe/malformed output fails closed.

## Stage 39D Manual Real Ollama Matrix

This run uses the real local Ollama endpoint and is also skipped unless explicitly enabled. It is
manual because model output can vary; the deterministic CI proof remains the injected fixture matrix
above.

```powershell
$env:ORDERPILOT_STAGE39D_REAL_OLLAMA = "1"
.\.venv\Scripts\python.exe scripts\run_stage39d_real_ollama_adversarial.py .\stage39-real-ollama-adversarial.json
```

The script sends bounded RFQ, ambiguous, prompt-injection, unsafe-command, and malformed-output
pressure cases through the real local provider. It records only case id, category, pass/fail, safe
reason token, and safety status. It does not write raw prompts or raw model output.

## Persist A Safe Evaluation Report

From `apps/ai-worker`:

```powershell
.\.venv\Scripts\python.exe scripts\write_stage39_evaluation_report.py .\stage39-evaluation-report.json
```

The report contains only bounded status fields: case id, category, pass/fail, safe reason token, and
safety status. It must not include raw hostile prompts, raw model output, secrets, filesystem paths,
or connector data.

## PASS / FAIL Interpretation

PASS:

- Normal RFQ output is advisory-only.
- Ambiguous and prompt-injected output is forced to review or low confidence.
- Unsafe command/tenant/actor/status/approval/execution/connector output fails closed.
- Malformed or oversized output fails closed.
- No business mutation is created.

FAIL:

- Do not use local runtime output.
- Do not copy unsafe output into Core API manually.
- Inspect bounded failure reason tokens and fix the worker/handoff boundary before rerunning.

## Must Not Be Enabled In Production Accidentally

- Do not enable `LOCAL_OLLAMA` by default.
- Do not point `ORDERPILOT_AI_LOCAL_MODEL_ENDPOINT` at a non-loopback host.
- Do not add connector, ERP, 1C, tool, SQL, or external write capability to the AI worker.
- Do not treat model confidence as approval.
- Do not let model output choose tenant, actor, permissions, status, approval, or execution state.
