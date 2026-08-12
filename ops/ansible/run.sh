#!/bin/bash
# Short-syntax wrapper for stop/deploy/start playbooks.
# Run from inside stop-deploy-start/ (local ansible.cfg -> ./hosts, ./roles)
#
# Usage:
#   ./run.sh <group> <servers> <apps> [actions]
#
#   group   : core | web | web-dmz | ussd | knotifypush | apigwdoc
#             npsb-apigw | npsb-spg | npsb-pp | npsb-png
#   servers : app1            (single)
#             app1,app4       (list)
#             app1..app6      (range)
#             all             (every host in the group's inventory group)
#   apps    : map | map,apigw | ...   (comma separated)
#   actions : stop | deploy | start | combo "stop,deploy"
#             (omit = full cycle: stop,deploy,start)
#
#   -K | --ask-pass : prompt for the sudo (become) password. Needed on
#                     hosts where passwordless sudo to the service user
#                     isn't configured (e.g. nagad-app-limited). Can be
#                     placed anywhere in the arguments.
#
# Examples:
#   ./run.sh core app1 map
#   ./run.sh core app1 map,apigw start
#   ./run.sh core app1..app6 map stop,deploy
#   ./run.sh core app9 knotify start -K
#   ./run.sh web all auth deploy
#   ./run.sh ussd ussd2,ussd3 ussdgwgp
#   ./run.sh web-dmz dmz-web2 sysgw,dmsgw stop
#   ./run.sh core app1..app6 npsb_recon stop,deploy,start
#   ./run.sh knotifypush all knotifypush
#   ./run.sh apigwdoc apigwdoc1 apigw stop,deploy,start
#   ./run.sh npsb-spg npsb-spg1..npsb-spg2 spg stop,deploy,start
#   ./run.sh npsb-pp npsb-pp1..npsb-pp2 pp,npsb_parser stop,deploy,start

set -e
cd "$(dirname "$0")"

# Optional -K (or --ask-pass) anywhere in args => prompt for sudo password.
# Strip it out so positional args still line up.
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

GROUP="$1"; SERVERS="$2"; APPS="$3"; ACTIONS="${4:-stop,deploy,start}"

if [[ -z "$GROUP" || -z "$SERVERS" || -z "$APPS" ]]; then
  grep '^#' "$0" | head -38; exit 1
fi

# inventory group name + host prefix per playbook group
case "$GROUP" in
  core)    INV_GROUP="nagad-app"     ; PREFIX="nagad-"     ;;  # app1 -> nagad-app1
  web)     INV_GROUP="nagad-web"     ; PREFIX="nagad-"     ;;  # web1 -> nagad-web1
  web-dmz) INV_GROUP="nagad-web-dmz" ; PREFIX="nagad-"     ;;  # dmz-web1 -> nagad-dmz-web1
  ussd)    INV_GROUP="nagad-ussd"    ; PREFIX="nagad-"     ;;  # ussd1 -> nagad-ussd1
  knotifypush) INV_GROUP="nagad-knotifypush" ; PREFIX="nagad-" ;;  # knotifypush1 -> nagad-knotifypush1
  apigwdoc)    INV_GROUP="nagad-apigwdoc"    ; PREFIX="nagad-" ;;  # apigwdoc1 -> nagad-apigwdoc1
  npsb-apigw)  INV_GROUP="nagad-npsb-apigw"  ; PREFIX="nagad-" ;;  # npsb-apigw1 -> nagad-npsb-apigw1
  npsb-spg)    INV_GROUP="nagad-npsb-spg"    ; PREFIX="nagad-" ;;  # npsb-spg1 -> nagad-npsb-spg1
  npsb-pp)     INV_GROUP="nagad-npsb-pp"     ; PREFIX="nagad-" ;;  # npsb-pp1 -> nagad-npsb-pp1
  npsb-png)    INV_GROUP="nagad-npsb-png"    ; PREFIX="nagad-" ;;  # npsb-png1 -> nagad-npsb-png1
  *) echo "Unknown group '$GROUP' (use: core|web|web-dmz|ussd|knotifypush|apigwdoc|npsb-apigw|npsb-spg|npsb-pp|npsb-png)"; exit 1 ;;
esac

PLAYBOOK="$GROUP.yml"
[[ -f "$PLAYBOOK" ]] || { echo "Playbook $PLAYBOOK not found"; exit 1; }

# Expand "servers" into an explicit comma host list, or "all"
expand_servers() {
  local input="$1" out=""
  if [[ "$input" == "all" ]]; then echo "all"; return; fi
  IFS=',' read -ra parts <<< "$input"
  for p in "${parts[@]}"; do
    p="$(echo "$p" | xargs)"
    if [[ "$p" == *".."* ]]; then
      local startp="${p%%..*}" endp="${p##*..}"
      local base="${startp%%[0-9]*}"
      local s="${startp//[!0-9]/}" e="${endp//[!0-9]/}"
      for ((i=s; i<=e; i++)); do out+="${PREFIX}${base}${i},"; done
    else
      out+="${PREFIX}${p},"
    fi
  done
  echo "${out%,}"
}

HOSTS="$(expand_servers "$SERVERS")"

# Decide the -l limit and the play target.
#  - "all"  -> run on the whole inventory group
#  - else   -> limit to exactly the hosts you named (they may live in
#              sub-groups like nagad-app-limited / nagad-app-txn-history,
#              so we DON'T intersect with the main group)
if [[ "$HOSTS" == "all" ]]; then
  LIMIT="$INV_GROUP"
  TARGET="$INV_GROUP"
else
  LIMIT="$HOSTS"
  # 'all' as the play target lets named hosts in any sub-group match;
  # the -l limit is what actually restricts the run to your hosts.
  TARGET="all"
fi

echo "============================================"
echo " Group   : $GROUP"
echo " Limit   : $LIMIT"
echo " Apps    : $APPS"
echo " Actions : $ACTIONS"
echo "============================================"

ansible-playbook "$PLAYBOOK" -l "$LIMIT" $ASK_BECOME \
  -e "target=$TARGET" -e "apps=$APPS" -e "actions=$ACTIONS"
