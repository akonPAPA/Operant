# Core V1 UAT Checklist

| Check | Status | Evidence |
| --- | --- | --- |
| App starts locally | PENDING | Run local demo startup before UAT session. |
| Backend tests pass | PASS | `mvn test`: 312 tests, 0 failures, 0 errors after approved Maven dependency resolution. |
| Frontend lint passes | PASS | `npm.cmd run lint`. |
| Frontend tests pass | PASS | `npm.cmd run test`: 39 tests passed. |
| Frontend build passes | PASS | `npm.cmd run build`. |
| Worker tests pass | PASS | `.venv\\Scripts\\python.exe -m pytest`: 12 tests passed. |
| Tenant isolation check | PASS | Backend isolation tests included in full suite. |
| Duplicate execution replay | PASS | Stage 9B replay test. |
| No duplicate sync event | PASS | Stage 9B replay test. |
| No ConnectorCommand creation | PASS | Stage 9B execution tests. |
| Policy block visible | PASS | `CHANGE_REQUEST_EXECUTION_POLICY_BLOCKED`. |
| Audit timeline visible | PASS | Frontend contract tests. |
| Idempotency hash masked | PASS | Integration queue masks `connectorIdempotencyKeyHash`. |
| Credential placeholder does not expose secret | PASS | Stage 9B policy/UI tests. |
| Production connector remains disabled | PASS | Execution mode `DEMO_ONLY`; production activation future-only. |
| Demo flow can run end-to-end | PENDING | Execute seeded local demo walkthrough before customer UAT. |
