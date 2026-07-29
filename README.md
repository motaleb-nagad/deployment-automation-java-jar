# Nagad Deploy Console
## test
A governed continuous-deployment console for the Nagad estate — a safer, auditable
replacement for the `./run.sh` Ansible wrapper. Fetch a jar from staging, gate it behind
super-admin approval, then run **stop / deploy / start** against production. Every action
is written to an append-only audit trail.

Implemented from the [Claude Design](https://claude.ai/design) handoff in this repository's
`project/` prototype. **React + TypeScript** frontend, **Spring Boot 3 (Java 21)** backend,
**PostgreSQL 15** with Flyway migrations. Docker- and Kubernetes-friendly.

> **Demo mode is the default.** Ansible runs are *simulated* (scripted terminal output, no
> hosts touched) and mail is *logged* rather than sent, so the whole stack runs with no jump
> host, SSH keys, or SMTP relay. The governance model, RBAC, persistence, and audit trail are
> all real. See [Going live](#going-live) to wire the real playbooks and relay.

---

## What it does

| Screen | Purpose |
|---|---|
| **Fleet** | Read-only dashboard: what's down, hash drift across a group, unexpected restarts. Collector-backed (not live); two demo scenarios — `incident` and `healthy`. |
| **Promote** | Fetch a built jar from staging → reads its hash, records it to `registry-db`, emails the super admin, opens an approval request. Never touches production. |
| **Deploy** | Build a `./run.sh`-equivalent (group → hosts → apps → actions), confirm the blast radius, then stream the run live per-host. A `deploy` action is **locked** unless the jar is approved. |
| **Stg Deployment** | The **staging** channel (`/stg-deployment`), backed by a wholly separate backend **container** (`deployment-automation-backend-stg`). Upload-driven: upload a jar / `application.properties` / UI tarball, the service stages it into the `stg-deployment` bundle on the jump host, then runs `./run.sh` (core/portal) or `portalui/run.sh` live. |
| **Approvals** | Super-admin gate — approve or deny pending promotions. Nothing reaches production without a decision here. |
| **Registry** | Persistent record of every governed jar: current prod hash + latest promotion. |
| **History** | Full audit trail of every run with before/after hashes and a log excerpt. |
| **Admin** | Roles and per-group r/w/x permissions (super-admin only). |
| **System** | The colour + component system reference. |

### Governance flow

```
PROMOTE (operator, w)  →  EMAIL super admin  →  APPROVE (super admin)  →  DEPLOY (operator, x)  →  RECORD
   fetch + hash            request PENDING        decision logged          gate unlocks             registry + audit
```

### RBAC

| Role | r | w | x | Can |
|---|:-:|:-:|:-:|---|
| **super admin** | ✓ | ✓ | ✓ | everything, including approve/deny + user management |
| **operator** | ✓ | ✓ | ✓/· | `w` = fetch from staging; `x` = execute an approved deploy (scoped to named groups) |
| **viewer** | ✓ | · | · | read-only across fleet, registry, history |

Enforced **server-side** (`@PreAuthorize` + permission checks in the services) — the UI only
reflects the gate. Sign-in is two-factor: password **+** a 6-digit OTP emailed to the user.

---

## Run it

### Docker Compose (everything)

```bash
docker compose up --build
# → http://localhost:8088
```

Sign in with any seeded account (`s.rahman`, `t.ahmed`, `m.hasan`, `r.karim`), **any password
of 4+ characters** (demo rule), then the OTP — shown on screen in demo mode.

### Local dev (hot reload)

```bash
# backend — H2 in-memory, no Postgres needed
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
#   or: mvn spring-boot:run -Dspring-boot.run.profiles=dev

# frontend — proxies /api to :8080
cd frontend && npm install && npm run dev
# → http://localhost:5173
```

The default (non-`dev`) profile expects Postgres at `DB_URL`. The `dev` profile uses an
in-memory H2 database in PostgreSQL-compatibility mode, so migrations and the app run with
zero external dependencies.

### Kubernetes

```bash
# build + push images (adjust the registry)
docker build -t ghcr.io/shakil128/nagad-cd-backend:latest ./backend  && docker push ghcr.io/shakil128/nagad-cd-backend:latest
docker build -t ghcr.io/shakil128/nagad-cd-frontend:latest ./frontend && docker push ghcr.io/shakil128/nagad-cd-frontend:latest

kubectl apply -f k8s/
# then point deploy.nagad.internal at the ingress
```

Manifests: namespace, Postgres `StatefulSet` (+PVC), backend `Deployment` (with liveness /
readiness / startup probes on `/actuator/health/*`), frontend `Deployment`, and an ingress.
Secrets are placeholders — replace before any real use.

---

## Architecture

```
React + TS (Vite)              Spring Boot 3 (Java 21)                 PostgreSQL 15
─────────────────   /api  ►    ───────────────────────    JPA/Flyway  ─────────────
Fleet / Promote /   REST +     RBAC (token + @PreAuthorize)           promotion
Deploy / Approvals  SSE        AuthService  (password + OTP)          jar_registry
Registry / History             PromotionService / ApprovalService     deployment
Admin / System                 DeploymentService (SSE stream)         app_user
TanStack Query +               AnsibleRunner (simulated | real)       audit_log (append-only)
Zustand                        MailService (simulated | SMTP relay)
```

- **Live output** streams over **Server-Sent Events** (`GET /api/deploy/stream?ticket=…`); the
  run executes on a Java 21 **virtual-thread** worker, never on the request thread.
- **Atomic audit** — each state change and its audit row commit in the same transaction; a
  rolled-back action leaves no orphan entry, and `audit_log` is **append-only** (a Postgres
  trigger blocks `UPDATE`/`DELETE`).
- **Approval re-verified at deploy time** — the gate is checked server-side against the DB on
  every deploy, never trusted from the client.

Backend package layout: `domain` (entities) · `repo` (Spring Data) · `service` (business
logic) · `web` (controllers) · `security` (token filter + RBAC) · `config` · `dto`.

---

## Security

Hardened with a SonarQube-style scan in mind:

- **No secrets in URLs.** The bearer token travels only in the `Authorization` header. The
  SSE deploy stream (which `EventSource` cannot send headers to) is authorised by a
  **single-use, short-lived stream ticket** minted at `POST /api/deploy` — consumed on
  connect, so it can't be replayed and won't linger in access logs.
- **RBAC + gate enforced server-side** — `@PreAuthorize` for role checks, explicit r/w/x
  checks in the services, and the deploy gate re-verified against the DB. The UI never
  decides authorisation.
- **CSRF** is disabled *deliberately and safely*: authentication is a non-cookie bearer
  token, so there is no ambient credential for a cross-site request to abuse (SonarQube
  hotspot `java:S4502` — mark as *Safe*). Session cookies are never issued.
- **Least-exposed surface** — only `/actuator/health/*` and `/actuator/info` are exposed and
  permitted; everything else is authenticated, and unmatched routes are `denyAll`.
- **CORS** uses an explicit origin allow-list (`CORS_ORIGINS`) and a fixed header allow-list —
  no wildcards, credentials off.
- **No sensitive data in logs** — mail bodies (which carry OTP codes) are never logged; OTP
  verification uses a constant-time comparison; tokens/OTPs are generated with `SecureRandom`.
- **Append-only audit** — `audit_log` cannot be updated or deleted (Postgres trigger).
- **Hardened containers** — both images run as a **non-root** user with `allowPrivilegeEscalation:
  false`, all Linux capabilities dropped, `RuntimeDefault` seccomp, and a read-only root
  filesystem on the backend (writable `/tmp` via `emptyDir`). The SPA sets `X-Frame-Options`,
  `X-Content-Type-Options`, `Referrer-Policy`, and a `Content-Security-Policy`.
- **No hardcoded secrets in the runtime config** — compose reads DB credentials from env with
  dev-only defaults; k8s pulls them from a `Secret` (replace the placeholder before use).

The only intentional demo affordances — any-4+-char password (`nagad.auth.demo`), the OTP code
echoed to the UI (`MAIL_SIMULATE`), and simulated Ansible (`ANSIBLE_SIMULATE`) — are all
config-gated and default to *off* semantics the moment those flags are set to `false`. See
[Going live](#going-live).

## Scaling

The bearer-token **sessions**, **OTP codes**, and **in-flight deploy streams** are held in
memory (demo-grade). This is why the backend ships at `replicas: 1`. To scale horizontally,
move those three stores to **Redis** (`SessionStore`, `OtpService`, and the `DeploymentService`
pending-run map are the seams) or enable sticky sessions at the ingress. Postgres is already
the single source of truth for all durable state, so the app tier is otherwise stateless.

## Going live

Flip these env vars (see `k8s/20-backend.yaml` / `docker-compose.yml`) and wire the seams:

| Var | Demo | Live |
|---|---|---|
| `ANSIBLE_SIMULATE` | `true` (scripted output) | `false` — `AnsibleRunner` shells out to `./run.sh` on the jump host via `ProcessBuilder` |
| `MAIL_SIMULATE` | `true` (logged) | `false` — `MailService` sends through `MAIL_RELAY` (10.210.2.16:25) |
| `nagad.auth.demo` | `true` (any 4+ char password) | `false` — bcrypt against `app_user.password_hash`, or swap for SSO/OIDC |

`ANSIBLE_DIR` points at the wrapper's working directory on the jump host.

---

## Staging deployment (`/stg-deployment`)

A **separate deployable** for the `stg-deployment` Ansible bundle: its own Spring Boot app in
`backend-stg/` → image **`deployment-automation-backend-stg`**, running as its own container. It
serves only `/api/stg/**`; nginx routes that prefix to it and everything else to the main
backend, so the governed production path is untouched. Unlike prod (which *fetches* governed
jars), staging is **upload-driven**: the operator uploads the build from the portal, the service
stages it on the jump host, then runs the wrapper.

The staging service **shares the main Postgres** (Flyway off — the main backend owns the schema;
staging runs still appear in History/audit) and **delegates authentication** to the main backend
(it validates each bearer token via `GET /api/auth/me`, so there's a single login for both).

| Channel | Upload → jump-host path (under `STG_DIR`) | Wrapper |
|---|---|---|
| App (jar) | `roles/deployment/files/jars/<jar_map name>` | `./run.sh <core\|portal> all <apps> <actions>` |
| App (config) | `roles/deployment/files/cfg/<app>-application.properties` | (staged for the deployment role) |
| Portal UI | `portalui/roles/portalui/files/<ui>.tar` | `portalui/run.sh <uis> [date]` |

Staging hosts: `core` → ngd-dc-core-01 (10.230.1.208), `portal` → ngd-dc-portal-01 (10.230.1.207).
Uploading needs the **write (w)** permission; executing a run needs **execute (x)**.
`STG_DIR` (default `/home/konasl/motaleb-ansible/stg-deployment`) is where files are staged and
the wrappers run. Upload size is bounded by `STG_MAX_FILE_SIZE` (default 512 MB).

---
##test
## Project layout

```
backend/    Spring Boot 3 API + Flyway migrations (db/migration, db/migration-pg, db/migration-h2)
backend-stg/ Standalone staging deploy service (image deployment-automation-backend-stg); serves /api/stg/*
frontend/   React + TS + Vite SPA (screens/, components/, api/, store/, theme/)
k8s/        Kubernetes manifests (namespace, postgres, backend, frontend, ingress)
project/    the original Claude Design prototype this was built from
docker-compose.yml
```
