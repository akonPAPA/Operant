# CTO / Founding Backend Lead Scorecard

## Must-have

- Strong Java/Spring Boot backend experience.
- PostgreSQL and database design experience.
- Security mindset: RBAC, ABAC, tenant isolation, audit logs.
- Experience with integrations: ERP, CRM, accounting, APIs, webhooks, file imports.
- Can design modular monolith before premature microservices.
- Can explain trade-offs clearly.
- Can review AI-generated code critically.
- Can build reliable systems, not only demos.

## Strong signals

- Built B2B SaaS before.
- Worked with order management, ERP, logistics, inventory or finance systems.
- Understands async jobs, transactional outbox, idempotency, queues.
- Understands document processing or data pipelines.
- Comfortable leading small engineering team.

## Red flags

- Wants to make everything microservices immediately.
- Says “AI can just update the database directly”.
- Ignores tenant isolation.
- Ignores audit logs.
- Focuses only on UI and ignores backend correctness.
- Cannot explain failure modes.
- Cannot write or review tests.

## Interview questions

1. How would you prevent AI worker from writing directly to business tables?
2. How would you design tenant isolation for a B2B SaaS?
3. When would you use modular monolith instead of microservices?
4. How would you design an audit log that users cannot silently modify?
5. How would you process uploaded Excel/PDF files safely?
6. How would you handle retries for ERP connector writes?
7. How would you design idempotency for quote/order creation?
8. How would you explain OrderPilot architecture to a non-technical investor?