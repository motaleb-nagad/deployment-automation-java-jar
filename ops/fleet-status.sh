#!/bin/bash
# fleet-status.sh — machine-readable fleet status for the deploy portal.
#
# CANONICAL HOME: the ansible package (stop-deploy-start/). This copy in the
# portal repo is a versioned reference — edit the package copy, keep them in sync.
#
# The portal's backend calls this script over SSH (read-only) and parses its
# output. All logic for HOW status is gathered lives here, in the ansible
# package — the backend never invents its own ansible commands. To change what
# "status" means (add hash, use systemctl, narrow the host set, tune forks/
# timeout to reduce prod load), change THIS script; the backend needs no change.
#
# One ansible sweep, no privilege escalation:
#   - reachability (UP / UNREACHABLE), same transport as `ansible -m ping`
#   - which service users have a running jar under /home/<user>/was/
#
# Output: one tab-separated line per host —
#   <host>\t<UP|UNREACHABLE>\t<csv of running service users>
#
# Run from inside stop-deploy-start/ (uses the local ansible.cfg -> ./hosts).

cd "$(dirname "$0")"

# Limit blast/load: one connection attempt, short timeouts, wide fan-out so the
# whole sweep finishes fast. Tune these to taste — this is the only place to.
export ANSIBLE_TIMEOUT="${ANSIBLE_TIMEOUT:-8}"
export ANSIBLE_SSH_ARGS="${ANSIBLE_SSH_ARGS:--o ConnectTimeout=8 -o BatchMode=yes}"
FORKS="${FLEET_FORKS:-30}"

PS_SWEEP="ps -eo args= | grep -oE '/home/[^/]+/was/[^ ]+\.jar' | sed -E 's#.*/home/##; s#/was/.*##' | sort -u | paste -sd, -"

ansible all -m shell -a "$PS_SWEEP" -o --forks "$FORKS" 2>&1 \
| while IFS= read -r line; do
    [ -z "$line" ] && continue
    host="${line%% *}"
    case "$line" in
      *UNREACHABLE*|*"| FAILED"*)
        printf '%s\tUNREACHABLE\t\n' "$host"
        ;;
      *"(stdout)"*)
        csv="${line#*(stdout)}"
        csv="${csv%%(stderr)*}"
        csv="$(printf '%s' "$csv" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"
        printf '%s\tUP\t%s\n' "$host" "$csv"
        ;;
      *">> "*)
        csv="${line#*>> }"
        csv="$(printf '%s' "$csv" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"
        printf '%s\tUP\t%s\n' "$host" "$csv"
        ;;
      *)
        # A continuation line (e.g. an SSH banner) with no host token — skip.
        ;;
    esac
  done
