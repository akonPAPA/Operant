# Security Verification Checklist

Use this checklist before investor demos, design-partner walkthroughs, and security reviews.

- [x] Tenant isolation tests cover latest bot, reconciliation, and analytics flows.
- [x] Bot cannot create final order.
- [x] Bot cannot approve quote.
- [x] Bot cannot update inventory, prices, or customers.
- [x] AI worker cannot write business tables directly in the current architecture.
- [x] No hardcoded real bot token is required for Stage 7.
- [x] Audit events emitted for important bot and reconciliation actions.
- [x] No public API endpoint updates or deletes audit events.
- [x] Explicit demo permission header checks deny analytics/review/bot/intake/extraction/validation categories when an insufficient permission header is supplied.
- [x] Analytics remains read-only and returns tenant-scoped counts/statuses only.
- [x] AI output remains advisory and cannot directly create quotes, orders, ERP writes, or master-data writes.
- [x] External writes are outside approved Stage 1-9 scope and require explicit controlled approval paths in later approved roadmap stages.
- [ ] File upload validation includes production size limits, type sniffing, malware scanning, and quarantine.
- [ ] Webhook validation includes production signed payloads and replay window enforcement.
- [ ] Rate limiting exists per tenant/IP/provider.
- [ ] Backup/restore has been exercised.
- [ ] Dependency scan is run in CI.
- [ ] Production DB enforces audit append-only permissions or trigger/policy.
- [ ] Connector credentials are stored in a secrets manager.
