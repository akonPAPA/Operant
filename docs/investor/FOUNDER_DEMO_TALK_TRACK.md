# Founder Demo Talk Track

## 1. Opening

OrderPilot helps B2B distributors turn messy customer requests into controlled, auditable quote and order work. It combines intake, document/message understanding, validation, exception handling, analytics, and a safe integration boundary in one operator workflow.

## 2. Problem

Distributors do not receive clean e-commerce carts. They receive email threads, PDFs, Excel files, messenger messages, partial SKUs, substitutions, pricing exceptions, stock uncertainty, and urgent follow-ups. Teams spend time retyping, checking, correcting, and deciding what is safe to send downstream.

## 3. Demo Scenario

A customer request comes in through a document, message, or bot conversation. OrderPilot captures it as tenant-scoped work, preserves evidence, and routes it into the same operating model instead of trusting free-form customer text.

## 4. System Understanding

The system extracts structured fields and line items, tracks confidence and evidence, and makes uncertainty visible. The point is not to pretend every request is perfect. The point is to make the uncertain parts easy for an operator to inspect.

## 5. Validation

OrderPilot checks the request against business context: customer, SKU, unit of measure, stock, price, margin, discount, and substitute options. The dashboard shows where the request is ready and where it needs review.

## 6. Human Control

Risky or incomplete work goes to review. Operators can inspect exceptions, correct data, prepare drafts, and keep approvals behind backend gates. Bot-only handoffs do not become validation-backed quote or order work by themselves.

## 7. Integration Safety

When an approved validation-backed draft is ready for integration proof, it becomes a `ChangeRequest`. In this demo, execution is local Demo ERP only. There is no direct production ERP write, no 1C write, no external connector network call, and no bot-triggered connector command.

The idempotency proof is deliberate: executing the same approved ChangeRequest again reuses the same external reference and `sha256:*` idempotency hash. Replay is audited as reuse, not a second mutation.

## 8. Audit

The audit timeline answers who did what, when, and why. It records attempts, success, failure, replay, cancellation, and policy blocks. Non-demo targets are blocked before adapter execution and recorded as `CHANGE_REQUEST_EXECUTION_POLICY_BLOCKED`.

## 9. Business Value

The business value is less manual handling, fewer avoidable errors, faster quote/order preparation, and clearer risk evidence. Command Center and value metrics show request volume, exception rates, blocked unsafe attempts, reconciliation issues, and estimated labor savings.

## 10. Closing

Core v1 demonstrates the value path and the control path together. It is intentionally not autonomous production execution. Production connectors, real ERP/1C writes, external connector network calls, raw secrets, inventory mutation through integration paths, and bot-triggered connector commands remain disabled until a separate security and runbook acceptance phase.
