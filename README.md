# Nagad Deploy Console

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

- **Live output** streams over **Server-Sent Events** (`/api/deploy/{id}/stream`); the run
  executes on a Java 21 **virtual-thread** worker, never on the request thread.
- **Atomic audit** — each state change and its audit row commit in the same transaction; a
  rolled-back action leaves no orphan entry, and `audit_log` is **append-only** (a Postgres
  trigger blocks `UPDATE`/`DELETE`).
- **Approval re-verified at deploy time** — the gate is checked server-side against the DB on
  every deploy, never trusted from the client.

Backend package layout: `domain` (entities) · `repo` (Spring Data) · `service` (business
logic) · `web` (controllers) · `security` (token filter + RBAC) · `config` · `dto`.

---

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

## Project layout

```
backend/    Spring Boot 3 API + Flyway migrations (db/migration, db/migration-pg, db/migration-h2)
frontend/   React + TS + Vite SPA (screens/, components/, api/, store/, theme/)
k8s/        Kubernetes manifests (namespace, postgres, backend, frontend, ingress)
project/    the original Claude Design prototype this was built from
docker-compose.yml
```
