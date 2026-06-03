# Investor Demo Script - Core V1

## Demo Goal

Show that OrderPilot is an investor-grade B2B SaaS transaction intelligence platform with controlled write paths, tenant isolation, audit, explainability, bot intake, validation, and reconciliation.

## Flow 1 - Telegram RFQ Intake

1. Send a Telegram-style RFQ message: "Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty."
2. Call `POST /api/v1/bot/telegram/webhook`.
3. Show that Bot Runtime Lite creates a bot conversation, bot message, and internal RFQ draft.
4. Explain that the bot response requires human review.
5. Point out that no approved quote, final order, inventory update, price update, customer update, Telegram API call, or ERP write occurs.

## Flow 2 - Unknown Bot Message Handoff

1. Send an unclear message: "Can you check the thing we discussed last time?"
2. Show that the message routes to human handoff.
3. Explain that customer messages are hostile input and external chat IDs are not trusted identity.

## Flow 3 - Import/PDF Validation

If demo data is available, show the import validation flow:
1. Create/import staged data.
2. Run validation.
3. Show deterministic validation results and validation report.
4. Explain that AI suggestions are advisory and validation is the controlled gate.

## Flow 4 - Inventory Reconciliation Mismatch

Use the canonical scenario:
- opening stock = 150;
- sale = 34;
- expected stock = 116;
- actual stock = 100;
- mismatch -16.

Run `POST /api/v1/reconciliation/inventory/run`.

Show:
- expected stock `116`;
- actual stock `100`;
- mismatch quantity `-16`;
- severity `HIGH`;
- open reconciliation case;
- audit event for discrepancy creation.

## Flow 5 - Analytics Summary

Call `GET /api/v1/analytics/commerce/summary`.

Show:
- total sales amount is currently `0` with a clear TODO until invoice/sales mirrors are added;
- draft order count;
- total bot RFQ requests;
- open reconciliation cases;
- high severity reconciliation cases;
- channel breakdown for Telegram.

## Security and Trust Explanation

Use these talking points:
- tenant isolation is a product feature, not just infrastructure;
- AI is advisory, not authoritative;
- all risky actions require backend validation, approval where needed, and audit;
- no direct DB writes are permitted outside services;
- no ERP writes exist in the current stage;
- audit events support explainability and review;
- Stage 9 prepares the system for security review, but is not Stage 10 pilot launch.

## 3-Minute Flow

1. Open the web dashboard at `/demo`.
2. Show the Telegram RFQ request for Toyota Camry 2018 brake pads.
3. Click **Send demo Telegram RFQ** and show RFQ draft/human review response.
4. Explain that the bot cannot approve quotes, create orders, update ERP, or mutate inventory/prices/customers.
5. Click **Run inventory reconciliation** and show expected `116`, actual `100`, mismatch `-16`, severity `HIGH`.
6. Click **Refresh analytics** and show RFQ count, Telegram channel activity, open high-severity reconciliation case, and total sales amount `0` when invoice/sales mirrors are not present.
7. Close on the safe write path: backend services, deterministic validation, approval where needed, tenant isolation, and audit.

## 10-Minute Flow

1. Set context on `/demo`: demo tenant is `OrderPilot Demo Parts Distributor`, customer is `Almaty Auto Service`, and catalog includes OEM plus aftermarket brake pad options.
2. Walk the timeline from Telegram RFQ through audit/security.
3. Run the Telegram RFQ and show the internal RFQ draft.
4. Run the unknown Telegram message and show human handoff.
5. Discuss import/PDF validation where available: staged import validation exists, while the Stage 9B fixture is documentation-only for PDF/import.
6. Run the reconciliation mismatch scenario.
7. Fetch reconciliation cases and analytics summary.
8. Visit Command Center, Omnichannel Inbox, Bot / Conversations, Reconciliation, Analytics, Audit / Security, and Integrations to show a clean product surface beyond a single page.
9. Close with the security model: AI is advisory, no direct DB writes, no ERP writes, audit events are generated for critical flows, and tenant isolation is tested.

## Demo Assets

- Fixture files: `apps/core-api/src/test/resources/demo/core-v1-demo/`
- Request walkthrough: `docs/investor/demo-api-walkthrough.http`
- Web route: `apps/web-dashboard/app/(dashboard)/demo/page.tsx`
- Local runbook: `docs/runbooks/LOCAL_DEMO_RUNBOOK.md`
- Smoke test: `CoreV1InvestorDemoSmokeTest`
