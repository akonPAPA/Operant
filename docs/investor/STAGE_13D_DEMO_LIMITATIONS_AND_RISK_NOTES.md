# Stage 13D Demo Limitations And Risk Notes

## Intentional Safety Limitations

- External ERP, 1C, warehouse, accounting, and connector execution are intentionally disabled.
- Quote approval is not autonomous.
- Substitute approval is not autonomous.
- Real Telegram outbound send is not enabled.
- Bot behavior is constrained to the rehearsed capture, classification, policy, and review handoff story.
- Quote conversion remains internal-only; it does not execute external writes.

## Demo Readiness Risks

- Backend-backed screens require seeded tenant and environment IDs. If `NEXT_PUBLIC_DEMO_TENANT_ID` is missing or points to the wrong tenant, pages may show empty states or backend seed/config limitation messages.
- Product and location IDs must be configured when showing backend-backed reconciliation or demo controls outside the core RFQ story.
- Some technical IDs are still shortened because richer read models do not exist yet.
- Audit metadata is readable for the demo path, not globally normalized across all historical event types.
- Local service health matters. If Core API, the dashboard, PostgreSQL, Redis, or the AI worker environment is unavailable, the presenter should describe it as local demo readiness, not product behavior.

## Investor Communication Guardrails

- Say that `externalExecution=DISABLED` is intentional and visible.
- Say that humans approve quotes and substitutes.
- Say that the bot routes and assists; it does not approve or execute risky business actions.
- Say that ERP/1C integration is a future controlled connector boundary, not part of this investor demo.
- Say that the audit trail is strong for this demo path while broader historical audit normalization remains future hardening.

## Remaining Risks After Freeze

- Seed data drift can break the clean route if the tenant or IDs are changed.
- Reusing an old browser session can show stale demo results.
- Backend failures can make the walkthrough fall back to fixture/explanation mode.
- The presenter can overstate autonomy if the talk track is not followed.

## Recommendation

Freeze the investor demo if the Stage 13D preflight passes and the verification command set completes without regressions. Keep the scope limited to the frozen RFQ story and safety posture until after the investor walkthrough.
