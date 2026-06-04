# Investor Demo Script

Date: 2026-05-23

OrderPilot is a secure AI-assisted transaction intelligence platform for B2B auto/industrial parts distributors. The demo shows how messy inbound requests become controlled business understanding without allowing AI or bots to write final business records.

## Opening

Positioning: distributors receive RFQs and product questions through email, files, spreadsheets, APIs, and chat channels. OrderPilot creates a trusted workflow where AI suggests, deterministic rules validate, humans approve risky cases, backend services write controlled workflow state, and audit records what happened.

## Scenario 1: Inbound Telegram/Bot RFQ

Show a Telegram-style inbound message entering the bot runtime. The bot normalizes the channel identity, stores the inbound message, classifies the intent with deterministic rules, and routes risky or unknown cases to review.

Trust line: the bot does not approve orders, create final quotes/orders, execute connectors, or send production customer replies.

## Scenario 2: Uploaded Document Intake

Show file/API upload intake for a PDF/Excel-style customer request. The raw file or payload is treated as untrusted and referenced through object storage metadata. The frontend and AI worker do not write directly to the database.

Trust line: uploaded content is input only, not authority.

## Scenario 3: Extraction With Confidence And Evidence

Show advisory extraction results: document/message type, intent, customer hints, fields, line items, confidence, and evidence snippets.

AI does not write directly to ERP, accounting systems, inventory, customer, product, price, quote, or order records.

Trust line: AI output is stored as suggestions/results only. It does not create customers, products, inventory, prices, quotes, orders, or ERP writes.

## Scenario 4: Deterministic Validation Issues

Show validation results around SKU/alias matching, UOM, quantity, requested date, inventory, pricing, discount/margin guardrails, compatibility, and substitute candidates where available.

Trust line: rules validate. Low confidence, ambiguous matches, out-of-policy discounts, risky substitutes, and unavailable stock require review or approval.

## Scenario 5: Operator Review Cockpit

Show review cases grouped by customer, document, line/product, pricing, inventory, substitution, and policy issues. Show internal notes and corrective suggestions.

Trust line: approving a review means approved for next stage only. It does not create a quote/order or execute an external write.

## Scenario 6: Bot Handoff / Needs-Review Flow

Show bot handoff records and conversation state linked to review when identity, price, order status, or ambiguity makes automation unsafe.

Trust line: human review exists for risky actions.

## Scenario 7: Read-only Analytics Command Center

Show analytics for intake volume, channel breakdown, extraction status, validation issues, review backlog, bot handoffs, workflow health, and automation readiness.

Trust line: analytics is read-only. It does not approve anything, mutate master data, send messages, create quotes/orders, execute connectors, or write to ERP.

## Scenario 8: Security And Trust Explanation

Close with the control model:

- Tenant isolation is mandatory.
- APIs use tenant-scoped service/repository access.
- Explicit demo permission headers can deny access to protected endpoint categories.
- Structured errors avoid stack traces in responses.
- Raw uploaded/channel data is untrusted.
- Prompt injection is document content only.
- Audit is append-oriented by service convention.
- External writes are outside this stage and require controlled command/approval paths in later approved roadmap stages.

## Honest Limitations

Stage 9 improves security, reliability, observability, and demo readiness. It does not make OrderPilot fully production-ready. It does not implement ERP/1C/SAP/Dynamics/Oracle writes, Local Windows Connector, desktop agent, final quote/order automation, production outbound messaging, production WhatsApp/WeChat runtime, or paid OCR/LLM integrations.
