# Smart Environmental Monitor

Public observatory and protected operations console for monitoring environmental conditions across IoT stations.

## Run & Operate

- `pnpm --filter @workspace/api-server run dev` — run the API server (port 5000)
- `pnpm run typecheck` — full typecheck across all packages
- `pnpm run build` — typecheck + build all packages
- `pnpm --filter @workspace/api-spec run codegen` — regenerate API hooks and Zod schemas from the OpenAPI spec
- `pnpm --filter @workspace/db run push` — push DB schema changes (dev only)
- Required env: `DATABASE_URL` — Postgres connection string

## Stack

- pnpm workspaces, Node.js 24, TypeScript 5.9
- API: Express 5
- DB: PostgreSQL + Drizzle ORM
- Validation: Zod (`zod/v4`), `drizzle-zod`
- API codegen: Orval (from OpenAPI spec)
- Build: esbuild (CJS bundle)

## Where things live

- `artifacts/smart-environment-monitor` — React/Vite frontend for the public observatory and admin console
- `artifacts/api-server` — shared Express API service (currently health-only)
- `lib/api-spec/openapi.yaml` — current API contract source of truth
- `lib/api-client-react` — generated client library for contract-backed frontend calls

## Architecture decisions

- The monitoring frontend keeps public environmental information separate from protected admin operations.
- Environmental demo data is isolated behind a typed service boundary until the real API Gateway contracts are available.
- Admin access is treated as a UI guard only; backend authorization remains the actual security boundary.

## Product

The public side helps visitors search a location, inspect current conditions, explore stations, and review environmental trends. The admin side gives authorized operators a focused view of station health, alerts, analytics, AI anomalies, firmware, and service health.

## User preferences

_Populate as you build — explicit user instructions worth remembering across sessions._

## Gotchas

_Populate as you build — sharp edges, "always run X before Y" rules._

## Pointers

- See the `pnpm-workspace` skill for workspace structure, TypeScript setup, and package details
