# OrderPilot Enterprise Platform Layer — Mid-Market + Enterprise Upgrade

**Status:** additive architecture layer  
**Purpose:** strengthen OrderPilot beyond a narrow Core v1 demo and prepare the product for both mid-market companies and large enterprise/corporate customers.  
**Placement:** append this section near the end of the existing OrderPilot roadmap/blueprint, or merge it into the roadmap as the new enterprise-readiness layer.  
**Scope:** this document does not replace the current Core v1 roadmap. It upgrades the structure around enterprise governance, integration fabric, AI governance, performance, security, analytics, and industry extensibility.

---

## 1. Strategic correction

OrderPilot must not be limited to auto parts or industrial parts.

The better product structure is:

```text
OrderPilot = B2B Transaction Intelligence Platform
for companies that sell physical goods through messy requests, documents,
messengers, email, spreadsheets, ERP/CRM/WMS/accounting systems and manual workflows.
```

The first demo or initial vertical may use parts/industrial supplies because compatibility, substitutes, aliases and stock issues are strong there. However, the core product must remain generic enough for any SKU-heavy B2B seller/distributor.

Correct positioning:

```text
OrderPilot is a secure AI-assisted transaction intelligence platform
for B2B sellers, distributors, wholesalers and enterprise commerce teams.

It converts messy inbound requests into validated quotes, draft orders,
substitutions, approvals, audit trails, analytics and controlled integration actions.
```

What must remain generic:

- customer accounts;
- product catalog;
- product aliases;
- inventory;
- pricing;
- discounts;
- margin;
- validation;
- RFQ;
- quote;
- draft order;
- audit;
- intake;
- integration;
- analytics.

What can be vertical-specific:

- vehicle fitment;
- OEM references;
- compatibility rules;
- batch/expiry rules;
- hazard rules;
- warranty rules;
- unit conversion rules;
- industry-specific validation packs.

---

## 2. New platform structure

OrderPilot should be structured as a platform, not only as a list of features.

```text
OrderPilot Platform
├── 1. Transaction Core
├── 2. Channel & Document Intake
├── 3. Product / Catalog Intelligence
├── 4. Commercial Rules Engine
├── 5. Inventory & Availability Intelligence
├── 6. Quote / Order Workspace
├── 7. Exception & Approval Cockpit
├── 8. Integration Fabric
├── 9. Enterprise Control Plane
├── 10. AI Governance Layer
├── 11. Commerce Intelligence / Analytics
├── 12. Industry Packs
└── 13. Deployment & Operations Layer
```

This structure supports two product directions at the same time:

1. **Mid-market SaaS:** faster onboarding, standard workflows, Excel/API/Telegram/email/PDF intake, draft quote/order, basic analytics.
2. **Enterprise Platform:** SSO, SCIM, business units, advanced permissions, audit export, retention, connector governance, sandbox environments, AI governance, integration control and deployment options.

---

## 3. Layer 1 — Transaction Core

### Purpose

Own the trusted business workflow inside OrderPilot.

### Core entities

```text
RFQ
ValidationReview
ValidationIssue
Correction
ApprovalRequest
DraftQuote
QuoteVersion
QuoteLine
DraftOrder
OrderLine
CommercialDecision
SourceTrace
AuditEvent
OutboxEvent
```

### Required behavior

All business mutations must go through:

```text
Core API command
  -> authentication
  -> authorization
  -> tenant policy
  -> deterministic validation
  -> risk decision
  -> approval gate if required
  -> transaction
  -> audit event
  -> outbox event if needed
```

### Enterprise gap to close

OrderPilot must develop a mature quote/order lifecycle:

```text
validation review
  -> corrected fields
  -> draft quote/order
  -> line review
  -> price/margin check
  -> approval
  -> customer-facing output or ERP-ready draft
```

### Near-term stages

```text
OP-CAP-15A — Validation Review → Draft Quote / Draft Order
OP-CAP-15B — Draft Quote/Order Workspace UI
OP-CAP-15C — Line corrections, totals, margin and approval impact
OP-CAP-15D — Audit timeline and approval history
```

---

## 4. Layer 2 — Channel & Document Intake

