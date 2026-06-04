# Demo Dataset - Core V1

## Demo Tenant

Use a realistic parts distributor tenant:
- tenant slug: `orderpilot-demo-parts-distributor`;
- tenant name: `OrderPilot Demo Parts Distributor`;
- customer: `Almaty Auto Service`;
- primary product: `TOY-CAM-2018-BPAD-OE` / original brake pads for Toyota Camry 2018;
- substitute products: `AFT-CAM-2018-BPAD-A` and `AFT-CAM-2018-BPAD-B`;
- location: `ALM-MAIN` / Almaty Main Warehouse.

## Telegram RFQ Text

Recommended RFQ message:

```text
Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.
```

Expected result:
- bot conversation created;
- bot message created;
- bot RFQ request created;
- requires human review;
- audit event emitted.

## Unknown Message

```text
Can you check the thing we discussed last time?
```

Expected result:
- human handoff created;
- no business data mutation.

## Inventory Reconciliation Scenario

Create movements:
- opening stock = 150;
- sale = 34;
- actual stock = 100.

Expected calculation:
- expected stock = 116;
- actual stock = 100;
- mismatch -16;
- severity HIGH;
- open reconciliation case;
- audit event emitted.

## Expected Analytics Output

The commerce summary should show:
- total sales amount = 0 until invoice/sales mirror tables exist;
- total bot RFQ requests = at least 1;
- open reconciliation cases = at least 1;
- high severity reconciliation cases = at least 1;
- channel breakdown includes Telegram.

## Notes

This demo dataset is documentation-only in Stage 9. It is not wired into production startup and does not create ERP, payment, Telegram, or external connector activity.

## Implemented Fixture Location

Stage 9B includes test/dev fixtures under:

```text
apps/core-api/src/test/resources/demo/core-v1-demo/
```

Files:
- `tenant-demo.json`
- `customers-demo.json`
- `products-demo.json`
- `inventory-movements-demo.json`
- `telegram-rfq-demo.json`
- `telegram-unknown-demo.json`
- `reconciliation-demo.json`
- `import-validation-demo.json`

The fixture seed is used by automated tests only. There is no production auto-seeding and no public seed endpoint.
