# Workspace Safety Model

Stage 6 preserves the authority boundary:

- Internal `DraftQuote` and `DraftOrder` are not ERP records.
- Internal approval does not write ERP, 1C, accounting, warehouse, or external systems.
- Internal order approval does not reserve or decrement inventory.
- Workspace services do not mutate product, customer, inventory, or pricing master data.
- AI output cannot bypass Stage 5 deterministic validation.
- Operator actions are audit logged where implemented.
- Tenant-owned workspace tables include `tenant_id`.
- No customer email, Telegram, or WhatsApp business reply sending is implemented.

External writes remain a later-stage capability and must use a ChangeRequest plus connector approval model.