### Purpose

Normalize messy inbound work into controlled, auditable events.

### Supported channels

```text
Email
PDF upload
Excel/XLSX upload
CSV upload
API upload
Telegram
WhatsApp-ready adapter
Portal
Future EDI/SFTP
```

### Core entities

```text
InboundEventLedger
InboundDocument
ChannelMessage
IngestionProfile
ChannelCredential
DeduplicationFingerprint
ProcessingJob
DeadLetterRecord
WebhookReplayToken
ObjectStoragePointer
```

### Required behavior

- inbound requests must be acknowledged quickly;
- heavy parsing must run asynchronously;
- raw source must be preserved;
- duplicate messages/uploads must be idempotent;
- failed processing must be visible;
- file uploads must be validated, size-limited and quarantined before parsing;
- customer-provided content must be treated as hostile input.

### Enterprise gap to close

Mature ingestion needs:

```text
retry policy
dead-letter queue
processing diagnostics
source-specific credentials
mapping profile
replay protection
connector health
support/debug trace
```

---

## 5. Layer 3 — Product / Catalog Intelligence

### Purpose

Make OrderPilot useful for SKU-heavy businesses without hardcoding one industry.

### Generic catalog model

```text
Product
ProductVariant
ProductAlias
ProductCategory
ProductAttribute
ProductRelation
ProductSubstitute
ProductBundle
ProductCompatibility
ProductDocument
```

### Product relation examples

```text
exact substitute
compatible substitute
recommended alternative
same family
bundle component
accessory
replacement part
customer-specific alias
supplier-specific alias
old SKU
```

### Design rule

Parts-specific concepts must not leak into generic core.

Correct:

```text
ProductRelation
ProductCompatibility
ProductSubstitute
ProductAttribute
```

Industry extension:

```text
AutoPartsPack:
  OEMReference
  VehicleMake
  VehicleModel
  VehicleYear
  FitmentRule
```

Other possible packs:

```text
ElectronicsPack:
  WarrantyProfile
  SpecCompatibility
  AccessoryRelation

ConstructionMaterialsPack:
  UnitConversionRule
  WeightVolumeRule
  DeliveryConstraint

FoodWholesalePack:
  Batch
  ExpiryDate
  TemperatureClass

MedicalSuppliesPack:
  LotNumber
  ComplianceClass
  SterileFlag

ChemicalsPack:
  HazardClass
  StorageRule
  RestrictedShippingRule
```

---

## 6. Layer 4 — Commercial Rules Engine

### Purpose

Protect revenue, discount discipline and margin.

### Core entities

```text
PriceBook
PriceRule
CustomerPriceRule
SegmentPriceRule
QuantityBreakRule
DiscountPolicy
MarginGuardrail
ApprovalThreshold
CommercialRiskDecision
PriceSimulation
ProfitabilityImpact
```

### Required behavior

The system must answer:

1. What is the correct price for this customer/product/quantity/date?
2. Is the requested discount allowed?
3. Does this quote violate margin rules?
4. Who can approve this exception?
5. What is the profitability impact?
6. Which price source was used?

### Enterprise gap to close

Mature competitors are stronger in pricing/margin optimization. OrderPilot should not try to beat a pricing suite immediately, but it must provide enough commercial control for quote/order automation.

Near-term priority:

```text
Commercial Rules Engine v2:
  customer-specific price
  segment price
  quantity break
  discount guardrail
  margin guardrail
  approval threshold
  visible price source explanation
```

---

## 7. Layer 5 — Inventory & Availability Intelligence

### Purpose

Prevent fake availability and detect stock problems.

### Core entities

```text
InventorySnapshot
InventoryMovement
Reservation
AvailabilityCheck
StockFreshnessPolicy
LowStockSignal
OutOfStockSignal
ReconciliationCase
DiscrepancyReason
```

### Required behavior

- availability must use latest trusted inventory snapshot;
- stale inventory must create warning;
- out-of-stock product must route to substitute logic;
- expected vs actual stock mismatch must create discrepancy case;
- dashboards must show stock risk, low stock and mismatch counts.

### Expected stock foundation

