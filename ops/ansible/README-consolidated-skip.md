# Consolidated deploy — skip services that aren't installed on a host

## The problem

In a mixed-group (consolidated) run you can name a `host:app` pair whose
service is **not installed on that host** — e.g. `app3:portal_davs`, where
`portal_davs` physically lives only on the txn-history tier (`app11–13`).

Every stop/deploy/start/hash task escalates with `become_user: "{{ app }}"`.
If that service **user does not exist** on the host, `become` fails with
`sudo: unknown user` (or the `/home/<app>/was` copy fails), the `block`'s
`rescue` re-raises, and the **whole run aborts** — so the services that *were*
present on their hosts never get deployed.

## The fix (server side)

`consolidated.yml` now runs an **availability pre-check** on each host before
the stop/deploy/start block:

```yaml
- name: Detect which target services are actually installed on this host
  become: false                     # never become a user that may not exist
  shell: |
    if getent passwd "{{ item }}" >/dev/null 2>&1 && [ -d "/home/{{ item }}/was" ]; then
      echo present
    else
      echo missing
    fi
  loop: "{{ apps_list }}"
  register: presence
  changed_when: false
  failed_when: false
```

It then splits the host's targets into `present_apps` / `missing_apps`, and:

- **every** stop/deploy/start/hash loop runs over `present_apps` (not the raw
  `apps_list`), so a missing service is simply never attempted → no failure;
- each skipped pair is announced on stdout as a machine-readable marker so the
  deploy console can render it as *skipped* rather than *failed*:

  ```
  SKIP_PAIR: app3:portal_davs
  SKIPPED on nagad-app3 — service not installed here, continuing without it: portal_davs
  ```

- a host where **every** targeted service is missing ends early
  (`meta: end_host`) instead of erroring.

Net effect: the run continues and succeeds for every service that is present;
absent services are skipped and reported. The per-group playbooks
(`core.yml`, `web.yml`, …) and `run.sh` are untouched.

## What to put on the ansible host

Copy these two files into the ansible working directory
(`/home/konasl/motaleb-ansible/stop-deploy-start` — i.e. `ANSIBLE_DIR`),
replacing the previous add-on:

| File               | Change                                            |
|--------------------|---------------------------------------------------|
| `consolidated.yml` | **updated** — availability pre-check + skip logic |
| `deploy.sh`        | unchanged wrapper (included for completeness)      |

They depend only on the existing roles (`stopcomponent`, `deployment`,
`startcomponent`, `notify`) and `group_vars_mail.yml`, all already present.

## Console integration

`AnsibleRunner` parses `SKIP_PAIR:` markers from the live stream; the deploy
finalizer marks those `host:app` rows with the verdict **`skipped`** (no jar
change, registry untouched), and the Deploy result table shows them as
`SKIPPED`. In demo mode the same behaviour is simulated for services that the
fleet inventory marks as host-restricted, so the flow is demonstrable without
touching real hosts.
