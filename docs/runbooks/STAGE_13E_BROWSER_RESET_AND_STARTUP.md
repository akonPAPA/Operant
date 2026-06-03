# Stage 13E Browser Reset And Startup

## Purpose

Use this runbook immediately before the investor walkthrough to avoid stale browser state and to start services in a predictable order.

## Clean Browser Session

Recommended approach:

1. Close old demo tabs.
2. Open a new browser window or private/incognito window.
3. Navigate only to the walkthrough routes.
4. Avoid using old bookmarked detail pages because IDs can point to stale tenant data.

If using the same browser session:

1. Open browser developer tools.
2. Clear local storage for `http://localhost:3000`.
3. Clear session storage for `http://localhost:3000`.
4. Clear application cache/service worker data if present.
5. Hard reload the page.

Console reset commands when applicable:

```javascript
localStorage.clear();
sessionStorage.clear();
```

Then hard reload `http://localhost:3000/demo`.

## Startup Order

1. Confirm seeded environment values.
2. Start Core API.
3. Verify backend health.
4. Start web dashboard.
5. Open a clean browser session.
6. Follow the recommended route order.

Backend startup:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\core-api
mvn spring-boot:run
```

Frontend startup:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
npm.cmd run dev
```

## Backend Health Check

Use one of these checks after Core API starts:

```powershell
Invoke-WebRequest http://localhost:8080/actuator/health
```

or:

```powershell
curl.exe http://localhost:8080/actuator/health
```

Expected result:

- HTTP status is `200`.
- Body reports `UP` or an equivalent healthy status.

If health fails:

- Do not continue the live walkthrough.
- Check backend terminal output.
- Confirm dependencies and seeded environment.
- Use static screenshot fallback only if clearly labeled as demo evidence.

## Recommended Browser Route Order

1. `http://localhost:3000/demo`
2. `http://localhost:3000/bot-conversations`
3. `http://localhost:3000/quote-review`
4. `http://localhost:3000/quotes`

Open routes manually in this order. Avoid jumping to old quote detail URLs unless the quote was created during the current rehearsal or preflight.

## Avoiding Stale Demo State

- Use a fresh idempotency key for quote draft creation.
- Prefer the default form values instead of typing alternate demo data.
- Do not reuse old quote IDs as evidence for the current walkthrough.
- Do not leave stale error banners visible when presenting the successful path.
- If a backend-backed page shows an empty state, explain it as seeded tenant/config readiness rather than product behavior.
- Keep the RFQ text unchanged: `Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.`
- Keep `externalExecution=DISABLED` visible anywhere execution risk is discussed.
