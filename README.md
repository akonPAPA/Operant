# Operant Core

Operant is intelligent o2c/e2e/intelligent e-commercesoftware

## License and proprietary notice

Operant is proprietary and confidential software.

Copyright (c) 2026 Operant / Akan Mukhametgali. All rights reserved.

No public open-source license is granted for this repository unless a specific file,
package, or directory explicitly states otherwise. See LICENSE, NOTICE,
THIRD_PARTY_NOTICES.md, and docs/legal/ for details.

Status note:currently building ST1 stage

This repository is intentionally scoped to platform foundation only:

- Java 21 Spring Boot core API
- Next.js TypeScript dashboard shell
- Python 3.12 AI/OCR worker skeleton
- PostgreSQL, Redis, Flyway migrations, Docker Compose
- Security and architecture documentation

AI, frontend, chatbot, and connector components must never directly write trusted business data. Future mutations must go through typed core-api command services, authentication, authorization, tenant policy, deterministic validation, approval gates, transactions, audit events, and outbox events.

# The Architecture
### The Full Architecture was so big to implement for one person in 2 months so I just paused full project because of my assessments at University and preparing for certificates which I wanted to pass
### P.S: I passed sc-200 and still preparing for OSAI certificate. Right now Im leearning about AI Red Teaming and I think I will improve and end this project as soon as possible after work and study hours.

