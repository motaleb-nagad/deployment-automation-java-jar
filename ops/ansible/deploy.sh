#!/bin/bash
# Consolidated mixed-group wrapper.
# Deploy across DIFFERENT groups (core/web/web-dmz/ussd/knotifypush/apigwdoc)
# in ONE run. Add-on only; run.sh and the per-group playbooks are untouched.
#
# Usage:
#   ./deploy.sh "<server:app[,app,...]> <server:app[,app,...]> ..." [actions] [-K]
#
#   server : short host name, same style as run.sh (app1, web2, dmz-web1,
#            ussd2, knotifypush1, apigwdoc1). Prefixed with 'nagad-'.
#            Full 'nagad-...' names are used as-is.
#   app    : component/service user on that host. Multiple apps on one host =
#            comma-separated: "app1:map,dmscore,extch".
#            Repeating a host also works: "app1:map app1:apigw".
#   actions: stop | deploy | start | comma combo. Omit = stop,deploy,start.
#   -K | --ask-pass : prompt for sudo (become) password. Place anywhere.
#
# A host:app pair whose service is not installed on that host is SKIPPED
# automatically by consolidated.yml (it prints "SKIP_PAIR: host:app") and the
# run continues for every service that IS present.
#
# Examples:
#   ./deploy.sh "app1:map web2:syscore dmz-web1:dmsgw ussd2:outboundproxy"
#   ./deploy.sh "app1:map,dmscore,extch web2:dmscore,syscore" start
#   ./deploy.sh "app9:knotify" stop,deploy -K
#   ./deploy.sh "app1:map,apigw web1:auth knotifypush1:knotifypush apigwdoc1:apigw"

set -e
cd "$(dirname "$0")"

PREFIX="nagad-"
PLAYBOOK="consolidated.yml"
[[ -f "$PLAYBOOK" ]] || { echo "Playbook $PLAYBOOK not found"; exit 1; }

# Pull out -K / --ask-pass from anywhere.
ASK_BECOME=""
ARGS=()
for a in "$@"; do
  if [[ "$a" == "-K" || "$a" == "--ask-pass" ]]; then
    ASK_BECOME="--ask-become-pass"
  else
    ARGS+=("$a")
  fi
done
set -- "${ARGS[@]}"

PAIRS_IN="$1"
ACTIONS="${2:-stop,deploy,start}"

if [[ -z "$PAIRS_IN" ]]; then
  grep '^#' "$0" | head -25
  exit 1
fi

resolve_host() {
  local h="$1"
  if [[ "$h" == ${PREFIX}* ]]; then echo "$h"; else echo "${PREFIX}${h}"; fi
}

LIMIT=""
PAIRS=""
declare -A SEEN

for tok in $PAIRS_IN; do
  host_short="${tok%%:*}"
  apps="${tok#*:}"
  if [[ -z "$host_short" || -z "$apps" || "$host_short" == "$tok" ]]; then
    echo "Bad token '$tok' — expected host:app or host:app,app,... (e.g. app1:map,dmscore)"; exit 1
  fi
  full="$(resolve_host "$host_short")"

  # split the comma-separated app list for this host into separate pairs
  IFS=',' read -ra app_arr <<< "$apps"
  for app in "${app_arr[@]}"; do
    app="$(echo "$app" | xargs)"   # trim spaces
    [[ -z "$app" ]] && continue
    PAIRS+="${full}:${app},"
  done

  if [[ -z "${SEEN[$full]:-}" ]]; then
    LIMIT+="${full},"
    SEEN[$full]=1
  fi
done

LIMIT="${LIMIT%,}"
PAIRS="${PAIRS%,}"

echo "============================================"
echo " Mode    : CONSOLIDATED (mixed-group)"
echo " Hosts   : $LIMIT"
echo " Pairs   : $PAIRS"
echo " Actions : $ACTIONS"
echo "============================================"

ansible-playbook "$PLAYBOOK" -l "$LIMIT" $ASK_BECOME \
  -e "target=all" -e "pairs=$PAIRS" -e "actions=$ACTIONS"
