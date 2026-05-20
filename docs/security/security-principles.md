# Security Principles

- AI is advisory, not authoritative.
- Frontend, chatbot, AI worker, and connectors must not directly write business tables.
- All mutations go through core-api command services.
- Tenant isolation is mandatory.
- RBAC and ABAC are mandatory before real business workflows.
- Audit events are mandatory for important actions.
- External writes require ChangeRequest and approval in future stages.
- LLM output is untrusted input and must not be rendered as raw HTML, executed as code, or used as final ERP payload.
- Client systems remain source-of-truth by default.
- No production secrets belong in the repository.