# Channel Identity Contract

ChannelIdentity maps an external channel identity to internal customer context without creating business records.

## Persistent Concept

Table: `channel_identity`

Fields:

- `id`
- `tenant_id`
- `channel_type`
- `external_sender_id`
- `external_conversation_id`
- `sender_phone`
- `sender_display_name`
- `customer_account_id`
- `customer_contact_id`
- `identity_status`
- `match_confidence`
- `created_at`
- `updated_at`
- `linked_at`
- `linked_by_user_id`
- `notes`

Statuses:

- `UNLINKED`
- `SUGGESTED_MATCH`
- `LINKED`
- `BLOCKED`
- `NEEDS_REVIEW`

## Rules

- Identities are tenant-scoped.
- A tenant cannot create duplicate mappings for the same `channel_type` and `external_sender_id`.
- Identity linking does not create quotes, orders, ChangeRequests, or master-data mutations.
- Identity linking does not call external systems.
- Link, unlink, and block actions are audit-compatible.
- Unlinked inbound messages remain safe for review.
- Blocked identities prevent normal processing and keep future inbound messages in a review-safe status.
