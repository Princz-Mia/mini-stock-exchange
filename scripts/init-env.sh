#!/bin/sh
# Creates a local .env from .env.example with a random value for every placeholder. Never overwrites.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
target="$root/.env"
# Warns about host ports something else already uses, which would stop the stack from starting.
check_ports() {
  command -v bash > /dev/null 2>&1 || return 0
  grep -E '^[A-Z_]+_HOST_PORT=[0-9]+$' "$target" | while IFS== read -r name port; do
    if bash -c "exec 3<>/dev/tcp/127.0.0.1/$port" 2> /dev/null; then
      echo "Port $port ($name) is already in use. Unless this stack is running, set $name in .env to a free port."
    fi
  done
}
if [ -e "$target" ]; then
  echo '.env already exists; leaving it unchanged.'
  check_ports
  exit 0
fi

umask 077
while IFS= read -r line; do
  case "$line" in
    *=change-me) printf '%s=%s\n' "${line%%=*}" "$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')" ;;
    *) printf '%s\n' "$line" ;;
  esac
done < "$root/.env.example" > "$target"
echo 'Created .env with random development secrets.'
check_ports
