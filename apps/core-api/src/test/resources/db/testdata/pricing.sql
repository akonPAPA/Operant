INSERT INTO price_rule (id, tenant_id, product_id, customer_account_id, location_id, min_quantity, uom, unit_price, currency, active_from, priority, active)
VALUES
  ('50000000-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', '10000000-0000-4000-8000-000000000001', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', '99999999-9999-4999-8999-999999999999', 1.0000, 'EA', 25.0000, 'USD', '2026-01-01T00:00:00Z', 10, true),
  ('50000000-0000-4000-8000-000000000002', '22222222-2222-4222-8222-222222222222', '20000000-0000-4000-8000-000000000001', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 1.0000, 'EA', 27.0000, 'USD', '2026-01-01T00:00:00Z', 10, true);

INSERT INTO discount_rule (id, tenant_id, code, name, customer_account_id, product_id, max_discount_percent, requires_approval_above_percent, active_from, active)
VALUES
  ('50000000-0000-4000-8000-000000000101', '11111111-1111-4111-8111-111111111111', 'DISC-A', 'Fixture Discount A', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', '10000000-0000-4000-8000-000000000001', 5.0000, 3.0000, '2026-01-01T00:00:00Z', true),
  ('50000000-0000-4000-8000-000000000102', '22222222-2222-4222-8222-222222222222', 'DISC-B', 'Fixture Discount B', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', '20000000-0000-4000-8000-000000000001', 5.0000, 3.0000, '2026-01-01T00:00:00Z', true);

INSERT INTO margin_rule (id, tenant_id, code, name, product_id, minimum_gross_margin_percent, approval_required_below_percent, active)
VALUES
  ('50000000-0000-4000-8000-000000000201', '11111111-1111-4111-8111-111111111111', 'MARGIN-A', 'Fixture Margin A', '10000000-0000-4000-8000-000000000001', 20.0000, 15.0000, true),
  ('50000000-0000-4000-8000-000000000202', '22222222-2222-4222-8222-222222222222', 'MARGIN-B', 'Fixture Margin B', '20000000-0000-4000-8000-000000000001', 20.0000, 15.0000, true);
