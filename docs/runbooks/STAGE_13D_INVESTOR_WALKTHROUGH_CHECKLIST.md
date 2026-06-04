# Stage 13D Investor Walkthrough Checklist

Use this checklist during the investor walkthrough. Follow the route order; avoid detours unless the core story has already landed.

## Route 1: `/demo`

Presenter wording:

OrderPilot starts with a realistic distributor RFQ from Steppe Logistics: `Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.` The bot can capture and classify the request, but this is a controlled review path, not autonomous quote approval.

Safety/trust point:

The first screen proves the RFQ text, tenant-aware backend call, operator review route, and visible `externalExecution=DISABLED` boundary.

Do not claim:

Do not claim the bot sends live Telegram responses, approves price, approves stock, creates orders, or writes ERP/1C.

## Route 2: `/bot-conversations`

Presenter wording:

This view shows the conversation record behind the demo RFQ. The important pieces are captured intent, policy decision, review handoff, and the absence of unsafe action controls.

Safety/trust point:

The bot runtime is auditable and review-first. It turns a channel message into a controlled work item instead of a business action that bypasses an operator.

Do not claim:

Do not claim broad bot autonomy or unrestricted customer-specific price disclosure.

## Route 3: `/quote-review`

Presenter wording:

The quote review cockpit is where operations can inspect validation issues, substitute context, approval requirements, and the audit timeline. The demo shows why this RFQ needs review before any customer-facing or downstream action.

Safety/trust point:

Validation, substitutes, and approvals are visible before action. Audit metadata on the demo path remains readable and includes disabled external execution.

Do not claim:

Do not claim substitute approval is automatic. Do not claim every historical audit event has the same normalized read model.

## Route 4: `/quotes`

Presenter wording:

The draft quote form uses the same frozen story: customer `CUST-001`, product `PAD-OE-04465`, warehouse `WH-ALM`, quantity `2 EA`. Creating a draft quote runs backend validation and exposes approval status, blocking issues, substitute candidates, and audit correlation.

Safety/trust point:

Quote approval and internal conversion are controlled by backend state transitions. External ERP write remains disabled and is shown as not executed.

Do not claim:

Do not claim autonomous quote approval, production order creation, inventory reservation, ERP posting, 1C posting, connector execution, or real outbound send.

## Close

Presenter wording:

The demo is intentionally conservative: OrderPilot captures messy demand, routes risky work to humans, validates draft quotes, shows audit evidence, and keeps external execution disabled until a separately approved connector flow exists.

Safety/trust point:

The product claim is controlled operator acceleration, not unsupervised automation.

Do not claim:

Do not claim the demo proves production rollout readiness for every integration. It proves the controlled RFQ-to-review-to-draft workflow and safety posture.
