# Stage 13B Investor Demo Script

## Scenario

OrderPilot is shown as a controlled B2B distributor workflow for Steppe Logistics requesting `2 EA` Toyota Camry 2018 front brake pads. The primary product, `PAD-OE-04465`, is out of stock in `WH-ALM`; `PAD-SUB-ADV` and `PAD-SUB-ECON` exist as substitute candidates. The walkthrough shows capture, policy routing, operator review, draft quote review, approval controls, and audit evidence. It does not show autonomous ordering or ERP execution.

## Setup Assumptions

- Core API is running at `http://localhost:8080`.
- Web dashboard is running at `http://localhost:3000`.
- Demo fixture data from `packages/test-fixtures/stage2-demo` has been loaded if backend-backed screens are used.
- Frontend `NEXT_PUBLIC_DEMO_TENANT_ID` points to the seeded tenant.
- External connector or ERP execution remains disabled.

## Walkthrough

1. Open `/demo`.
2. Explain the business context: a distributor receives messy Telegram/RFQ-style demand and must avoid bad quotes, leaked prices, and uncontrolled orders.
3. Click `Send demo Telegram RFQ`. The demo message is `Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.`
4. Show the Telegram RFQ panel:
   - detected intent is RFQ-oriented;
   - operator review is required;
   - the safe response does not approve price, stock, substitute, quote, order, or ERP action;
   - external execution is `DISABLED`.
5. Open `/bot-conversations`.
6. Show the captured conversation, policy decision, handoff reason, and operator review queue state.
7. Open `/quote-review`.
8. Show the review queue and then a quote review detail:
   - customer is Steppe Logistics when seed data is present;
   - product, quantity, validation status, stock/substitute state, and approval requirements are visible;
   - issue reason codes are shown in readable form;
   - the audit timeline summarizes controlled decisions.
9. Open `/quotes`.
10. Use the RFQ-to-draft form defaults:
    - customer `CUST-001`;
    - product `PAD-OE-04465`;
    - location `WH-ALM`;
    - quantity `2 EA`.
11. Create the draft quote, then show:
    - resolved customer/product;
    - validation and substitute context;
    - approval status;
    - approval/reject/request-changes commands;
    - conversion remains internal-only and external ERP write is not executed.
12. Close on the safety narrative: OrderPilot creates reviewable work and audit evidence, not autonomous risky business actions.

## What This Proves

- Tenant-scoped bot intake can capture a buyer message.
- Intent and policy routing are deterministic and operator-controlled.
- Sensitive flows require human review.
- Quote approval is enforced by backend state transitions.
- Audit metadata explains important actions and keeps `externalExecution=DISABLED` visible.
- Demo screens are operational surfaces, not a static marketing mock.

## Intentionally Not Enabled

- No real Telegram outbound send.
- No ERP, 1C, connector, or external order write.
- No autonomous quote approval.
- No autonomous substitute approval.
- No master-data mutation by the bot.
- No customer-specific price disclosure from bot policy-review paths.

## Known Limitations

- The local demo depends on seeded tenant IDs and environment variables.
- Some pages still display shortened technical IDs because richer read models are not complete.
- The bot simulation is local and controlled; real channel deployment is outside this stage.
- Audit metadata formatting is improved for the demo path but not globally normalized across every historical event.
