# Web Dashboard

Next.js TypeScript dashboard shell for OrderPilot.

The frontend must not connect directly to PostgreSQL or any trusted business data store. Future mutations must call core-api command endpoints.

## Run

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\web-dashboard"
npm install
npm run dev
```

## Verify

```powershell
npm run lint
npm run build
```