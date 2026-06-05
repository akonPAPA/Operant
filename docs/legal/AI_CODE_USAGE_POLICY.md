# AI-Generated Code and AI Tools Usage Policy

> This document is an internal policy, not formal legal advice.

## Purpose

This policy governs how AI coding tools and AI-generated code may be used in OrderPilot.
It protects proprietary IP, customer data, and third-party rights while still allowing
AI tools to improve productivity.

## Allowed AI usage

AI tools may be used to draft, refactor, explain, test, and document code. They are a
development aid, not an authority — their output is subject to the same review,
license, and security rules as any other code.

## Human review requirement

AI-generated code must be reviewed by a human before merge. The reviewer is responsible
for correctness, security, licensing, and fit with OrderPilot architecture and policy.

## Prohibited input to AI tools

Do not paste the following into AI tools:

- Secrets, tokens, private keys, or production credentials
- Customer data or customer documents
- Confidential third-party code or materials under NDA

Be especially careful with AI tools that may log, retain, or train on submitted input.

## Prohibited output usage

- Do not copy AI output that appears to be verbatim third-party proprietary code.
- Do not merge AI output that reproduces recognizable copyrighted code without a
  compatible license.

## Third-party code risk

AI output can unintentionally reproduce licensed or proprietary code. Treat suspicious
or unusually specific output as a potential third-party code risk and verify before use.

## Customer data rule

Do not use customer data for global model training without written contractual
permission. Customer data must stay within its authorized processing scope.

## Model training / distillation rule

Do not use a proprietary model's outputs to train, fine-tune, or distill a competing
model unless the provider's terms explicitly allow it.

## Security review rule

AI-generated security-sensitive code (auth, tenant isolation, RBAC/ABAC, audit,
webhook verification, file upload, AI output handling, connectors, external writes,
secret handling) must receive extra review. AI code does not bypass dependency,
license, or security policy.

## Commit message / PR disclosure recommendation

When a change is substantially AI-assisted, it is recommended to note that in the
commit message or pull request description so reviewers can apply appropriate scrutiny.
