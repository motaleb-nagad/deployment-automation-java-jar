# Confirm a service really started / stopped — don't trust the "started" line

## The problem

When a stop/deploy/start run finishes, the console used to show a service as
**started** the moment Ansible's `startcomponent` role emitted
`changed: [host] => … started`. But that line only means the **start command
fired** — it is *not* proof the JVM came up. A heavy service (e.g. `syscore`)
can take a while to bind its port, and it may die seconds later on a bad jar, a
port clash or OOM. The console would already be green while the process was, in
reality, **down**. On a fintech estate that false positive is dangerous: an
operator believes production is up when it is not.

The same gap exists in reverse for **stop** — the role reports "stopped" as soon
as it sends the signal, before a slow process has actually exited.

## The fix — two layers (defence in depth)

### 1. Console-side verification (already shipped, in this repo)

After a run whose actions include **stop** or **start**, the deploy console now
runs an **independent, read-only** check: it reads the live process table on each
target host and confirms the real state before reporting a result.

- **What it reads** (per service, on its own host):

  ```bash
  ps -u <service-user> -o args= 2>/dev/null | grep -F <jar> | wc -l
  ```

  Scoped to the service's dedicated Linux user, so co-located services that share
  a jar name (`sysgw` / `dmsgw` / `callcentergw` all run `portal-gateway-1.0.jar`)
  stay distinct.

- **How it waits**: it *polls* with backoff — `start` waits up to
  `VERIFY_START_TIMEOUT` (default **180s**) for the process to appear, `stop`
  waits up to `VERIFY_STOP_TIMEOUT` (default **45s**) for it to drain, re-reading
  every `VERIFY_INTERVAL` (default **6s**). The poll settles the instant a service
  reaches the expected state, so a fast service never waits the full window — the
  timeout is only the ceiling before a still-down service is declared
  `not-running`. Raise `VERIFY_START_TIMEOUT` for the very heaviest services.

- **What it reports** (per host×service row, in the result table and audit/mail):

  | verdict         | meaning                                             |
  |-----------------|-----------------------------------------------------|
  | `running`       | start intended, process confirmed up                |
  | `stopped`       | stop intended, process confirmed gone               |
  | `not-running`   | start intended, **no process** after the timeout → **INCIDENT** |
  | `still-running` | stop intended, **still there** after the timeout → **INCIDENT** |
  | `unverified`    | host could not be read — state unconfirmed          |

  A run with any `not-running` / `still-running` row is marked **`incident`**
  (not `ok`); the mail subject and audit line say `INCIDENT`. The result table
  offers a one-click **RETRY** that re-runs just that service's start (or stop)
  and re-verifies.

- **Safety**: the check is **read-only**. It never restarts, kills or rolls
  anything back — it only tells the truth loudly and lets a human decide.

It runs on both consoles (production `./run.sh` + consolidated `./deploy.sh`, and
the staging `stg-deployment` bundle). Tunables live under `nagad.verify.*` in
each backend's `application.yml`; set `VERIFY_ENABLED=false` to switch it off.

This layer needs **nothing** added to the ansible host — it uses the same SSH +
ad-hoc `ansible <host> -m shell` path the console already uses for hash reads.

### 2. Playbook-side gate (recommended — the strongest guarantee)

The console check reports the truth, but the *hardest* guarantee is to make the
**play itself fail** when a service does not come up, so `run.sh` exits non-zero
and the run is red at the source. That gate belongs in the `startcomponent` /
`stopcomponent` roles, which live on the ansible host (not in this repo). Drop
the task below at the **end** of `roles/startcomponent/tasks/main.yml`:

```yaml
# --- verify the service actually came up (fail the play if it did not) -------
- name: Wait for {{ component }} to be running
  become: true
  become_user: "{{ component }}"
  shell: "ps -u {{ component }} -o args= 2>/dev/null | grep -F -- {{ jar_map[component] }} | grep -vc grep"
  register: proc_check
  until: proc_check.stdout | int > 0
  retries: 30            # 30 × 6s ≈ 180s — match VERIFY_START_TIMEOUT
  delay: 6
  changed_when: false
  failed_when: false

- name: Fail if {{ component }} is not running
  fail:
    msg: "{{ component }} did NOT start — no live process for {{ jar_map[component] }} after ~180s"
  when: proc_check.stdout | default('0') | int < 1
```

The mirror for `roles/stopcomponent/tasks/main.yml` asserts the process is
**gone** (`until: proc_check.stdout | int == 0`, then fail if it is still there).

With the gate in place a failed start aborts the run; the console's rescue path
mails the FAILED report exactly as it does for any other task failure — and
layer 1 still labels the specific service, so you get both a red run *and* a
precise per-service verdict.

## Why both

- Layer 1 works today with zero ansible-host changes and gives the operator a
  precise, per-service picture plus one-click retry.
- Layer 2, once the ops team adds it, turns "not running" from a *report* into a
  *hard stop* at the source. Neither replaces the other; together they close the
  false-positive gap end to end.
