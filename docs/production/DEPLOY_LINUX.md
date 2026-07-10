# Linux production deployment (P1-D)

**Anchor:** `feature/p1-phase-1` — production Compose profile; Core API is not published to the host.

## Topology

```text
Internet → reverse-proxy (TLS) → web-dashboard (BFF) → core-api (internal network only)
                                      ↓
                                 postgres, redis (internal)
```

## Quick start

```bash
cd infra/production
cp .env.example .env   # fill secrets locally; never commit .env
docker compose -f docker-compose.prod.yml up -d
```

## systemd

See `systemd/operant-compose.service` for a `docker compose up` unit on boot.

## Invariants

- `core-api` has **no** host port mapping in `docker-compose.prod.yml`.
- Browser uses **only** the dashboard origin; `CORE_API_BASE_URL` is server-side on the dashboard container.
- Gateway signing secret is shared between BFF and Core; not exposed via `NEXT_PUBLIC_*`.
