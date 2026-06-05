# Security Policy

## Supported Branches

Security fixes are handled on the active development branch and the default branch for the OrderPilot Core repository. Historical stage snapshots are not supported as standalone release lines.

## Reporting a Vulnerability

Report suspected vulnerabilities privately to the repository owner. Do not open public issues with exploit details, secrets, tenant data, customer documents, webhook payloads, or credentials.

Include the affected area, reproduction steps, impact, and any relevant commit or workflow run. Redact all customer/private data before sharing evidence.

## Security Baseline

OrderPilot backend changes must preserve tenant isolation, authorization boundaries, audit behavior, deterministic approval gates, safe external-write controls, and secret redaction. CI and code-scanning workflows should use least-privilege permissions and must not use `write-all`.

## Security-Sensitive Areas

Changes in the following areas require extra scrutiny: authentication, tenant isolation, RBAC/ABAC, audit log, webhook verification, file upload, AI output handling, connectors, external writes, and secrets handling.

## Reporting Contact and Scope

Report privately to `[SECURITY_CONTACT_EMAIL]` (or, until that mailbox exists, to the repository owner). Supported versions are limited to the current private development branch; historical stage snapshots are not supported.

This file is not a public bug bounty program and offers no reward unless such a program is explicitly announced in writing.
