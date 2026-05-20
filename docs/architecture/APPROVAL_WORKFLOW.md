# Approval Workflow

Stage 6 stores internal `ApprovalDecision` records for:

- Stage 5 approval requirements;
- draft quotes;
- draft orders;
- quote/order lines;
- substitute candidates;
- exception cases.

Approval decisions are tenant-owned workflow records. They can update workflow status where supported, but they do not create external ChangeRequests and do not write ERP, 1C, accounting, warehouse, inventory, product, customer, or pricing systems.
