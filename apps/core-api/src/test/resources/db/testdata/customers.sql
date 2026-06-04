INSERT INTO customer_segment (id, tenant_id, code, name)
VALUES
  ('77777777-7777-4777-8777-777777777777', '11111111-1111-4111-8111-111111111111', 'FLEET', 'Fleet'),
  ('88888888-8888-4888-8888-888888888888', '22222222-2222-4222-8222-222222222222', 'FLEET', 'Fleet');

INSERT INTO location (id, tenant_id, code, name, type, city, country)
VALUES
  ('99999999-9999-4999-8999-999999999999', '11111111-1111-4111-8111-111111111111', 'MAIN', 'Main Warehouse', 'WAREHOUSE', 'Almaty', 'KZ'),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '22222222-2222-4222-8222-222222222222', 'MAIN', 'Main Warehouse', 'WAREHOUSE', 'Astana', 'KZ');

INSERT INTO customer_account (id, tenant_id, account_code, legal_name, display_name, segment_id, status, default_currency, default_location_id)
VALUES
  ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', '11111111-1111-4111-8111-111111111111', 'CUST-A', 'Customer A LLP', 'Customer A', '77777777-7777-4777-8777-777777777777', 'ACTIVE', 'USD', '99999999-9999-4999-8999-999999999999'),
  ('cccccccc-cccc-4ccc-8ccc-cccccccccccc', '22222222-2222-4222-8222-222222222222', 'CUST-B', 'Customer B LLP', 'Customer B', '88888888-8888-4888-8888-888888888888', 'ACTIVE', 'USD', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