```text
Expected Stock =
Opening Stock
+ Purchases Received
+ Returns In
- Sales
- Returns Out
- Write-offs
- Transfers Out
+ Transfers In
± Manual Adjustments
```

### Enterprise gap to close

OrderPilot needs stronger inventory confidence:

```text
stock freshness
reservation awareness
source timestamp
warehouse/location scope
batch/incremental reconciliation
discrepancy severity
```

---

## 8. Layer 6 — Quote / Order Workspace

### Purpose

Turn validation into real operator work.

### Required screens

```text
Validation Review Detail
Draft Quote Detail
Draft Order Detail
Line Items Panel
Corrections Panel
Validation Issues Panel
Price/Margin Panel
Substitution Panel
Approval Panel
Audit Timeline
```

### Required behavior

- operator can create draft quote/order from validated review;
- operator can correct SKU/UOM/quantity;
- operator can approve/reject substitute;
- operator can see price and margin impact;
- operator can request or perform approval based on permission;
- every change is audited;
- unresolved blocking issues prevent draft/final actions.

### Enterprise gap to close

Without this layer, OrderPilot remains a validation tool, not a transaction workflow platform.

---

## 9. Layer 7 — Exception & Approval Cockpit

### Purpose

Give operations teams a controlled place to handle risk.

### Exception types

```text
low confidence extraction
unmatched SKU
unknown customer
invalid UOM
out-of-stock
risky substitute
margin violation
discount violation
stale inventory
duplicate request
connector failure
external write approval
prompt injection suspicion
```

### Core entities

```text
ExceptionQueue
ExceptionCase
ExceptionAssignment
SLAClock
EscalationPolicy
ApprovalPolicy
ApprovalDelegation
DecisionReason
ResolutionNote
```

### Required behavior

- exceptions must be assigned, filtered and resolved;
- high-risk actions cannot bypass approval;
- every decision must have reason and audit trail;
- managers must see SLA and bottleneck metrics.

### Enterprise gap to close

Large companies need approval routing, delegation, SLA clocks and audit-ready decision history.

---

## 10. Layer 8 — Integration Fabric

### Purpose

Connect to real systems safely.

### Core entities

```text
IntegrationConnection
ConnectorCredential
ConnectorScope
ConnectorSyncRun
MappingProfile
ExternalEntityReference
ChangeRequest
ConnectorCommand
ConnectorDeadLetter
RetryPolicy
CircuitBreakerState
```

### Supported integration modes

```text
Excel/CSV import
SFTP import/export
REST API
Webhooks
Read-only database connector
1C/OData/HTTP connector
ERP adapter
Windows Local Connector Agent
Demo ERP adapter
```

### Required behavior

- connectors are read-only by default;
- external writes require ChangeRequest and approval;
- every sync run is visible;
- mapping profiles show field-level mapping;
- failed connector actions go to diagnostics/dead-letter state;
- connector credentials are scoped per tenant;
- connector failures must not corrupt core workflow.

### Enterprise gap to close

Mature enterprise customers require connector governance, mapping, diagnostics and safe external write controls.

---

## 11. Layer 9 — Enterprise Control Plane

### Purpose

Make OrderPilot acceptable for large corporations.

### Core entities

```text
Organization
Tenant
BusinessUnit
Region
Branch
Department
User
Group
Role
Permission
Policy
FeatureFlag
Environment
AuditExport
DataRetentionPolicy
SSOConfiguration
SCIMConfiguration
APIKey
ServiceAccount
```

### Required capabilities

```text
SSO/OIDC/SAML-ready authentication
SCIM-ready user provisioning
MFA policy
IP allowlist
custom RBAC/ABAC
business-unit/branch/department scoping
audit export
data retention policy
legal hold later
sandbox/staging/prod separation
feature flags per tenant
service accounts and scoped API keys
dedicated tenant option later
```

### Enterprise gap to close

This is one of the biggest technical gaps. Large companies will not buy without governance, identity, access control, audit export and admin controls.

---

## 12. Layer 10 — AI Governance Layer

### Purpose

Make AI measurable, controllable and safe.

### Core entities

