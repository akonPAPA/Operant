# Test Fixtures

Future realistic demo data for tenants, customers, products, aliases, OEM references, inventory, price rules, PDF/Excel samples, and channel conversations.

`stage2-demo/` contains API-importable CSV fixtures for the Core v1 demo data foundation. They are loaded through `scripts/seed-demo-data/seed-core-v1.ps1`; do not import them by direct database writes.

`stage3-intake/` contains small JSON/text payloads for local file, API, email, and Telegram intake testing. They contain no secrets and are intended for core-api endpoints only.
