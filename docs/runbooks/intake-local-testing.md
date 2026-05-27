# Intake Local Testing

Use these commands after the backend is running on `http://localhost:8080`.

```powershell
$tenantId = "<demo-tenant-id>"
```

## Health

```powershell
Invoke-RestMethod "http://localhost:8080/api/v1/health"
```

## File Upload

```powershell
curl.exe -X POST "http://localhost:8080/api/v1/intake/documents/upload" `
  -H "X-Tenant-Id: $tenantId" `
  -F "sourceChannel=FILE_UPLOAD" `
  -F "documentType=CUSTOMER_RFQ" `
  -F "file=@packages/test-fixtures/stage3-intake/tiny-upload.sample.txt;type=text/plain"
```

Allowed file types are PDF, CSV, XLS, XLSX, TXT, PNG, JPG, and JPEG. Default max size is 10 MB.

## API Upload

```powershell
curl.exe -X POST "http://localhost:8080/api/v1/intake/api-upload" `
  -H "X-Tenant-Id: $tenantId" `
  -H "Content-Type: application/json" `
  --data-binary "@packages/test-fixtures/stage3-intake/api-upload.sample.json"
```

## Email Stub

```powershell
curl.exe -X POST "http://localhost:8080/api/v1/webhooks/email" `
  -H "X-Tenant-Id: $tenantId" `
  -H "Content-Type: application/json" `
  --data-binary "@packages/test-fixtures/stage3-intake/email-webhook.sample.json"
```

## Telegram Stub

```powershell
curl.exe -X POST "http://localhost:8080/api/v1/webhooks/telegram" `
  -H "X-Tenant-Id: $tenantId" `
  -H "Content-Type: application/json" `
  --data-binary "@packages/test-fixtures/stage3-intake/telegram-update.sample.json"
```

## Verification Reads

```powershell
Invoke-RestMethod "http://localhost:8080/api/v1/intake/documents" -Headers @{ "X-Tenant-Id" = $tenantId }
Invoke-RestMethod "http://localhost:8080/api/v1/intake/messages" -Headers @{ "X-Tenant-Id" = $tenantId }
Invoke-RestMethod "http://localhost:8080/api/v1/intake/events" -Headers @{ "X-Tenant-Id" = $tenantId }
Invoke-RestMethod "http://localhost:8080/api/v1/intake/jobs" -Headers @{ "X-Tenant-Id" = $tenantId }
```

## Notes

Webhook payloads are dev stubs. Configure `orderpilot.webhooks.email.dev-token` or `orderpilot.webhooks.telegram.secret-token` to require local token headers. Without configured tokens, unsigned webhooks are accepted only because `orderpilot.webhooks.dev-accept-unsigned=true` is the local default.