```text
AIProvider
ModelPolicy
PromptVersion
ExtractionSchemaVersion
ModelRun
ModelRunInputRef
ModelRunOutputRef
EvaluationDataset
EvaluationCase
CorrectionFeedback
TokenCostLedger
PIIRedactionPolicy
PromptInjectionSignal
AIQualityMetric
```

### Required behavior

- every AI run must record model, provider, prompt version and schema version;
- AI output must remain suggestion until deterministic validation;
- prompt injection attempts must not override system behavior;
- human corrections must be stored as feedback;
- cost per document/message/tenant/model must be measurable;
- tenant-level AI settings must be possible later;
- open-source/local models must be optional providers, not the core business logic.

### Enterprise gap to close

AI must be governed like infrastructure:

```text
quality
cost
privacy
model version
prompt version
correction rate
failure rate
fallback behavior
```

---

## 13. Layer 11 — Commerce Intelligence / Analytics

### Purpose

Prove business value in money, time, margin and risk.

### KPIs

```text
AutomationRate
TouchlessRate
ExceptionRate
AverageCycleTime
QuoteTurnaroundTime
OrderCycleTime
HumanCorrectionRate
MarginSaved
DiscountLeakage
SubstituteRecoveredRevenue
StockDiscrepancyValue
ChannelVolume
CustomerDemandTrend
TopExceptionReasons
ConnectorFailureRate
```

### Required dashboards

```text
Command Center
Sales Analytics
Margin Analytics
Discount Leakage
Inventory Analytics
Reconciliation Cases
Channel Analytics
Customer Analytics
Operator Productivity
ROI Dashboard
```

### Enterprise gap to close

Dashboards must be based on read models/materialized views, not heavy live joins. Analytics must prove ROI, not only display charts.

---

## 14. Layer 12 — Industry Packs

### Purpose

Keep the core generic while allowing deep vertical intelligence.

### Pack structure

Each industry pack may define:

```text
industry-specific product attributes
industry-specific compatibility rules
industry-specific validation rules
industry-specific import templates
industry-specific demo dataset
industry-specific UI labels
industry-specific analytics metrics
```

### Initial packs

```text
Generic Wholesale Pack
Auto/Industrial Parts Pack
Electronics Pack
Construction Materials Pack
Medical Supplies Pack
Food Wholesale Pack
Industrial Equipment Pack
```

### Design rule

Do not hardcode any pack into the generic transaction core.

---

## 15. Layer 13 — Deployment & Operations Layer

### Purpose

Support mid-market SaaS and enterprise deployment needs.

### Deployment modes

```text
Standard SaaS
Dedicated tenant
Private cloud later
Hybrid with Windows Local Connector Agent
On-prem connector with outbound-only sync
Sandbox environment
Staging environment
Production environment
```

### Operations capabilities

```text
health checks
structured logs
metrics
tracing
connector diagnostics
queue depth monitoring
AI cost monitoring
audit export
backup/restore test
incident response runbook
security review package
```

### Enterprise gap to close

Enterprise sales requires operational maturity, not only product features.

---

## 16. Product editions

### Mid-Market Edition

```text
SaaS deployment
single tenant/company account
standard roles
Excel/CSV import
email/PDF/API/Telegram intake
validation review
draft quote/order
basic approvals
basic audit
basic analytics
standard integrations
```

### Enterprise Edition

```text
organization hierarchy
business units / regions / branches
SSO/OIDC/SAML
SCIM provisioning
advanced RBAC/ABAC
approval policies
audit export
data retention policies
environment separation
connector governance
advanced mapping profiles
AI governance
ROI dashboards
dedicated tenant option
security/procurement package
```

### Hybrid Connector Edition

```text
Windows Local Connector Agent
read-only first
outbound-only connection
local Excel/CSV/DB/1C access
encrypted local credentials
watched folders
sync diagnostics
connector health monitoring
approved external write commands only
```

---

## 17. Updated technical priority map

### P0 — must close first

```text
1. Validation Review → Draft Quote / Draft Order
2. Draft Quote/Order Workspace UI
3. Enterprise Control Plane foundation
4. Integration Fabric foundation
5. AI Governance / evaluation foundation
6. Operator Exception Cockpit maturity
7. UI polish for investor/customer trust
```

