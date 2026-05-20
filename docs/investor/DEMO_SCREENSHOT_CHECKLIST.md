# Demo Screenshot Checklist

## Purpose

Capture a consistent investor demo evidence set without committing large binary artifacts by default.

Generated screenshots, if produced, should go under one of these locations:

- `artifacts/demo/`
- `docs/investor/screenshots/`

Before committing screenshots, confirm they are intentionally part of the documentation package and are not oversized.

## Local Setup

1. Start the backend:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\core-api
mvn spring-boot:run
```

2. Start the frontend:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
npm run dev
```

3. Open:

`http://localhost:3000/demo`

## Required Screenshots

Capture these views:

1. `/demo` hero and timeline.
2. `/demo` Telegram RFQ panel showing `Need brake pads for Toyota Camry 2018, 20 pcs, wholesale, Almaty.`
3. `/demo` reconciliation panel showing opening `150`, sold `34`, expected `116`, actual `100`, mismatch `-16`.
4. `/demo` analytics summary panel showing the sales amount note and channel breakdown.
5. `/demo` security/trust panel showing no quote approval, no final order, no ERP write, audit, and tenant isolation.
6. `/command-center` shell.
7. `/reconciliation` page.
8. `/audit-log` Audit / Security page.

## Visual Acceptance Checks

- Sidebar navigation is visible and usable.
- No broken route or blank page is visible.
- Fixture values are clearly labeled as demo or local fallback when backend data is not seeded.
- No screen implies real Telegram API calls, real LLM calls, ERP writes, payment integrations, or production seeding.
- Text fits inside cards and table cells at desktop width.
- Security/trust language is visible without opening developer tools.

## EPERM Browser Connector Fallback

If the in-app browser connector fails with a local `EPERM` startup issue, use a normal desktop browser:

1. Run `powershell -ExecutionPolicy Bypass -File C:\OrderPilot\OrderPilot-Core\scripts\check-local-demo.ps1`.
2. Open `http://localhost:3000/demo`.
3. Capture the checklist views manually.
4. Save screenshots only if they are intentionally being packaged for review.
