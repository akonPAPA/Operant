INSERT INTO product (id, tenant_id, sku, normalized_sku, name, category, brand, manufacturer, base_uom, status, cost, currency)
VALUES
  ('10000000-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'BRK-001', 'BRK001', 'Brake Pad A', 'BRAKES', 'PilotParts', 'PilotParts', 'EA', 'ACTIVE', 15.0000, 'USD'),
  ('10000000-0000-4000-8000-000000000002', '11111111-1111-4111-8111-111111111111', 'FLT-001', 'FLT001', 'Fuel Filter A', 'FILTERS', 'PilotParts', 'PilotParts', 'EA', 'ACTIVE', 8.0000, 'USD'),
  ('10000000-0000-4000-8000-000000000003', '11111111-1111-4111-8111-111111111111', 'FLT-ALT', 'FLTALT', 'Fuel Filter Substitute', 'FILTERS', 'PilotParts', 'PilotParts', 'EA', 'ACTIVE', 9.0000, 'USD'),
  ('20000000-0000-4000-8000-000000000001', '22222222-2222-4222-8222-222222222222', 'BRK-001', 'BRK001', 'Brake Pad B', 'BRAKES', 'OtherParts', 'OtherParts', 'EA', 'ACTIVE', 16.0000, 'USD');

INSERT INTO product_substitute (id, tenant_id, source_product_id, substitute_product_id, substitute_type, risk_level, requires_approval, notes)
VALUES
  ('10000000-0000-4000-8000-000000000101', '11111111-1111-4111-8111-111111111111', '10000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000003', 'AFTERMARKET', 'MEDIUM', true, 'Postgres fixture substitute');
