INSERT INTO channel_message (
  id, tenant_id, channel, external_message_id, conversation_id, sender_handle, sender_display_name,
  customer_account_id, direction, message_type, text_content, normalized_text, raw_payload,
  raw_payload_storage_key, status, received_at
)
VALUES
  (
    '60000000-0000-4000-8000-000000000001',
    '11111111-1111-4111-8111-111111111111',
    'TELEGRAM',
    'tg-a-1',
    'chat-a',
    '@buyer-a',
    'Buyer A',
    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    'INBOUND',
    'TEXT',
    'Need BRK-001 qty 2',
    'need brk 001 qty 2',
    '{"rawText":"DO_NOT_EXPOSE_RAW_TEXT","secret":"DO_NOT_EXPOSE_SECRET"}',
    'object-storage://tenant-a/raw-message',
    'RECEIVED',
    '2026-06-01T10:00:00Z'
  ),
  (
    '60000000-0000-4000-8000-000000000002',
    '22222222-2222-4222-8222-222222222222',
    'EMAIL',
    'email-b-1',
    'thread-b',
    'buyer-b@example.test',
    'Buyer B',
    'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
    'INBOUND',
    'TEXT',
    'Need BRK-001 qty 1',
    'need brk 001 qty 1',
    '{"rawText":"TENANT_B_DO_NOT_EXPOSE"}',
    'object-storage://tenant-b/raw-message',
    'RECEIVED',
    '2026-06-01T10:05:00Z'
  );

INSERT INTO inbound_document (
  id, tenant_id, source_channel, document_type, status, original_filename, content_type, file_size_bytes,
  object_storage_key, sha256_fingerprint, received_from, subject, raw_metadata, received_at
)
VALUES
  (
    '70000000-0000-4000-8000-000000000001',
    '11111111-1111-4111-8111-111111111111',
    'EMAIL',
    'RFQ',
    'RECEIVED',
    'rfq-a.pdf',
    'application/pdf',
    1234,
    'object-storage://tenant-a/raw-rfq',
    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
    'buyer-a@example.test',
    'RFQ A',
    '{"rawPayload":"DO_NOT_EXPOSE_DOCUMENT_PAYLOAD"}',
    '2026-06-01T11:00:00Z'
  );

INSERT INTO draft_quote (
  id, tenant_id, quote_number, customer_account_id, status, currency, subtotal_amount, total_amount,
  created_by, source_type, source_document_id, customer_display_name, validation_status,
  requires_human_review, audit_correlation_id, idempotency_key
)
VALUES
  (
    '80000000-0000-4000-8000-000000000001',
    '11111111-1111-4111-8111-111111111111',
    'Q-A-001',
    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    'DRAFT',
    'USD',
    50.0000,
    50.0000,
    '33333333-3333-4333-8333-333333333333',
    'INBOUND_DOCUMENT',
    '70000000-0000-4000-8000-000000000001',
    'Customer A',
    'READY',
    false,
    '81000000-0000-4000-8000-000000000001',
    'quote-a-linked'
  );

INSERT INTO draft_quote_line (
  id, tenant_id, draft_quote_id, product_id, line_number, description, quantity, uom,
  unit_price, line_total, status, validation_status, raw_text, raw_sku, normalized_sku,
  product_name, confidence_score, issue_codes
)
VALUES
  (
    '80000000-0000-4000-8000-000000000011',
    '11111111-1111-4111-8111-111111111111',
    '80000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000001',
    1,
    'Brake Pad A',
    2.0000,
    'EA',
    25.0000,
    50.0000,
    'OPEN',
    'VALID',
    'Need BRK-001 qty 2',
    'BRK-001',
    'BRK001',
    'Brake Pad A',
    0.9900,
    '[]'
  );

INSERT INTO quote_conversion_attempt (
  id, tenant_id, source_type, source_id, status, quote_id, failure_code, failure_message,
  validation_summary_json, created_at, triggered_by, triggered_by_type, idempotency_key, request_mode
)
VALUES
  (
    '90000000-0000-4000-8000-000000000001',
    '11111111-1111-4111-8111-111111111111',
    'CHANNEL_MESSAGE',
    '60000000-0000-4000-8000-000000000001',
    'NEEDS_REVIEW',
    NULL,
    'CUSTOMER_UNRESOLVED',
    'Customer requires operator review',
    '{"lineCount":1,"customerResolution":"UNRESOLVED","rawPayload":"DO_NOT_EXPOSE_SUMMARY_PAYLOAD","rawText":"DO_NOT_EXPOSE_SUMMARY_TEXT","objectStorageKey":"DO_NOT_EXPOSE_STORAGE_KEY","issues":[{"code":"CUSTOMER_UNRESOLVED","severity":"ERROR","blocking":true,"message":"Customer requires review","lineId":null}]}',
    '2026-06-01T10:10:00Z',
    '33333333-3333-4333-8333-333333333333',
    'USER',
    'attempt-a-review',
    'CREATE'
  ),
  (
    '90000000-0000-4000-8000-000000000002',
    '11111111-1111-4111-8111-111111111111',
    'INBOUND_DOCUMENT',
    '70000000-0000-4000-8000-000000000001',
    'READY_FOR_DRAFT_QUOTE',
    '80000000-0000-4000-8000-000000000001',
    NULL,
    NULL,
    '{"lineCount":1,"customerResolution":"RESOLVED","issues":[]}',
    '2026-06-01T11:10:00Z',
    '33333333-3333-4333-8333-333333333333',
    'USER',
    'attempt-a-linked',
    'CREATE'
  ),
  (
    '90000000-0000-4000-8000-000000000003',
    '22222222-2222-4222-8222-222222222222',
    'CHANNEL_MESSAGE',
    '60000000-0000-4000-8000-000000000002',
    'NEEDS_REVIEW',
    NULL,
    'NO_LINE_ITEMS',
    'No line items found',
    '{"lineCount":0,"customerResolution":"RESOLVED","rawPayload":"TENANT_B_DO_NOT_EXPOSE","issues":[{"code":"NO_LINE_ITEMS","severity":"ERROR","blocking":true,"message":"No line items found","lineId":null}]}',
    '2026-06-01T10:20:00Z',
    '44444444-4444-4444-8444-444444444444',
    'USER',
    'attempt-b-review',
    'CREATE'
  );