```mermaid
flowchart TB

  %% ═══════════════════ 0. CANONICAL ARCHITECTURE LAW ═══════════════════
  subgraph SEC0["0. Canonical architecture law"]
    direction TB
    s0P1["P1 human / tenant-admin plane"]
    s0P2["P2 plane"]
    s0P3["P3 plane"]
    s0P4["P4 plane"]
    s0POLICY["POLICY enforcement"]
    s0OWNER["Authoritative module command or query"]
    s0CLIENT["Caller may send business intent, data, expected version, idempotency key"]
    s0BACKEND["Backend resolves plane, tenant, actor, permission, ownership, state, policy and calculated facts"]
    s0DENY["Unauthorized / wrong-plane / wrong-tenant / stale or malformed"]
    s0NOFX["No business mutation<br/>No success audit<br/>No outbox provider connector effect"]
    s0SDA["Optional bounded security-denial audit"]
    s0P3 --> s0POLICY
    s0P4 --> s0POLICY
    s0POLICY --> s0OWNER
    s0CLIENT --> s0POLICY
    s0BACKEND --> s0OWNER
    s0DENY --> s0NOFX
    s0DENY --> s0SDA
    s0P1 -. "never inherits" .-> s0P4
    s0P2 -. "never inherits" .-> s0P1
    s0P3 -. "never inherits human approval" .-> s0P1
    s0P4 -. "never reuses tenant-admin authority" .-> s0P1
  end

  %% ═══════════════════ 3. PRODUCTION NETWORK & DEPLOYABLE TOPOLOGY ═══════════════════
  subgraph SEC3["3. Production network and deployable topology"]
    direction TB
    s3INTERNET["Internet"] --> s3EDGE["Public edge: CDN, WAF, reverse proxy"]
    subgraph S3PUB["Public ingress network"]
      s3WEB["web-dashboard and BFF"]
      s3BUYER["customer edge"]
      s3SITEPUB["experience-public"]
      s3HOOKS["explicit provider webhook endpoints"]
    end
    subgraph S3APP["Private application network"]
      s3COREAPI["core-api"]
      s3EXPCONTROL["experience-control"]
      s3CGW["connector-gateway"]
      s3CONTROLAPI["control-api"]
      s3AIWORKER["ai-worker"]
      s3DOCWORKER["document worker"]
      s3EFFECTWORKER["connector / notification / projection workers"]
    end
    subgraph S3DATA["Private data network"]
      s3COREPG[("Core PostgreSQL")]
      s3EXPPG[("M19 PostgreSQL")]
      s3REDIS2[("Redis")]
      s3OBJECT[("S3-compatible storage")]
      s3MQ[("Optional broker")]
    end
    s3EDGE --> s3WEB
    s3EDGE --> s3BUYER
    s3EDGE --> s3SITEPUB
    s3EDGE --> s3HOOKS
    s3WEB --> s3COREAPI
    s3BUYER --> s3COREAPI
    s3WEB --> s3EXPCONTROL
    s3SITEPUB --> s3EXPCONTROL
    s3HOOKS --> s3COREAPI
    s3COREAPI --> s3COREPG
    s3COREAPI --> s3REDIS2
    s3COREAPI --> s3OBJECT
    s3COREAPI --> s3MQ
    s3EXPCONTROL --> s3EXPPG
    s3EXPCONTROL --> s3OBJECT
    s3AIWORKER --> s3COREAPI
    s3DOCWORKER --> s3COREAPI
    s3EFFECTWORKER --> s3COREAPI
    s3CGW --> s3COREAPI
    s3CONTROLAPI --> s3COREAPI
    s3DBLOCK["PostgreSQL, Redis, object admin and Core are not publicly exposed"] -. "invariant" .-> S3DATA
    s3ROOTLESS["Managed Linux, hardened Compose or rootless containers and systemd lifecycle"] -. "deployment" .-> S3APP
  end

  %% ═══════════════════ 4. M01–M24 AUTHORITATIVE OWNERSHIP MAP (canonical nodes) ═══════════════════
  subgraph SEC4["4. M01–M24 authoritative ownership map"]
    direction TB
    subgraph T0["T0 platform envelope"]
      M23["M23 auditoutbox<br/>audit, durable event and effect envelope"]
    end
    subgraph T1["T1 identity and tenant foundation"]
      M01["M01 identityaccess<br/>principals, memberships, permissions, service identities, support grants"]
      M02["M02 tenancyconfiguration<br/>tenant, workspace, legal entity, typed config, localization"]
    end
    subgraph T2["T2 facts, evidence, validation, commercial"]
      M03["M03 party<br/>customer, contact, address, projections, merge evidence"]
      M04["M04 catalog<br/>product, identifiers, fitment, compatibility, UOM"]
      M05["M05 inventory<br/>stock, observations, reservation, allocation intents"]
      M06["M06 inquirydocument<br/>inquiry, RFQ, evidence, document lifecycle, lineage"]
      M08["M08 commercial<br/>price, discount, margin, tax, credit, policy snapshots"]
      M07["M07 validationwork<br/>validation, approval, requirements, tasks, exceptions, corrections"]
    end
    subgraph T3["T3 transactional process owners"]
      M09["M09 quote<br/>quote versions, approvals, buyer decision, customer artifact"]
      M10["M10 order<br/>sales order, conversion, confirmation, change lifecycle"]
      M11["M11 fulfillment<br/>allocation, shipment, delivery, projections, exceptions"]
      M12["M12 finance<br/>AR, AP, payment, allocation, claims, collections"]
      M14["M14 suppliercommerce<br/>sourcing, bid, award, contract, ref requisition, PO"]
      M13["M13 reconciliation<br/>drift, conflict, cases, resolutions, checkpoints"]
      M20["M20 subscriptionentitlement<br/>plan, usage, subscription, tenant billing projection"]
    end
    subgraph T4["T4 edge, integration, AI, projection, operations"]
      M15["M15 integration<br/>connectors, credential refs, ChangeRequest, runs, checkpoints"]
      M16["M16 channel<br/>connections, sender mapping, conversations, reply intent"]
      M17["M17 notification<br/>NotificationIntent, preferences, delivery state"]
      M18["M18 aibot<br/>AI jobs, provenance, evaluation, memory, policy, bot definitions"]
      M19["M19 buyercommerce and Sites<br/>experience, publication, forms, buyer-safe sessions"]
      M21["M21 projection<br/>search, analytics, operational read models"]
      M22["M22 developerexperience<br/>APIs, webhooks, SDK, extension certification lifecycle"]
      M24["M24 operationssupport<br/>lifecycle, incidents, repair, backup, control plane"]
    end
    M23 --> M01 --> M02
    M02 --> M03
    M02 --> M04
    M04 --> M05
    M03 --> M06
    M04 --> M06
    M05 --> M08
    M06 --> M07
    M08 --> M07
    M07 --> M09
    M08 --> M09
    M09 --> M10
    M10 --> M11
    M10 --> M12
    M11 --> M12
    M07 --> M14
    M08 --> M14
    M12 --> M14
    M11 --> M13
    M12 --> M13
    M14 --> M13
    M23 --> M15
    M23 --> M16
    M23 --> M17
    M23 --> M18
    M23 --> M21
    M23 --> M22
    M23 --> M24
    M20 --> M19
    M03 --> M19
    M04 --> M19
    M05 --> M19
    M09 --> M19
    M10 --> M19
    s4NOTE1["Process arrows do not grant table ownership"] -.-> M09
    s4NOTE2["Core never synchronously depends on edge modules M15–M24"] -.-> T4
  end

  %% ═══════════════════ 5. CANONICAL DEPENDENCY DIRECTION & CYCLE PREVENTION ═══════════════════
  subgraph SEC5["5. Canonical dependency direction and cycle prevention"]
    direction LR
    M23 --> M01
    M01 --> M02
    M02 --> s5BASE["M03, M04, M20, M17"]
    s5BASE --> s5MID["M05, M06"]
    s5MID --> M08
    M08 --> M07
    M07 --> M09
    M09 --> M10
    M10 --> M11
    M11 --> M12
    M12 --> M14
    M14 --> M13
    M13 --> s5EDGE2["M15, M18"]
    s5EDGE2 --> s5EDGE3["M16, M19"]
    s5EDGE3 --> M21
    M21 --> M22
    M22 --> M24
    s5EXT["External system"] --> s5OBS["M15 verifies, normalizes observation"]
    s5OBS --> s5OWNERAPI["Authoritative owner public API"]
    s5OWNERAPI --> s5OWNERDB[("Owner tables")]
    s5INTENT["Domain owner commits typed external intent"] --> s5ENV["M23 durable envelope"]
    s5ENV --> s5EXEC["M15 executes after commit"]
    s5FORBID1["M03–M14 never query M15 for business truth"] -. "forbidden" .-> s5OBS
    s5FORBID2["No cross-module entity repository or internal service imports"] -. "enforced by Modulith and ArchUnit" .-> s5OWNERDB
    s5OBS -. "authority" .-> M15
    s5ENV -. "authority" .-> M23
    s5EXEC -. "authority" .-> M15
  end

  %% ═══════════════════ 6. STATE-1 ACTIVATION POSTURE ═══════════════════
  subgraph SEC6["6. State-1 activation posture"]
    direction TB
    s6ACTIVE["ACTIVE_NOW"] --> s6ASET["M01 M02 M03 M04 M05 M06 M07 M08 M09 M15 M18 M20 M23 M24"]
    s6MIN["MINIMAL_CONTRACT_ONLY"] --> s6MSET["M10 M12 M13 M16 M19 M21"]
    s6FUTURE["FUTURE_OWNERSHIP_BOUNDARY"] --> s6FSET["M11 M17 M22"]
    s6NONE["NOT_INSTANTIATED"] --> s6NSET["M14"]
    s6LIMIT1["Future or minimal posture does not justify empty packages, tables, endpoints or stubs"] --> s6FUTURE
    s6LIMIT1 --> s6NONE
    s6LIMIT2["M19 separate service starts only through P8/P9 activation gates"] --> s6MIN
  end

  %% ═══════════════════ 7. O2C END-TO-END SPINE ═══════════════════
  subgraph SEC7["7. O2C end-to-end spine"]
    direction LR
    s7SRC["Email, messenger, document, marketplace, portal, API"] --> s7INTAKE["Verified bounded intake"]
    s7INTAKE --> s7RFQ["M06 Inquiry and RFQ"]
    s7RFQ --> s7EXTRACT["Document extraction and AI candidates"]
    s7EXTRACT --> s7VALIDATE["M07 deterministic validation and work"]
    s7VALIDATE --> s7COMM["M08 price, discount, margin, tax, credit snapshot"]
    s7COMM --> s7QUOTE["M09 versioned quote"]
    s7QUOTE --> s7APPROVAL{"Approval required"}
    s7APPROVAL -- "yes" --> s7HUMAN["Authorized human approval, exact version"]
    s7APPROVAL -- "no" --> s7ARTIFACT["Customer-safe immutable quote artifact"]
    s7HUMAN --> s7ARTIFACT
    s7ARTIFACT --> s7DECISION["M09 buyer accept / reject / comment"]
    s7DECISION --> s7ORDER["M10 bounded sales order"]
    s7ORDER --> s7FULFILL["M11 allocation, shipment, delivery"]
    s7FULFILL --> s7AR["M12 invoice and AR obligation"]
    s7AR --> s7PAYMENT["M12 verified payment observation and allocation"]
    s7PAYMENT --> s7RECON["M13 reconciliation and closure"]
    s7ORDER -. "external posting is separate" .-> s7CR["M15 ChangeRequest"]
    s7CR --> s7ERPFX["ERP execution and read-back"]
    s7ERPFX --> s7RECON
    s7RFQ -. "owner" .-> M06
    s7VALIDATE -. "owner" .-> M07
    s7COMM -. "owner" .-> M08
    s7QUOTE -. "owner" .-> M09
    s7ORDER -. "owner" .-> M10
    s7FULFILL -. "owner" .-> M11
    s7AR -. "owner" .-> M12
    s7PAYMENT -. "owner" .-> M12
    s7RECON -. "owner" .-> M13
    s7CR -. "owner" .-> M15
  end

  %% ═══════════════════ 8. INTAKE / DOCUMENT / AI / QUOTE SEQUENCE ═══════════════════
  subgraph SEC8["8. Intake, document, AI and quote sequence"]
    direction TB
    s8CALLER["Tenant user or verified channel"]
    s8EDGE["BFF or verified ingress"]
    s8STORE[("Object storage")]
    s8DOC["Sandboxed document worker"]
    s8FACTS["M03 M04 M05 M08 facts"]
    s8DB[("PostgreSQL + M23 outbox")]
    s8DELIVERY["M16 or M17 delivery worker"]
    s8NOTE["AI never approves or writes trusted quote state"]
    s8CALLER -->|"1 bounded message / document / RFQ intent"| s8EDGE
    s8EDGE -->|"2 trusted tenant actor, source, idempotency context"| M06
    M06 -->|"3 immutable evidence bytes and digest"| s8STORE
    M06 -->|"4 commit inquiry, evidence, audit and processing intent"| s8DB
    s8DB -.->|"5 post-commit document task"| s8DOC
    s8DOC -.->|"6 typed extraction candidates and provenance"| M06
    M06 -->|"7 minimized tenant-scoped advisory job"| M18
    M18 -.->|"8 typed suggestions, confidence and evidence refs"| M07
    M07 -->|"9 resolve customer, product, UOM, stock freshness, commercial policy"| s8FACTS
    s8FACTS -.->|"10 authoritative snapshots"| M07
    M07 -->|"11 validated quote assembly command"| M09
    M09 -->|"12 commit quote version, approval requirements, audit and delivery intent"| s8DB
    s8DB -.->|"13 post-commit delivery task"| s8DELIVERY
    s8DELIVERY -.->|"14 normalized delivery observation"| M09
    s8NOTE -. "invariant" .-> M18
    s8FACTS -. "owners" .-> M03 & M04 & M05 & M08
    s8DELIVERY -. "owners" .-> M16 & M17
  end

  %% ═══════════════════ 9. STATE-MACHINE FAMILIES ═══════════════════
  subgraph SEC9["9. Canonical state-machine families"]
    direction TB

    subgraph S91["9.1 Inquiry and quote"]
      direction TB
      s91_START1(("•")) --> s91_RFQ_RECEIVED["RFQ_RECEIVED"]
      s91_RFQ_RECEIVED --> s91_RFQ_PROCESSING["RFQ_PROCESSING"]
      s91_RFQ_PROCESSING --> s91_RFQ_NEEDS_REVIEW["RFQ_NEEDS_REVIEW"]
      s91_RFQ_PROCESSING --> s91_RFQ_VALIDATED["RFQ_VALIDATED"]
      s91_RFQ_NEEDS_REVIEW -->|"corrected and revalidated"| s91_RFQ_PROCESSING
      s91_RFQ_VALIDATED --> s91_RFQ_CONVERTED["RFQ_CONVERTED"]
      s91_RFQ_PROCESSING --> s91_RFQ_REJECTED["RFQ_REJECTED"]
      s91_RFQ_RECEIVED --> s91_RFQ_CANCELLED["RFQ_CANCELLED"]
      s91_START2(("•")) --> s91_QUOTE_DRAFT["QUOTE_DRAFT"]
      s91_QUOTE_DRAFT --> s91_QUOTE_NEEDS_REVIEW["QUOTE_NEEDS_REVIEW"]
      s91_QUOTE_DRAFT --> s91_QUOTE_APPROVED_INTERNAL["QUOTE_APPROVED_INTERNAL"]
      s91_QUOTE_NEEDS_REVIEW -->|"exact version approval"| s91_QUOTE_APPROVED_INTERNAL
      s91_QUOTE_APPROVED_INTERNAL --> s91_QUOTE_READY["QUOTE_READY_FOR_CUSTOMER"]
      s91_QUOTE_READY --> s91_QUOTE_EXPORTED["QUOTE_EXPORTED"]
      s91_QUOTE_EXPORTED --> s91_QUOTE_ACCEPTED["QUOTE_ACCEPTED"]
      s91_QUOTE_EXPORTED --> s91_QUOTE_REJECTED["QUOTE_REJECTED"]
      s91_QUOTE_EXPORTED --> s91_QUOTE_EXPIRED["QUOTE_EXPIRED"]
      s91_QUOTE_DRAFT -->|"new immutable version"| s91_QUOTE_SUPERSEDED["QUOTE_SUPERSEDED"]
    end

    subgraph S92["9.2 Order, fulfillment and receivable"]
      direction TB
      s92_START1(("•")) --> s92_ORDER_DRAFT["ORDER_DRAFT"]
      s92_ORDER_DRAFT --> s92_ORDER_VALIDATED["ORDER_VALIDATED"]
      s92_ORDER_VALIDATED --> s92_ORDER_APPROVAL_REQUIRED["ORDER_APPROVAL_REQUIRED"]
      s92_ORDER_VALIDATED --> s92_ORDER_APPROVED["ORDER_APPROVED"]
      s92_ORDER_APPROVAL_REQUIRED --> s92_ORDER_APPROVED
      s92_ORDER_APPROVED --> s92_ORDER_CONFIRMED["ORDER_CONFIRMED"]
      s92_ORDER_CONFIRMED --> s92_ORDER_IN_FULFILLMENT["ORDER_IN_FULFILLMENT"]
      s92_ORDER_IN_FULFILLMENT --> s92_ORDER_COMPLETED["ORDER_COMPLETED"]
      s92_ORDER_DRAFT --> s92_ORDER_CANCELLED["ORDER_CANCELLED"]
      s92_ORDER_VALIDATED --> s92_ORDER_CANCELLED
      s92_START2(("•")) --> s92_FULFILLMENT_PLANNED["FULFILLMENT_PLANNED"]
      s92_FULFILLMENT_PLANNED --> s92_FULFILLMENT_ALLOCATING["FULFILLMENT_ALLOCATING"]
      s92_FULFILLMENT_ALLOCATING --> s92_FULFILLMENT_PARTIAL["FULFILLMENT_PARTIAL"]
      s92_FULFILLMENT_ALLOCATING --> s92_FULFILLMENT_ALLOCATED["FULFILLMENT_ALLOCATED"]
      s92_FULFILLMENT_PARTIAL --> s92_FULFILLMENT_ALLOCATED
      s92_FULFILLMENT_ALLOCATED --> s92_FULFILLMENT_IN_TRANSIT["FULFILLMENT_IN_TRANSIT"]
      s92_FULFILLMENT_IN_TRANSIT --> s92_FULFILLMENT_PARTIALLY_DELIVERED["FULFILLMENT_PARTIALLY_DELIVERED"]
      s92_FULFILLMENT_IN_TRANSIT --> s92_FULFILLMENT_DELIVERED["FULFILLMENT_DELIVERED"]
      s92_FULFILLMENT_PARTIALLY_DELIVERED --> s92_FULFILLMENT_DELIVERED
      s92_FULFILLMENT_DELIVERED --> s92_FULFILLMENT_CLOSED["FULFILLMENT_CLOSED"]
      s92_FULFILLMENT_ALLOCATING --> s92_FULFILLMENT_EXCEPTION["FULFILLMENT_EXCEPTION"]
      s92_START3(("•")) --> s92_AR_OPEN["AR_OPEN"]
      s92_AR_OPEN --> s92_AR_PARTIALLY_PAID["AR_PARTIALLY_PAID"]
      s92_AR_PARTIALLY_PAID --> s92_AR_PAID["AR_PAID"]
      s92_AR_OPEN --> s92_AR_DISPUTED["AR_DISPUTED"]
      s92_AR_PARTIALLY_PAID --> s92_AR_DISPUTED
      s92_AR_DISPUTED -->|"resolution"| s92_AR_OPEN
      s92_AR_OPEN -->|"authorized correction"| s92_AR_WRITTEN_OFF["AR_WRITTEN_OFF"]
    end

    subgraph S93["9.3 Integration, reconciliation and notification"]
      direction TB
      s93_START1(("•")) --> s93_CR_DRAFT["CR_DRAFT"]
      s93_CR_DRAFT --> s93_CR_VALIDATED["CR_VALIDATED"]
      s93_CR_DRAFT --> s93_CR_VALIDATION_FAILED["CR_VALIDATION_FAILED"]
      s93_CR_VALIDATED --> s93_CR_APPROVAL_REQUIRED["CR_APPROVAL_REQUIRED"]
      s93_CR_VALIDATED --> s93_CR_APPROVED["CR_APPROVED"]
      s93_CR_APPROVAL_REQUIRED --> s93_CR_APPROVED
      s93_CR_APPROVED --> s93_CR_EXECUTION_PENDING["CR_EXECUTION_PENDING"]
      s93_CR_EXECUTION_PENDING --> s93_CR_EXECUTED["CR_EXECUTED"]
      s93_CR_EXECUTION_PENDING --> s93_CR_FAILED_RETRYABLE["CR_FAILED_RETRYABLE"]
      s93_CR_EXECUTION_PENDING --> s93_CR_FAILED_TERMINAL["CR_FAILED_TERMINAL"]
      s93_CR_EXECUTION_PENDING --> s93_CR_RECON_REQUIRED["CR_RECONCILIATION_REQUIRED"]
      s93_CR_FAILED_RETRYABLE -->|"same effect identity"| s93_CR_EXECUTION_PENDING
      s93_CR_DRAFT --> s93_CR_CANCELLED["CR_CANCELLED"]
      s93_START2(("•")) --> s93_SYNC_UNOBSERVED["SYNC_UNOBSERVED"]
      s93_SYNC_UNOBSERVED --> s93_SYNC_OBSERVED_EXTERNAL["SYNC_OBSERVED_EXTERNAL"]
      s93_SYNC_OBSERVED_EXTERNAL --> s93_SYNC_IN_SYNC["SYNC_IN_SYNC"]
      s93_SYNC_IN_SYNC --> s93_SYNC_LOCAL_CHANGE_PENDING["SYNC_LOCAL_CHANGE_PENDING"]
      s93_SYNC_IN_SYNC --> s93_SYNC_EXTERNAL_CHANGE_DETECTED["SYNC_EXTERNAL_CHANGE_DETECTED"]
      s93_SYNC_LOCAL_CHANGE_PENDING --> s93_SYNC_BOTH_SIDES_CHANGED["SYNC_BOTH_SIDES_CHANGED"]
      s93_SYNC_EXTERNAL_CHANGE_DETECTED --> s93_SYNC_BOTH_SIDES_CHANGED
      s93_SYNC_BOTH_SIDES_CHANGED --> s93_SYNC_CONFLICT_REVIEW_REQUIRED["SYNC_CONFLICT_REVIEW_REQUIRED"]
      s93_SYNC_CONFLICT_REVIEW_REQUIRED --> s93_SYNC_APPROVED["SYNC_APPROVED"]
      s93_SYNC_APPROVED --> s93_SYNC_IN_PROGRESS["SYNC_IN_PROGRESS"]
      s93_SYNC_IN_PROGRESS --> s93_SYNC_SYNCED["SYNC_SYNCED"]
      s93_SYNC_IN_PROGRESS --> s93_SYNC_FAILED_RETRYABLE["SYNC_FAILED_RETRYABLE"]
      s93_SYNC_IN_PROGRESS --> s93_SYNC_FAILED_TERMINAL["SYNC_FAILED_TERMINAL"]
      s93_SYNC_IN_PROGRESS --> s93_SYNC_RECON_REQUIRED["SYNC_RECONCILIATION_REQUIRED"]
      s93_START3(("•")) --> s93_NOTIF_PENDING["NOTIFICATION_PENDING"]
      s93_NOTIF_PENDING --> s93_NOTIF_DISPATCHING["NOTIFICATION_DISPATCHING"]
      s93_NOTIF_DISPATCHING --> s93_NOTIF_PROVIDER_ACCEPTED["NOTIFICATION_PROVIDER_ACCEPTED"]
      s93_NOTIF_PROVIDER_ACCEPTED --> s93_NOTIF_DELIVERED["NOTIFICATION_DELIVERED"]
      s93_NOTIF_PROVIDER_ACCEPTED --> s93_NOTIF_FAILED["NOTIFICATION_FAILED"]
      s93_NOTIF_PENDING --> s93_NOTIF_CANCELLED["NOTIFICATION_CANCELLED"]
      s93_NOTIF_DISPATCHING --> s93_NOTIF_EXPIRED["NOTIFICATION_EXPIRED"]
    end

    subgraph S94["9.4 AI, bot, Sites and support control states"]
      direction TB
      s94_START1(("•")) --> s94_AI_QUEUED["AI_QUEUED"]
      s94_AI_QUEUED --> s94_AI_RUNNING["AI_RUNNING"]
      s94_AI_RUNNING --> s94_AI_SUCCEEDED["AI_SUCCEEDED"]
      s94_AI_RUNNING --> s94_AI_FAILED_RETRYABLE["AI_FAILED_RETRYABLE"]
      s94_AI_RUNNING --> s94_AI_FAILED_TERMINAL["AI_FAILED_TERMINAL"]
      s94_AI_RUNNING --> s94_AI_REJECTED_SCHEMA["AI_REJECTED_SCHEMA"]
      s94_AI_SUCCEEDED -->|"evidence version changed"| s94_AI_STALE["AI_STALE"]
      s94_START2(("•")) --> s94_BOT_DRAFT["BOT_DRAFT"]
      s94_BOT_DRAFT --> s94_BOT_VALIDATED["BOT_VALIDATED"]
      s94_BOT_VALIDATED --> s94_BOT_APPROVAL_REQUIRED["BOT_APPROVAL_REQUIRED"]
      s94_BOT_APPROVAL_REQUIRED --> s94_BOT_PUBLISHED["BOT_PUBLISHED"]
      s94_BOT_PUBLISHED --> s94_BOT_ACTIVE["BOT_ACTIVE"]
      s94_BOT_ACTIVE --> s94_BOT_PAUSED["BOT_PAUSED"]
      s94_BOT_PAUSED --> s94_BOT_ACTIVE
      s94_BOT_ACTIVE --> s94_BOT_RETIRED["BOT_RETIRED"]
      s94_START3(("•")) --> s94_PUB_REQUESTED["PUB_REQUESTED"]
      s94_PUB_REQUESTED --> s94_PUB_BUILDING["PUB_BUILDING"]
      s94_PUB_BUILDING --> s94_PUB_BUILT["PUB_BUILT"]
      s94_PUB_BUILT --> s94_PUB_VERIFYING["PUB_VERIFYING"]
      s94_PUB_VERIFYING --> s94_PUB_READY["PUB_READY"]
      s94_PUB_READY --> s94_PUB_ACTIVATING["PUB_ACTIVATING"]
      s94_PUB_ACTIVATING --> s94_PUB_ACTIVE["PUB_ACTIVE"]
      s94_PUB_BUILDING --> s94_PUB_FAILED["PUB_FAILED"]
      s94_PUB_VERIFYING --> s94_PUB_FAILED
      s94_PUB_ACTIVE --> s94_PUB_ROLLED_BACK["PUB_ROLLED_BACK"]
      s94_PUB_ACTIVE --> s94_PUB_REVOKED["PUB_REVOKED"]
      s94_START4(("•")) --> s94_GRANT_REQUESTED["GRANT_REQUESTED"]
      s94_GRANT_REQUESTED --> s94_GRANT_APPROVED["GRANT_APPROVED"]
      s94_GRANT_REQUESTED --> s94_GRANT_DENIED["GRANT_DENIED"]
      s94_GRANT_APPROVED --> s94_GRANT_ACTIVE["GRANT_ACTIVE"]
      s94_GRANT_ACTIVE --> s94_GRANT_EXPIRED["GRANT_EXPIRED"]
      s94_GRANT_ACTIVE --> s94_GRANT_REVOKED["GRANT_REVOKED"]
      s94_START5(("•")) --> s94_OP_QUEUED["OP_QUEUED"]
      s94_OP_QUEUED --> s94_OP_LEASED["OP_LEASED"]
      s94_OP_LEASED --> s94_OP_RUNNING["OP_RUNNING"]
      s94_OP_RUNNING --> s94_OP_SUCCEEDED["OP_SUCCEEDED"]
      s94_OP_RUNNING --> s94_OP_FAILED["OP_FAILED"]
      s94_OP_QUEUED --> s94_OP_CANCELLED["OP_CANCELLED"]
    end
  end

  %% ═══════════════════ 10. EDITABLE ORDER WORKSPACE & TENANT EXTENSIONS ═══════════════════
  subgraph SEC10["10. Editable order workspace and tenant extensions"]
    direction TB
    s10UI["Editable tenant order table"] --> s10CMD["Typed PatchOrderTable command"]
    s10CMD --> s10AUTHZ["Resolve tenant, actor, permission, resource, expected version"]
    s10AUTHZ --> s10LOAD["Load order by tenant and public ID"]
    s10LOAD --> s10STATE["Verify editable state and field policy"]
    s10STATE --> s10PARSE["Parse typed canonical or extension value"]
    s10PARSE --> s10APPLY["Apply bounded batch changes"]
    s10APPLY --> s10REVAL["Mark REVALIDATION_REQUIRED"]
    s10REVAL --> s10RULES["Re-run customer, product, UOM, stock, price, margin, credit, approval rules"]
    s10RULES --> s10TX["Commit order version, audit and required durable intents"]
    subgraph S10MODEL["Order data model"]
      s10CORECOLS["Typed canonical columns<br/>quantities, UOM, product, price, tax, warehouse, state, version"]
      s10EXTJSON["Versioned validated JSONB extensions<br/>data type, allowed values, visibility, editability, schema version"]
      s10VIEW["View configuration projection<br/>column order, labels, width, grouping, filters"]
    end
    s10APPLY --> s10CORECOLS
    s10APPLY --> s10EXTJSON
    s10UI --> s10VIEW
    s10VIEW -. "presentation only, never authority" .-> s10CORECOLS
    s10FORBID["No tenant-authored JavaScript, SQL, shell, executable formulas"] -. "reject" .-> s10EXTJSON
    s10TX -. "owner" .-> M10
  end

  %% ═══════════════════ 11. TRANSACTION OUTBOX, IDEMPOTENCY & EXTERNAL EFFECT ═══════════════════
  subgraph SEC11["11. Transaction outbox, idempotency and external effect"]
    direction TB
    s11CLIENT["Client"] -->|"intent, expectedVersion, idempotencyKey"| s11BOUNDARY["BFF API or service boundary"]
    s11BOUNDARY -->|"trusted context plus bounded request"| s11OWNER["Authoritative module"]
    s11OWNER -->|"load tenant-scoped aggregate and validate transition"| s11PG["PostgreSQL transaction"]
    s11PG --> s11ALT{"Authorized / legal / fresh / no conflict?"}
    s11ALT -- "no: unauthorized, stale, invalid or conflicting" --> s11DENY["deny or conflict"]
    s11DENY --> s11ERR["safe stable error"]
    s11NOTEZERO["zero business mutation, zero success audit, zero outbox"] -.-> s11ERR
    s11ALT -- "yes: legal mutation" --> s11WRITE["write state + BUSINESS_SUCCESS_AUDIT + outbox atomically"]
    s11WRITE --> s11RESULT["committed stable result or operation reference"]
    s11WRITE --> s11OUTBOX["M23 durable envelope"]
    s11OUTBOX -.->|"claim task with lease and fencing token"| s11WORKER["Leased worker"]
    s11WORKER -->|"execute using stable effect identity"| s11EXTERNAL["ERP / PSP / channel provider"]
    s11EXTERNAL --> s11RESALT{"Normal response?"}
    s11RESALT -- "yes" --> s11PROVIDER["provider result"]
    s11PROVIDER --> s11OBS["normalized observation"]
    s11RESALT -- "timeout after possible send" --> s11AMBIG["mark AMBIGUOUS and request read-back"]
    s11AMBIG --> s11RECON["M13 or owning reconciliation flow"]
    s11RECON -->|"query by stable external reference"| s11EXTERNAL
    s11EXTERNAL -.->|"applied / not applied / conflict / unknown"| s11RECON
    s11RECON --> s11RECONRES["normalized reconciled result"]
    s11OUTBOX -. "authority" .-> M23
    s11RECON -. "authority" .-> M13
    s11OWNER -. "is" .-> M23
  end

  %% ═══════════════════ 12. ERP SYNC, CONFLICT & DATA-LEAK BOUNDARY ═══════════════════
  subgraph SEC12["12. ERP sync, conflict and data-leak boundary"]
    direction TB
    s12EXT["ERP / WMS / accounting / marketplace / POS"] --> s12ACCESS{"Approved access method"}
    s12ACCESS --> s12API["Narrow typed API (preferred)"]
    s12ACCESS --> s12VIEW["Read-only tenant or legal-entity views"]
    s12ACCESS --> s12PROC["Allowlisted stored procedure for approved write"]
    s12API --> s12STAGE["Staging, quarantine and canonical mapping"]
    s12VIEW --> s12STAGE
    s12PROC --> s12READBACK["Mandatory read-back"]
    s12STAGE --> s12OBS["M15 normalized observation<br/>source ID, version, fingerprint, time, authority, metadata"]
    s12OBS --> s12OWNER["Domain owner public observation API"]
    s12OWNER --> s12MIRROR[("Minimal tenant-scoped Operant projection")]
    s12INTENT["Confirmed domain intent"] --> s12SNAP["Server-built current snapshot"]
    s12SNAP --> s12CR["Typed ChangeRequest with baseExternalVersion and payload hash"]
    s12CR --> s12COMPARE["Three-way compare<br/>base external, Operant proposal, current external"]
    s12COMPARE --> s12SAFE["Safe field-disjoint merge"]
    s12COMPARE --> s12REVALIDATE["Merge then full business revalidation"]
    s12COMPARE --> s12HUMAN["CONFLICT_REVIEW_REQUIRED"]
    s12COMPARE --> s12FORBIDDEN["Forbidden automatic merge<br/>terminal, fulfilled, monetary contradictions"]
    s12SAFE --> s12EXEC["Leased idempotent execution"]
    s12REVALIDATE --> s12EXEC
    s12HUMAN --> s12APPROVED["Human resolution creates new generation"]
    s12APPROVED --> s12EXEC
    s12EXEC --> s12READBACK
    s12READBACK --> s12RECON["M13 reconciliation case or closure"]
    s12LEAK["No full ERP database copy<br/>No system catalog SELECT *, HR/payroll, secrets, unused PII"] -. "constraint" .-> s12STAGE
    s12BLIND["No blind overwrite or blind duplicate create"] -. "constraint" .-> s12COMPARE
    s12OBS -. "authority" .-> M15
    s12RECON -. "authority" .-> M13
  end

  %% ═══════════════════ 13. CHANNEL, MESSENGER & MARKETPLACE PLATFORM ═══════════════════
  subgraph SEC13["13. Channel, messenger and marketplace platform"]
    direction TB
    s13PROVIDER["Selected channel provider"] --> s13VERIFY["Signature, timestamp, replay and connection ownership"]
    s13VERIFY --> s13RAW["Bounded raw evidence and quarantine"]
    s13RAW --> s13EVENT["Canonical channel event and dedup"]
    s13EVENT --> s13IDMAP["Tenant, channel, sender and customer resolution"]
    s13IDMAP --> s13SAFE["Safety, relevance, intent classification"]
    s13SAFE --> s13ROUTE{"Route"}
    s13ROUTE --> s13BUSINESS["M06 M07 M09 M10 business workflow"]
    s13ROUTE --> s13BOT["M18 bot runtime"]
    s13ROUTE --> s13HANDOFF["Human handoff work item"]
    s13BUSINESS --> s13REPLY["M16 reply intent"]
    s13BOT --> s13REPLY
    s13HANDOFF --> s13REPLY
    s13REPLY --> s13POLICY["Permission, content, preference, rate and response policy"]
    s13POLICY --> s13OUTBOX["Durable provider-send intent"]
    s13OUTBOX --> s13SEND["Provider adapter send"]
    s13SEND --> s13OBS["Delivery observation and reconciliation"]
    subgraph S13SCHED["Fair scheduling"]
      s13QT["Per-tenant queues"]
      s13RL["Provider rate limits"]
      s13WF["Weighted fairness plus aging"]
      s13BP["Backpressure, bounded concurrency"]
      s13DLQ["Visible, authorized dead-letter handling"]
    end
    s13OUTBOX --> S13SCHED
    S13SCHED --> s13SEND
    s13RULE["Wave 1: two or three deep channels before top-20 breadth"] -. "adoption rule" .-> s13PROVIDER
    s13BOT -. "authority" .-> M18
    s13REPLY -. "authority" .-> M16
  end

  %% ═══════════════════ 14. AI ADVISORY & BOTCREATIONENGINE ═══════════════════
  subgraph SEC14["14. AI advisory and BotCreationEngine"]
    direction TB
    s14EVIDENCE["Untrusted tenant-scoped evidence"] --> s14GUARD["Type, size, provenance, injection and data-policy controls"]
    s14GUARD --> s14MIN["Minimized provider-approved context"]
    s14MIN --> s14JOB["Versioned AI job, schema, budget and provider policy"]
    s14JOB --> s14EXECUTE["Isolated bounded execution"]
    s14EXECUTE --> s14PARSER["Strict typed output parser"]
    s14PARSER --> s14STALE["Evidence and aggregate version check"]
    s14STALE --> s14DET["Deterministic domain validation"]
    s14DET --> s14RISK["Confidence, risk and approval policy"]
    s14RISK --> s14REVIEW["Human review or safe rejection"]
    s14REVIEW --> s14OWNERCMD["Owning-domain command fully reauthorized"]
    subgraph S14BOTDEF["Bot creation"]
      s14TEMPLATE["Approved template"]
      s14INTENT["Versioned intent schema"]
      s14QUERY["Allowlisted query bindings"]
      s14COMMAND["Confirmation-required command bindings"]
      s14RESP["Safe response templates"]
      s14KNOW["Knowledge, escalation and handoff policy"]
      s14EVAL["Simulation, evaluation, security lint"]
      s14CONFIG["Tenant configuration"]
      s14VERSION["BotDefinitionVersion"]
    end
    s14TEMPLATE --> s14VERSION
    s14INTENT --> s14VERSION
    s14QUERY --> s14VERSION
    s14COMMAND --> s14VERSION
    s14RESP --> s14VERSION
    s14KNOW --> s14VERSION
    s14EVAL --> s14VERSION
    s14CONFIG --> s14VERSION
    s14VERSION --> s14APPROVE["Authorized publication"]
    s14APPROVE --> s14RUNTIME["Bot runtime reauthorizes every action"]
    s14RUNTIME --> s14OWNERCMD
    s14FORBID["No arbitrary tools, SQL, shell, URLs, secrets, self-granted capabilities, direct ERP/payment/approval"] -. "deny" .-> s14VERSION
    s14PROVIDERS["Remote or self-hosted model adapters are replaceable"] -. "adapter" .-> s14EXECUTE
    s14OWNERCMD -. "targets" .-> M18
  end

  %% ═══════════════════ 15. FINANCE, PAYMENTS, SUBSCRIPTIONS & ENTITLEMENT ═══════════════════
  subgraph SEC15["15. Finance, payments, subscriptions and entitlement"]
    direction TB
    subgraph S15O2C["Customer O2C finance (M12)"]
      s15INVOICE["Invoice or AR obligation"]
      s15PAYOBS["Verified immutable PaymentObservation"]
      s15MATCH["Deterministic match candidates"]
      s15PAYAPP["PaymentAllocation and reversal"]
      s15CLAIM["Claims, disputes, collections"]
    end
    subgraph S15AP["Supplier and AP finance (M12 + M14)"]
      s15PO["Purchase order and receipt"]
      s15APINV["Supplier invoice obligation"]
      s15MATCH3["Two-way or three-way matching"]
      s15PAYPROP["Payment proposal or observation"]
    end
    subgraph S15SAAS["M20 Operant SaaS commercial context"]
      s15PLAN["Plan and entitlement"]
      s15USAGE["Usage facts"]
      s15SUB["Tenant subscription"]
      s15BILLPROJ["Operant billing projection"]
    end
    s15PSP["Hosted tokenized PSP or bank"] --> s15PAYOBS
    s15PAYOBS --> s15MATCH
    s15MATCH --> s15PAYAPP
    s15PAYAPP --> s15INVOICE
    s15INVOICE --> s15CLAIM
    s15PO --> s15APINV
    s15APINV --> s15MATCH3
    s15MATCH3 --> s15PAYPROP
    s15PLAN --> s15ADMISSION["Server-resolved runtime admission"]
    s15USAGE --> s15ADMISSION
    s15SUB --> s15PLAN
    s15BILLPROJ --> s15SUB
    s15HYPER["Hyperswitch optional after multi-PSP routing trigger"] -. "adapter" .-> s15PSP
    s15LAGO["Lago optional after metering complexity trigger"] -. "adapter" .-> s15BILLPROJ
    s15RULE["Provider paid-redirect or text never directly marks invoice or order paid"] -. "invariant" .-> s15PAYOBS
    s15INVOICE -. "authority" .-> M12
    s15PO -. "authority" .-> M14
    s15SUB -. "authority" .-> M20
  end

  %% ═══════════════════ 16. OPERANT SITES (M19) ARCHITECTURE ═══════════════════
  subgraph SEC16["16. Operant Sites (M19) architecture"]
    direction TB
    subgraph S16AUTHOR["Tenant authoring plane"]
      s16EDITOR["React TypeScript declarative editor"]
      s16CONTROL["experience-control private API"]
      s16REV["Immutable ExperienceRevision"]
      s16DS["DesignSystemVersion and typed component AST"]
      s16FORMS["FormDefinitionVersion"]
      s16DOMAIN["DomainBinding"]
    end
    subgraph S16BUILD["Isolated publication pipeline"]
      s16REQUEST["Publish intent, expected revision, idempotency"]
      s16LINT["Schema, security, privacy, SEO, accessibility, performance lint"]
      s16APPROVAL["Approval when policy requires"]
      s16LEASE["Fenced build lease"]
      s16WORKER["Sandboxed non-root deterministic renderer"]
      s16ART["Content-addressed artifacts, manifest, SBOM, provenance"]
      s16VERIFY["Integrity, route, link, malware and public canary checks"]
      s16POINTER["Atomic active-publication pointer"]
    end
    subgraph S16PUBLIC["Public delivery plane"]
      s16TLS["TLS SNI normalized Host"]
      s16WAF["WAF, rate, body and abuse policy"]
      s16BIND["Verified unique DomainBinding lookup"]
      s16MANIFEST["Active PublicationManifest"]
      s16CDN["CDN and private origin immutable response"]
      s16PUBLICFORM["Public form or RFQ receipt"]
      s16BUYERW["Customer-safe dynamic widgets"]
    end
    s16EDITOR --> s16CONTROL
    s16CONTROL --> s16REV
    s16CONTROL --> s16DS
    s16CONTROL --> s16FORMS
    s16CONTROL --> s16DOMAIN
    s16REV --> s16REQUEST
    s16REQUEST --> s16LINT
    s16LINT --> s16APPROVAL
    s16APPROVAL --> s16LEASE
    s16LEASE --> s16WORKER
    s16WORKER --> s16ART
    s16ART --> s16VERIFY
    s16VERIFY --> s16POINTER
    s16POINTER --> s16MANIFEST
    s16TLS --> s16WAF
    s16WAF --> s16BIND
    s16BIND --> s16MANIFEST
    s16MANIFEST --> s16CDN
    s16CDN --> s16PUBLICFORM
    s16CDN --> s16BUYERW
    s16PUBLICFORM --> s16RECEIPT["M19 SubmissionReceipt local transaction and outbox"]
    s16RECEIPT --> s16M06INTAKE["M06 independently accepts or rejects inquiry intent"]
    s16BUYERW --> s16COREOWNERS["M03–M12 customer-safe projections and typed commands"]
    s16SEPDB[("M19 PostgreSQL")] --> s16CONTROL
    s16SEPOBJ[("Private draft and immutable publication objects")] --> s16WORKER
    s16RULE3["No shared writable schema, no distributed transaction, no arbitrary tenant JS/SQL/shell"] -. "invariant" .-> s16CONTROL
    s16RULE4["Static publication remains available during Core authoring outage"] -. "availability boundary" .-> s16CDN
    s16M06INTAKE -. "authority" .-> M06
    s16COREOWNERS -. "authority" .-> M19
    s16CONTROL -. "authority" .-> M19
  end

  %% ═══════════════════ 17. DATA STORAGE, TENANCY, PRIVACY & PROJECTION BOUNDARY ═══════════════════
  subgraph SEC17["17. Data storage, tenancy, privacy and projection boundary"]
    direction TB
    subgraph S17AUTH["Authoritative stores"]
      s17COREDB[("Core PostgreSQL")]
      s17M19DB[("M19 PostgreSQL after extraction")]
    end
    subgraph S17OBJ["Object authority"]
      s17EVIDOBJ[("Immutable evidence objects")]
      s17PUBOBJ[("Immutable publication objects")]
    end
    subgraph S17NONAUTH["Non-authoritative, rebuildable or ephemeral"]
      s17REDIS[("Redis sessions, replay, rate, safe cache")]
      s17FTS["PostgreSQL FTS and trigram"]
      s17TYPE["Optional Typesense projection"]
      s17ANALYTICS["Analytics and operational read models"]
      s17AIMEM["Expiring purpose-bound AI memory"]
    end
    s17TENANTKEY["Every row, object, cache key, task, event, audit, search doc, partition has tenant ownership"] --> s17COREDB
    s17TENANTKEY --> s17M19DB
    s17COREDB --> s17EVIDOBJ
    s17M19DB --> s17PUBOBJ
    s17COREDB --> s17REDIS
    s17COREDB --> s17FTS
    s17COREDB --> s17TYPE
    s17COREDB --> s17ANALYTICS
    s17COREDB --> s17AIMEM
    s17MINIMUM["Application tenant-scoped queries + tenant-aware constraints + least-privileged roles + negative tests"] --> s17COREDB
    s17RLS["Optional RLS defense-in-depth after pool worker migration, backup/restore proof"] -. "optional" .-> s17COREDB
    s17PRIVATE["Data classes C0 public, C1 internal, C2 confidential, C3 restricted, C4 secret"] --> s17LIFECYCLE["Purpose minimization, encryption, retention, legal hold, export, deletion, lineage"]
    s17LIFECYCLE --> s17COREDB
    s17LIFECYCLE --> S17OBJ
    s17LIFECYCLE --> S17NONAUTH
    s17NOAUTH["Redis, search, analytics, provider and AI memory never become business truth"] -. "invariant" .-> S17NONAUTH
  end

  %% ═══════════════════ 18. SUPPORT, CONTROL, BACKUP, RELEASE & INCIDENT ═══════════════════
  subgraph SEC18["18. Support, control, backup, release and incident architecture"]
    direction TB
    s18STAFF["Operant staff identity"] --> s18MFA["Phishing-resistant MFA or step-up"]
    s18MFA --> s18TICKET["Ticket, incident, release reason"]
    s18TICKET --> s18JIT["JIT tenant resource action grant with TTL"]
    s18JIT --> s18ACTION{"Bounded action class"}
    s18ACTION --> s18DIAG["Read-only diagnostic"]
    s18ACTION --> s18REPAIR["Approved dry-run repair"]
    s18ACTION --> s18LIFE["Fixed lifecycle operation via operantctl"]
    s18ACTION --> s18BREAK["Incident-bound break-glass"]
    s18DIAG --> s18AUD["Immutable staff audit"]
    s18REPAIR --> s18SOD["Separation of duties approval"]
    s18SOD --> s18EXEC["Leased executor with fencing"]
    s18LIFE --> s18EXEC
    s18BREAK --> s18ALERT["Immediate alert and post-review"]
    s18EXEC --> s18AUD
    s18ALERT --> s18AUD
    subgraph S18RECOVERY["Recovery"]
      s18BACKUP["Encrypted signed backup manifest"]
      s18RESTORE["Isolated restore drill"]
      s18REBUILD["Rebuild projections and reconcile pending effects"]
      s18SMOKE["Tenant, security, business and artifact smoke tests"]
      s18RPO["Measured RPO and RTO evidence"]
    end
    s18BACKUP --> s18RESTORE
    s18RESTORE --> s18REBUILD
    s18REBUILD --> s18SMOKE
    s18SMOKE --> s18RPO
    subgraph S18RELEASE["Release integrity"]
      s18SOURCE["Exact source SHA"]
      s18BUILD["Reproducible build and tests"]
      s18SBOM["SBOM, scans, provenance"]
      s18SIGN["Signed artifact digest"]
      s18DEPLOY["Deploy reviewed digest"]
      s18VERIFY["Clean-environment verification"]
      s18ROLLBACK["Approved signed rollback or forward fix"]
    end
    s18SOURCE --> s18BUILD
    s18BUILD --> s18SBOM
    s18SBOM --> s18SIGN
    s18SIGN --> s18DEPLOY
    s18DEPLOY --> s18VERIFY
    s18VERIFY --> s18ROLLBACK
    s18NOBACKDOOR["No hidden backdoor, permanent impersonation, unrestricted SQL or shell"] -. "invariant" .-> s18ACTION
    s18STAFF -. "authority" .-> M24
  end

  %% ═══════════════════ 19. RELIABILITY, DEGRADATION & RECOVERY ROUTING ═══════════════════
  subgraph SEC19["19. Reliability, degradation and recovery routing"]
    direction TB
    s19FAILURE{"Dependency failure"}
    s19FAILURE --> s19PGDOWN["PostgreSQL unavailable"]
    s19FAILURE --> s19REDISDOWN["Redis session/replay unavailable"]
    s19FAILURE --> s19AIDOWN["AI unavailable / malformed"]
    s19FAILURE --> s19CONNDOWN["Connector/ERP unavailable"]
    s19FAILURE --> s19SEARCHDOWN["Search/analytics lag"]
    s19FAILURE --> s19NOTDOWN["Notification provider unavailable"]
    s19FAILURE --> s19PAYDOWN["Payment provider unavailable"]
    s19FAILURE --> s19SITECTRL["Experience control or build unavailable"]
    s19FAILURE --> s19COREOUT["Core unavailable to public Sites"]
    s19PGDOWN --> s19PGRES["Authoritative writes fail, no alternate truth"]
    s19REDISDOWN --> s19REDRES["Fail closed where security/correctness depends"]
    s19AIDOWN --> s19AIRES["Manual deterministic review path"]
    s19CONNDOWN --> s19CONRES["Local state remains committed, integration status pending"]
    s19SEARCHDOWN --> s19SEARCHRES["Stale badge, bounded direct authoritative lookup"]
    s19NOTDOWN --> s19NOTRES["Business action stays committed, delivery pending"]
    s19PAYDOWN --> s19PAYRES["Pending/unavailable, never mark paid"]
    s19SITECTRL --> s19SITERES["Current immutable publication remains active"]
    s19COREOUT --> s19CORERES["Static pages and durable generic receipts only, dynamic commerce unavailable"]
    s19CONRES --> s19RECON["Read-back reconciliation before retry"]
    s19PAYRES --> s19RECON
    s19SITERES --> s19VERIFY["Manifest digest public synthetic verification"]
    s19ALL["All screens show loading, stale, queued, retryable, terminal and denied states honestly"] --> s19FAILURE
  end

  %% ═══════════════════ 20. FUTURE SOURCE-TO-PAY (M14 + M12 AP) ═══════════════════
  subgraph SEC20["20. Future Source-to-Pay boundary (M14 + M12 AP)"]
    direction LR
    s20NEED["Business need or requisition"] --> s20SUPPLIER["Supplier discovery and onboarding"]
    s20SUPPLIER --> s20SOURCE["Sourcing event RFI/RFP/RFQ"]
    s20SOURCE --> s20BIDS["Bid submission and evaluation"]
    s20BIDS --> s20AWARD["Authorized award"]
    s20AWARD --> s20CONTRACT["Optional Source-to-Contract sub-capability"]
    s20CONTRACT --> s20REQ["Requisition"]
    s20REQ --> s20PO["Purchase order"]
    s20PO --> s20RECEIPT["Goods or service receipt"]
    s20RECEIPT --> s20APINV["Supplier invoice and AP obligation"]
    s20APINV --> s20MATCHING["Two-way or three-way match"]
    s20MATCHING --> s20PAYPROPOSAL["Payment proposal or verified observation"]
    s20PAYPROPOSAL --> s20RECON["Reconciliation, exceptions and closure"]
    s20M14OWN["M14 owns supplier, sourcing, award, contract, reference requisition, PO process"] -. "ownership" .-> s20SOURCE
    s20M12OWN["M12 owns AP obligation, allocation, reversal and payment observation"] -. "ownership" .-> s20APINV
    s20GATE["State 3 owner-gated and NOT_INSTANTIATED in State 1"] -. "activation" .-> s20NEED
    s20M14OWN -. "authority" .-> M14
    s20M12OWN -. "authority" .-> M12
  end

  %% ═══════════════════ 21. OPTIONAL TECHNOLOGY ADOPTION GATES ═══════════════════
  subgraph SEC21["21. Optional technology adoption gates"]
    direction TB
    s21BASELINE["State-1 baseline"]
    s21BASELINE --> s21PGBASE["PostgreSQL authority + transactional outbox workers"]
    s21BASELINE --> s21SEARCHBASE["PostgreSQL exact FTS trigram search"]
    s21BASELINE --> s21PSPBASE["Direct hosted tokenized PSP port"]
    s21BASELINE --> s21DBPM["Database process manager"]
    s21BASELINE --> s21SPRING["Java Spring Boot modular monolith + ArchUnit or Modulith"]
    s21BROKERTRIG["Persistent queue age, DB contention, independent retry fan-out and ops readiness"] --> s21RABBIT["RabbitMQ"]
    s21SEARCHTRIG["Measured Postgres relevance/latency failure or OLTP harm"] --> s21TYPESENSE["Typesense rebuildable projection"]
    s21PSPTRIG["At least two PSPs, routing/failover, regional methods or material adapter cost"] --> s21HYPER["Hyperswitch"]
    s21BILLTRIG["Usage pricing, multi-product, geography complexity"] --> s21LAGO["Lago behind M20 port"]
    s21WORKTRIG["Measured multi-day timer and manual-signal complexity beyond safe DB operation"] --> s21TEMP["Temporal or Camunda"]
    s21AUTHTRIG["Relationship graph, policy explosion and acceptable independent auth runtime"] --> s21FGA["OpenFGA"]
    s21EVENTTRIG["Many independent consumers, durable replay, sustained volume and CDC maturity"] --> s21KAFKA["Kafka / Redpanda / Debezium"]
    s21RULE["External products are adapters and never own Operant business semantics"] --> s21RABBIT & s21TYPESENSE & s21HYPER & s21LAGO & s21TEMP & s21FGA & s21KAFKA
    s21LAGO -. "behind" .-> M20
  end

  %% ═══════════════════ 22. ARCHITECTURE VALIDATION & RELEASE GATE ═══════════════════
  subgraph SEC22["22. Architecture validation and release gate"]
    direction TB
    s22DESIGN["Architecture target and ADR"] --> s22MOD["Module graph and ownership checks"]
    s22MOD --> s22ROUTES["Route, plane, permission, DTO registry"]
    s22ROUTES --> s22TESTS["Behavioral tests"]
    s22TESTS --> s22RUNTIME["Exact-head runtime and integration evidence"]
    s22RUNTIME --> s22SECURITY["Independent architecture, security, privacy review"]
    s22SECURITY --> s22LOAD["Workload, load, soak, failure injection, recovery"]
    s22LOAD --> s22RELEASE{"Release decision"}
    s22RELEASE --> s22GO["PRODUCTION GO"]
    s22RELEASE --> s22PILOT["CONDITIONAL GO FOR SUPERVISED PILOT"]
    s22RELEASE --> s22NOGO["NO-GO"]
    s22BLOCKERS["Cross-tenant access, spoofable authority, denied mutation side effect, public Core bypass, arbitrary agent command, stale evidence, failed restore or unsigned artifact"] --> s22NOGO
    s22NOTPROVEN["Missing exact-head runtime, customer, capacity, legal or provider evidence"] --> s22NOGO
  end

  %% ═══════════════════ 23. DIAGRAM LEGEND & INTERPRETATION ═══════════════════
  subgraph SEC23["23. Diagram legend and interpretation"]
    direction LR
    s23SOLID["Solid arrow"] --> s23MEAN1["Synchronous request, command or defined process step"]
    s23DOTTED["Dotted arrow"] -.-> s23MEAN2["Policy invariant, optional adapter target or asynchronous relationship"]
    s23DB[("Cylinder")] --> s23MEAN3["Persistent store"]
    s23BOX["Rectangle"] --> s23MEAN4["Module, deployable, boundary, worker or business step"]
    s23DEC{"Diamond"} --> s23MEAN5["Decision or policy branch"]
  end

  %% ═══════════════════ CROSS-SECTION CONSISTENCY LINKS ═══════════════════
  s3COREPG -. "authoritative store" .-> s17COREDB
  s3EXPPG -. "authoritative store" .-> s17M19DB
  s3REDIS2 -. "non-authoritative" .-> s17REDIS
  s6MSET -. "minimal" .-> M19
  s6FSET -. "future" .-> M11 & M17 & M22
  s6NSET -. "not instantiated" .-> M14
```
