INSERT INTO product_alias (id, tenant_id, product_id, alias_type, raw_alias, normalized_alias, customer_account_id, confidence_default, active)
VALUES
  ('30000000-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', '10000000-0000-4000-8000-000000000001', 'CUSTOMER_SKU', 'brake 001', 'BRAKE001', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 0.9500, true),
  ('30000000-0000-4000-8000-000000000002', '22222222-2222-4222-8222-222222222222', '20000000-0000-4000-8000-000000000001', 'CUSTOMER_SKU', 'brake 001', 'BRAKE001', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', 0.9500, true);
