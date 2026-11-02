#!/bin/sh
# Creates a local .env from .env.example with a random value for every placeholder. Never overwrites.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
target="$root/.env"
if [ -e "$target" ]; then
  echo '.env already exists; leaving it unchanged.'
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
