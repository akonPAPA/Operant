# Stage 13E Demo Preflight Evidence

## Evidence Capture

- Date/time: `YYYY-MM-DD HH:MM local time`
- Operator:
- Machine/environment:
- Git branch/commit:
- Core API URL: `http://localhost:8080`
- Web dashboard URL: `http://localhost:3000`
- Seeded tenant ID:
- Notes:

## Environment Assumptions

- Stage 13D remains frozen.
- The investor walkthrough uses the Steppe Logistics RFQ story only.
- Backend-backed screens use seeded tenant/environment IDs.
- Core API runs locally on port `8080`.
- Web dashboard runs locally on port `3000`.
- Node/npm dependencies are installed for `apps/web-dashboard`.
- Java 21 and Maven are available for `apps/core-api`.
- The AI worker virtualenv exists at `apps/ai-worker/.venv`.
- External execution remains disabled across the demo path.

## Startup Commands

Start backend first:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\core-api
mvn spring-boot:run
```

Start the web dashboard after backend health is known:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
npm.cmd run dev
```

Use the seeded environment values before starting the dashboard:

```powershell
$env:NEXT_PUBLIC_CORE_API_URL="http://localhost:8080"
$env:NEXT_PUBLIC_DEMO_TENANT_ID="<seeded tenant id>"
$env:NEXT_PUBLIC_DEMO_PRODUCT_ID="<seeded product id if needed>"
$env:NEXT_PUBLIC_DEMO_LOCATION_ID="<seeded location id if needed>"
```

## Verification Commands

Run the full freeze gate before signoff:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\core-api
mvn test

cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
npm.cmd run lint
npm.cmd exec tsc -- --noEmit --incremental false
npm.cmd test
npm.cmd run build

cd C:\OrderPilot\OrderPilot-Core\apps\ai-worker
.\.venv\Scripts\python.exe -m pytest
```

## Expected Pass Outputs

- `mvn test`: Maven exits `0`; test summary has no failures or errors.
- `npm.cmd run lint`: ESLint exits `0`; no blocking lint errors.
- `npm.cmd exec tsc -- --noEmit --incremental false`: TypeScript exits `0`; no type errors.
- `npm.cmd test`: Node test runner exits `0`; all dashboard tests pass, including the Stage 13E frozen demo preflight assertion.
- `npm.cmd run build`: Next.js build exits `0`; production build completes.
- `.\.venv\Scripts\python.exe -m pytest`: Pytest exits `0`; all AI worker tests pass.

## Expected Fail Outputs

Any of these blocks live investor walkthrough until resolved or moved to the approved fallback:

- Non-zero exit from any verification command.
- Missing seeded tenant ID for backend-backed screens.
- `/demo` does not show the frozen RFQ text.
- `/quotes` does not show the frozen defaults.
- `externalExecution=DISABLED` is hidden, changed, or contradicted.
- UI copy implies autonomous quote approval, substitute approval, ERP/1C write, connector write, real Telegram outbound send, or external execution.
- Backend health check fails and cannot be restored before the session.

## Demo Route Checklist

### `/demo`

- [ ] Page loads.
- [ ] RFQ text is visible exactly as `Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.`
- [ ] Send demo Telegram RFQ control is visible.
- [ ] Policy route shows operator review.
- [ ] `externalExecution=DISABLED` or `External execution: DISABLED` is visible.
- [ ] No unsafe approval, order, ERP, connector, or outbound-send control is presented.

### `/bot-conversations`

- [ ] Page loads with the seeded tenant or a clear seed/config limitation.
- [ ] Captured intent is visible when backend data is present.
- [ ] Policy decision and review handoff are visible.
- [ ] No controls imply autonomous quote approval, substitute approval, or external execution.

### `/quote-review`

- [ ] Review queue loads or shows a clear seeded-data limitation.
- [ ] Review detail shows validation issues when seeded data is present.
- [ ] Substitute context and approval needs are visible when seeded data is present.
- [ ] Audit timeline is visible when events exist.
- [ ] `externalExecution=DISABLED` is visible.

### `/quotes`

- [ ] Draft quote form loads.
- [ ] Customer default is `CUST-001`.
- [ ] Product default is `PAD-OE-04465`.
- [ ] Warehouse/location default is `WH-ALM`.
- [ ] Quantity and unit defaults are `2 EA`.
- [ ] Approval controls are operator initiated.
- [ ] Internal conversion copy says external ERP write is disabled or not executed.

## Frozen Payload And Defaults Evidence

The frozen demo evidence must show:

| Evidence item | Expected value |
| --- | --- |
| RFQ text | `Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.` |
| Customer | `CUST-001` |
| Product | `PAD-OE-04465` |
| Warehouse | `WH-ALM` |
| Quantity | `2 EA` |
| External execution | `DISABLED` |

The dashboard static test `Stage 13E final preflight keeps frozen demo safe and reproducible` is the code-level evidence that these defaults remain present in the demo surfaces.

## No Autonomous External Execution Evidence

- `/demo` states `externalExecution=DISABLED`.
- `/quote-review` states `externalExecution=DISABLED` and external ERP write is disabled or not executed.
- `/quotes` states external ERP write is disabled or not executed.
- Quote approval controls require operator action.
- Substitute approval remains review controlled.
- There is no live Telegram outbound send in this walkthrough.
- There is no ERP, 1C, connector, warehouse, or accounting write in this walkthrough.

## Known Acceptable Limitations From Stage 13D

- Backend-backed screens require seeded tenant and environment IDs.
- Product and location IDs are needed for optional backend-backed controls outside the core RFQ story.
- Some technical IDs are still shortened because richer read models do not exist yet.
- Audit metadata is readable for the demo path, not globally normalized across all historical event types.
- Local service health can force fallback to static demo evidence.
- Browser state from prior rehearsals can show stale results unless reset before the walkthrough.
- The presenter must not overstate autonomy.
