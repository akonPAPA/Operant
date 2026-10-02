# Operant Core

Operant is intelligent o2c/e2e/intelligent e-commercesoftware

## License and proprietary notice

Operant is proprietary and confidential software.

Copyright (c) 2026 Operant / Akan Mukhametgali. All rights reserved.
Creative Common 4 License.

Status note:currently building ST1 stage

This repository is intentionally scoped to platform foundation only:

- Java 21 Spring Boot core API
- Next.js TypeScript dashboard shell
- Python 3.12 AI/OCR worker skeleton
- PostgreSQL, Redis, Flyway migrations, Docker Compose
- Security and architecture documentation

AI, frontend, chatbot, and connector components must never directly write trusted business data. Future mutations must go through typed core-api command services, authentication, authorization, tenant policy, deterministic validation, approval gates, transactions, audit events, and outbox events.

# The Demo Version

![Demo](docs/Screenshot2026-09-05154735.png)






### So first of all here is not full but part of the architecture because full detailed architecture was too big

![Full Arch](docs/image.svg)

### Here is also architecture of Source-to-Pay Engine

![Source Arch](docs/image(1).svg)

### Here is Order-to-Cash Architecture flow

![Order Arch](docs/image(2).svg)

### Here is value validation engines flow

![Value Arch](docs/image(3).svg)

### Here is flow why customers paying as it is the Enterprise platform like Esker but with imlemented tools and more convinient Order-Controling flow 

![Why Should Pay](docs/image(4).svg)