### P1 — next

```text
1. Commercial Rules Engine v2
2. Connector Mapping + Sync Run Diagnostics
3. ROI / Commerce Intelligence read models
4. Security review package
5. Prompt injection and AI-output safety tests
6. Audit export
7. SSO/OIDC-ready admin model
```

### P2 — later

```text
1. SCIM provisioning
2. Dedicated tenant / private cloud option
3. Advanced ERP connectors
4. Advanced pricing simulation
5. Advanced industry packs
6. SOC 2 / ISO preparation
7. Local/open-source model deployment controls
```

---

## 18. Updated roadmap after current validation stages

```text
OP-CAP-15A — Validation Review → Draft Quote / Draft Order
OP-CAP-15B — Draft Quote/Order Workspace UI
OP-CAP-15C — Commercial Rules Engine v2
OP-CAP-15D — Exception Cockpit v2

OP-CAP-16A — Integration Fabric Foundation
OP-CAP-16B — Connector Mapping + Sync Run Diagnostics
OP-CAP-16C — ChangeRequest External Write Control

OP-CAP-17A — Enterprise Control Plane Foundation
OP-CAP-17B — SSO/OIDC/SCIM-ready Admin Layer
OP-CAP-17C — Audit Export + Data Retention Policies

OP-CAP-18A — AI Governance + Evaluation Harness
OP-CAP-18B — AI Cost/Quality Dashboard
OP-CAP-18C — Local/Open-Source Model Provider Governance

OP-CAP-19A — Commerce Intelligence Read Models
OP-CAP-19B — ROI / Executive Dashboard
OP-CAP-19C — Reconciliation and Margin Leakage Analytics

OP-CAP-20A — Security Review Pack + Pentest Readiness
OP-CAP-20B — Threat Model and Procurement Readiness
OP-CAP-20C — Enterprise Demo Dataset and Demo Script
```

---

## 19. Target package/module structure

This is the target structure. Do not create empty folders without a real slice.

```text
apps/core-api/src/main/java/com/orderpilot/

  common/
    tenant/
    auth/
    security/
    audit/
    idempotency/
    policy/
    errors/

  platform/
    organization/
    controlplane/
    featureflags/
    environments/
    compliance/

  commerce/
    customer/
    product/
    pricing/
    inventory/
    quote/
    order/
    validation/
    approval/
    reconciliation/

  intake/
    channels/
    documents/
    messages/
    uploads/
    processingjobs/

  intelligence/
    extraction/
    confidence/
    airesults/
    modelruns/
    evaluation/

  integrations/
    connections/
    credentials/
    mappings/
    changerequests/
    syncruns/
    connectorcommands/

  analytics/
    readmodels/
    kpis/
    reports/
    exports/

  verticals/
    generic/
    parts/
    electronics/
    construction/
    food/
    medical/
```

---

## 20. Non-negotiable rules for future implementation

1. Keep generic commerce core separate from industry packs.
2. Do not hardcode auto/parts assumptions into generic quote/order/product modules.
3. AI must remain advisory.
4. All business mutations must go through Core API command services.
5. External writes must go through ChangeRequest + approval + connector command.
6. Every tenant-owned business row must be tenant-scoped.
7. Every important mutation must be audited.
8. Dashboard analytics must use read models when data grows.
9. Enterprise features must be additive, not destructive to mid-market simplicity.
10. Do not create architecture-only empty layers without a working business slice.

---

## 21. Summary

OrderPilot should evolve from a strong Core v1 into a platform with two selling motions:

```text
Mid-market:
  fast SaaS, practical automation, simple onboarding, quick ROI

Enterprise:
  governance, integrations, security, audit, AI control, deployment options
```

The current validation/risk/audit foundation is valuable, but the next structural upgrade must close:

```text
quote/order workspace
integration fabric
enterprise control plane
AI governance
commercial rules depth
analytics/ROI
security/procurement readiness
```

This additive layer should guide future prompts and roadmap updates without breaking the current implementation path.
P.S(Akan):there is some fixes added but not all of them,I still thinking which ones should to add
