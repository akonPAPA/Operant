# Security Policy

## Supported Branches

Security fixes are handled on the active development branch and the default branch for the OrderPilot Core repository. Historical stage snapshots are not supported as standalone release lines.

## Reporting a Vulnerability

Report suspected vulnerabilities privately to the repository owner. Do not open public issues with exploit details, secrets, tenant data, customer documents, webhook payloads, or credentials.

Include the affected area, reproduction steps, impact, and any relevant commit or workflow run. Redact all customer/private data before sharing evidence.

## Security Baseline

OrderPilot backend changes must preserve tenant isolation, authorization boundaries, audit behavior, deterministic approval gates, safe external-write controls, and secret redaction. CI and code-scanning workflows should use least-privilege permissions and must not use `write-all`.
