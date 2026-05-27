# Security Principles

- AI is advisory, not authoritative.
- Frontend must not access the database directly.
- AI worker must not write business tables directly.
- Chatbot, bot, channel, and connector layers must not write business tables directly.
- All business mutations go through core-api command/services.
- Tenant isolation is mandatory.
- RBAC and ABAC are mandatory before real business workflows.
- Important actions must create audit events.
- External writes require ChangeRequest and approval in future stages.
- LLM output is untrusted input and must not be rendered as raw HTML, executed as code, or used as final ERP payload.
- Client systems remain source-of-truth by default.
- No production secrets belong in the repository.

## Current Repository Posture

The repository contains later-stage modules beyond the Stage 1 foundation. These modules are allowed to extend intake, validation, workspace, connector, and demo behavior only while preserving the same authority boundary: core-api services are the trusted mutation path, and external/AI/bot inputs remain untrusted until validated and approved.
