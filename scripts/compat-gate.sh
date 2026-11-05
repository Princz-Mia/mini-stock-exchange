#!/usr/bin/env bash
# Compatibility gate. Builds everything, then runs the full stack in a throwaway Compose project
# ("mse-gate") with its own volumes and ports, so a development stack and its data are never touched.
# Needs: JAVA_HOME = Oracle JDK 25.0.5, JAVA17_HOME = a JDK 17, Node 24.22.2, uv, running Docker.
set -uo pipefail

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"
run_dir="$root/runtime/compat-gate"
mkdir -p "$run_dir"
rm -f "$run_dir"/*.log
env_file="$run_dir/gate.env"
summary="$run_dir/summary.txt"
: > "$summary"
web_port=18080
kafka_port=29092

secret() { od -An -N24 -tx1 /dev/urandom | tr -d ' \n'; }
execution_owner_password=$(secret)
execution_app_password=$(secret)
market_data_owner_password=$(secret)
market_data_app_password=$(secret)
trader_password=$(secret)
operator_password=$(secret)
umask 077
cat > "$env_file" <<EOF
POSTGRES_PASSWORD=$(secret)
EXECUTION_DB_OWNER_PASSWORD=$execution_owner_password
EXECUTION_DB_APP_PASSWORD=$execution_app_password
MARKET_DATA_DB_OWNER_PASSWORD=$market_data_owner_password
MARKET_DATA_DB_APP_PASSWORD=$market_data_app_password
GRAFANA_ADMIN_PASSWORD=$(secret)
DEV_OPERATOR_PASSWORD=$operator_password
DEV_TRADER_PASSWORD=$trader_password
WEB_HOST_PORT=$web_port
POSTGRES_HOST_PORT=25432
KAFKA_HOST_PORT=$kafka_port
FLINK_UI_HOST_PORT=28081
EOF

# MSYS_NO_PATHCONV only here: Git Bash must not rewrite container paths, but Maven still needs its conversion.
compose() { MSYS_NO_PATHCONV=1 docker compose -p mse-gate --env-file runtime/compat-gate/gate.env --profile full "$@"; }
mvnw() { sh "$root/mvnw" -B -ntp "$@"; }

log=/dev/null
note() { printf '%s\n' "$*" >> "$log"; }
fail() { printf '%s\n' "$*" >> "$log"; echo "  $*" >&2; return 1; }

assert_equal() { # what expected actual
  note "$1 => $3"
  [ "$(printf '%s' "$3" | tr -d '\r' | sed -e 's/[[:space:]]*$//')" = "$2" ] || fail "$1: expected '$2', got '$3'"
}
assert_match() { # what regex actual
  note "$1 => $3"
  printf '%s' "$3" | grep -Eq "$2" || fail "$1: expected /$2/, got '$3'"
}

password_of() {
  case "$1" in
    execution_owner) printf '%s' "$execution_owner_password" ;;
    execution_app) printf '%s' "$execution_app_password" ;;
    market_data_owner) printf '%s' "$market_data_owner_password" ;;
    market_data_app) printf '%s' "$market_data_app_password" ;;
  esac
}
psql_as() { # role database sql
  compose exec -T -e PGPASSWORD="$(password_of "$1")" postgres \
    psql -h 127.0.0.1 -v ON_ERROR_STOP=1 -U "$1" -d "$2" -Atc "$3" 2>&1
}
assert_sql() { # role database sql expected
  local output
  output=$(psql_as "$1" "$2" "$3") || { fail "$1@$2 '$3' failed: $output"; return 1; }
  assert_equal "$1@$2 $3" "$4" "$output"
}
assert_denied() { # role database sql
  local output
  if output=$(psql_as "$1" "$2" "$3"); then fail "$1@$2 was ALLOWED: $3"; return 1; fi
  note "$1@$2 '$3' => $output"
  printf '%s' "$output" | grep -q 'permission denied' || fail "$1@$2 failed for another reason: $output"
}

http_status() { curl -s -m 10 -o /dev/null -w '%{http_code}' "http://localhost:$web_port$1"; }
readiness() { compose exec -T "$1" curl -fsS http://localhost:8080/actuator/health/readiness 2>&1; }

class_version() { # classpath class major
  assert_match "$2 bytecode" "major version: $3" "$("$JAVA_HOME/bin/javap" -v -cp "$1" "$2" | grep 'major version')"
}

smoke() { # module test simulationId
  mvnw -f "$1" test-compile failsafe:integration-test failsafe:verify \
    "-Dit.test=$2" -Dfailsafe.failIfNoSpecifiedTests=true "-Dsmoke.kafka=localhost:$kafka_port" "-Dsmoke.simulationId=$3" >> "$log" 2>&1
}

step() { # name function
  local name=$1 started=$SECONDS line
  log="$run_dir/$(printf '%s' "$name" | tr -cs 'A-Za-z0-9' '-' | tr 'A-Z' 'a-z').log"
  : > "$log"
  if "$2"; then
    line=$(printf 'PASS  %-44s %5ds' "$name" $((SECONDS - started)))
    echo "$line"; echo "$line" >> "$summary"
  else
    line=$(printf 'FAIL  %-44s %5ds' "$name" $((SECONDS - started)))
    echo "$line"; echo "$line" >> "$summary"
    echo "Log: $log"
    return 1
  fi
}

toolchain() {
  [ -n "${JAVA_HOME:-}" ] || { fail 'JAVA_HOME is not set'; return 1; }
  [ -n "${JAVA17_HOME:-}" ] || { fail 'JAVA17_HOME is not set'; return 1; }
  local properties
  properties=$("$JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1)
  assert_match 'JAVA_HOME version' 'java\.version = 25\.0\.5' "$properties" &&
    assert_match 'JAVA_HOME vendor' 'java\.vendor = Oracle' "$properties" &&
    assert_match 'JAVA17_HOME' 'version "17\.' "$("$JAVA17_HOME/bin/java" -version 2>&1)" &&
    assert_match 'Maven wrapper' 'Apache Maven 3\.9\.16' "$(mvnw -v 2>&1)" &&
    assert_equal 'Node' 'v24.22.2' "$(node --version)" &&
    assert_match 'Docker server' '[0-9]' "$(docker version --format '{{.Server.Version}}')"
}

maven_verify() {
  mvnw clean verify >> "$log" 2>&1 &&
    mvnw -f event-contracts/pom.xml -DskipTests install >> "$log" 2>&1 &&
    class_version services/execution-service/target/classes com.mia_princz.mse.execution.ExecutionServiceApplication 69 &&
    class_version services/market-data-service/target/classes com.mia_princz.mse.marketdata.MarketDataServiceApplication 69 &&
    class_version analytics-job/target/analytics-job.jar com.mia_princz.mse.analytics.AnalyticsJob 61 &&
    class_version event-contracts/target/event-contracts-0.1.0.jar com.mia_princz.mse.contracts.events.TradeExecuted 61
}

frontend() {
  (cd web && npm ci && npm run api:check && npm run typecheck && npm run lint && npm test && npm run build) >> "$log" 2>&1 &&
    [ -f web/dist/index.html ]
}

simulator() {
  (cd simulator && uv sync --frozen) >> "$log" 2>&1 &&
    assert_equal 'Python' 'Python 3.14.10' "$(cd simulator && uv run --frozen python --version)" &&
    (cd simulator && uv run --frozen ruff check . && uv run --frozen ruff format --check . && uv run --frozen pytest -q) >> "$log" 2>&1
}

stack_up() {
  compose down -v --remove-orphans >> "$log" 2>&1
  compose up -d --build --wait --wait-timeout 480 >> "$log" 2>&1
}

runtimes() {
  local service version uid
  for service in execution-service market-data-service; do
    version=$(compose exec -T "$service" java -version 2>&1)
    assert_match "$service JDK" 'version "25\.0\.5"' "$version" || return 1
    assert_match "$service vendor" 'Java\(TM\) SE' "$version" || return 1
  done
  assert_match 'Flink JDK' 'version "17\.' "$(compose exec -T flink-taskmanager java -version 2>&1)" || return 1
  assert_match 'Flink job' '"state":"RUNNING"' "$(compose exec -T flink-jobmanager curl -fsS http://localhost:8081/jobs/overview)" || return 1
  for service in execution-service market-data-service web simulator flink-taskmanager; do
    uid=$(compose exec -T "$service" id -u | tr -d '\r')
    note "$service uid => $uid"
    [ "$uid" != "0" ] || { fail "$service runs as root"; return 1; }
  done
}

kafka_topics() {
  local describe
  describe=$(compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe 2>&1)
  assert_match mse.trades.v1 'Topic: mse\.trades\.v1[[:space:]].*PartitionCount: 3[[:space:]].*retention\.ms=604800000' "$describe" &&
    assert_match mse.market.v1 'Topic: mse\.market\.v1[[:space:]].*PartitionCount: 3[[:space:]].*cleanup\.policy=compact' "$describe" &&
    assert_match mse.candles.v1 'Topic: mse\.candles\.v1[[:space:]].*PartitionCount: 3[[:space:]].*retention\.ms=604800000' "$describe"
}

migrations() {
  assert_sql execution_app execution_db 'SELECT service_name FROM service_identity' execution-service &&
    assert_sql market_data_app market_data_db 'SELECT service_name FROM service_identity' market-data-service &&
    assert_sql execution_owner execution_db "SELECT DISTINCT installed_by || ':' || success FROM flyway_schema_history" execution_owner:true &&
    assert_sql market_data_owner market_data_db "SELECT DISTINCT installed_by || ':' || success FROM flyway_schema_history" market_data_owner:true &&
    assert_sql execution_owner execution_db "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner <> 'execution_owner'" 0 &&
    assert_sql market_data_owner market_data_db "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner <> 'market_data_owner'" 0 &&
    assert_sql market_data_owner market_data_db "SELECT count(*) FROM pg_tables WHERE tablename = 'spring_session'" 0
}

isolation() {
  local role
  for role in execution_owner execution_app; do assert_denied "$role" market_data_db 'SELECT 1' || return 1; done
  for role in market_data_owner market_data_app; do assert_denied "$role" execution_db 'SELECT 1' || return 1; done
  for role in execution_owner execution_app market_data_owner market_data_app; do assert_denied "$role" postgres 'SELECT 1' || return 1; done
  assert_denied execution_app execution_db 'CREATE TABLE intruder (id INT)' &&
    assert_denied market_data_app market_data_db 'CREATE TABLE intruder (id INT)' &&
    assert_denied execution_app execution_db 'DELETE FROM flyway_schema_history' &&
    assert_denied market_data_app market_data_db 'DELETE FROM flyway_schema_history' &&
    assert_denied execution_owner execution_db 'CREATE EXTENSION dblink' &&
    assert_denied market_data_owner market_data_db 'CREATE EXTENSION dblink'
}

proxy_routes() {
  assert_equal /healthz 200 "$(http_status /healthz)" &&
    assert_equal '/ (SPA)' 200 "$(http_status /)" &&
    assert_equal '/deep/link (SPA fallback)' 200 "$(http_status /deep/link)" &&
    assert_equal '/api/v1/orders (Execution, denied)' 401 "$(http_status /api/v1/orders)" &&
    assert_match '/api/v1/instruments (Market Data, public)' '"instruments":\[' "$(curl -s -m 10 "http://localhost:$web_port/api/v1/instruments")" &&
    assert_equal '/api/v1/instruments/x/candles (Market Data, denied)' 403 "$(http_status /api/v1/instruments/x/candles)" &&
    assert_match '/api/v1/market-events, no market yet (Market Data)' '"code":"INSTRUMENT_NOT_FOUND"' "$(curl -s -m 10 "http://localhost:$web_port/api/v1/market-events?instrumentId=00000000-0000-0000-0000-000000000001")" &&
    assert_equal '/api/v1/internal/x (blocked at proxy)' 404 "$(http_status /api/v1/internal/x)" &&
    assert_match '/actuator/health serves the SPA, not Actuator' '^<!doctype html>' "$(curl -s "http://localhost:$web_port/actuator/health")"
}

login_flow() {
  local base="http://localhost:$web_port/api/v1" jar="$run_dir/cookies.txt" token body
  token=$(curl -s -c "$jar" "$base/auth/csrf" | sed -E 's/.*"token":"([^"]+)".*/\1/')
  body=$(curl -s -b "$jar" -c "$jar" -H "X-CSRF-TOKEN: $token" -H 'Content-Type: application/json' \
    -d "{\"username\":\"trader\",\"password\":\"$trader_password\"}" "$base/auth/login")
  assert_match 'login user' '"username":"trader"' "$body" &&
    assert_match 'me' '"roles":\["TRADER"\]' "$(curl -s -b "$jar" "$base/me")"
}

browser_flow() {
  # Users have no API, so the counterparty's login is inserted; the test sets up the market as the operator.
  local counterparty_password
  counterparty_password=$(secret)
  compose cp web/e2e/seed-users.sql postgres:/tmp/seed-users.sql >> "$log" 2>&1 &&
    compose exec -T -e PGPASSWORD="$execution_owner_password" postgres psql -h 127.0.0.1 -q -U execution_owner       -d execution_db -v counterparty_password="$counterparty_password" -f /tmp/seed-users.sql >> "$log" 2>&1 &&
    (cd web && npx playwright install chromium &&
      E2E_BASE_URL="http://localhost:$web_port" E2E_TRADER_PASSWORD="$trader_password"         E2E_OPERATOR_PASSWORD="$operator_password" E2E_COUNTERPARTY_PASSWORD="$counterparty_password"         npm run e2e) >> "$log" 2>&1
}

bot_tokens() {
  # The browser test's setup provisioned two bots; each token is a file only its owner can read.
  assert_equal 'token directory' '700 mse' "$(compose exec -T execution-service stat -c '%a %U' /run/mse/bot-tokens)" &&
    assert_match 'token files' '^ *2 600 mse$' \
      "$(compose exec -T execution-service sh -c 'stat -c "%a %U" /run/mse/bot-tokens/*.token | uniq -c')"
}

bot_trading() {
  # The bots provisioned for the browser test trade once the simulator has a rate, and their trades reach
  # the public market data. The simulator goes back to idle afterwards.
  local sequence=0 published=0 i
  MSE_BOT_RATE=4 compose up -d --no-deps --wait --wait-timeout 120 simulator >> "$log" 2>&1 || return 1
  for i in $(seq 1 60); do
    sequence=$(psql_as execution_owner execution_db "SELECT coalesce(max(t.trade_sequence), 0) FROM trades t JOIN account b ON b.id = t.buyer_account_id JOIN account s ON s.id = t.seller_account_id WHERE b.kind = 'BOT' OR s.kind = 'BOT'" | tr -d '\r')
    [ "${sequence:-0}" -gt 0 ] 2>/dev/null && break
    sleep 2
  done
  assert_match 'latest bot trade sequence' '^[1-9][0-9]*$' "$sequence" || return 1
  for i in $(seq 1 30); do
    published=$(curl -s -m 10 "http://localhost:$web_port/api/v1/instruments" |
      grep -o '"lastTradeSequence":"[0-9]*"' | grep -o '[0-9]*' | sort -n | tail -1)
    [ "${published:-0}" -ge "$sequence" ] && break
    sleep 2
  done
  assert_match "public tape reached bot trade $sequence" '^[0-9]+$' "$published" &&
    [ "$published" -ge "$sequence" ] || { fail "public tape at '$published', bot trade at $sequence"; return 1; }
  compose up -d --no-deps --wait --wait-timeout 120 simulator >> "$log" 2>&1
}

independence() {
  compose stop market-data-service >> "$log" 2>&1 &&
    assert_match 'instruments with Market Data down' '^50[24]$' "$(http_status /api/v1/instruments)" &&
    assert_equal 'orders with Market Data down' 401 "$(http_status /api/v1/orders)" &&
    assert_match 'Execution readiness' '"status":"UP"' "$(readiness execution-service)" &&
    compose start market-data-service >> "$log" 2>&1 &&
    compose up -d --no-deps --wait --wait-timeout 180 market-data-service >> "$log" 2>&1 &&
    compose stop execution-service >> "$log" 2>&1 &&
    assert_match 'orders with Execution down' '^50[24]$' "$(http_status /api/v1/orders)" &&
    assert_equal 'instruments with Execution down' 200 "$(http_status /api/v1/instruments)" &&
    assert_match 'Market Data readiness' '"status":"UP"' "$(readiness market-data-service)" &&
    compose start execution-service >> "$log" 2>&1 &&
    compose up -d --no-deps --wait --wait-timeout 180 execution-service >> "$log" 2>&1
}

avro_pipeline() {
  local simulation_id
  simulation_id=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || powershell.exe -NoProfile -Command '[guid]::NewGuid().ToString()' | tr -d '\r')
  note "simulationId => $simulation_id"
  smoke services/execution-service/pom.xml TradePublishSmoke "$simulation_id" &&
    smoke services/market-data-service/pom.xml CandleConsumeSmoke "$simulation_id"
}

kafka_outage() {
  compose stop kafka >> "$log" 2>&1 &&
    assert_match 'Execution readiness' '"status":"UP"' "$(readiness execution-service)" &&
    assert_match 'Market Data readiness' '"status":"UP"' "$(readiness market-data-service)"
}

teardown() {
  if [ "${1:-}" = "--keep-stack" ]; then
    echo 'Stack kept: docker compose -p mse-gate'
  else
    compose down -v --remove-orphans > "$run_dir/teardown.log" 2>&1
  fi
}

status=0
step 'Toolchain versions' toolchain &&
  step 'Maven verify: services, contracts, Flink on JDK 17' maven_verify &&
  step 'Frontend: npm ci, API contract, typecheck, lint, test, build' frontend &&
  step 'Simulator: frozen uv sync, ruff, pytest' simulator &&
  step 'Compose: clean stack builds and turns healthy' stack_up &&
  step 'Runtimes: Oracle JDK 25 services, Flink JDK 17' runtimes &&
  step 'Kafka topics: partitions and retention' kafka_topics &&
  step 'Migrations: each service owns its own history' migrations &&
  step 'Database isolation: cross-service access denied' isolation &&
  step 'Proxy: routes by path to the owning service' proxy_routes &&
  step 'Login: session and CSRF through the proxy' login_flow &&
  step 'Independence: each service survives the other stopping' independence &&
  step 'Browser: trading, phone layout, halt and resume' browser_flow &&
  step 'Bot tokens: one owner-only file per provisioned bot' bot_tokens &&
  step 'Bots: the simulator trades and the tape shows it' bot_trading &&
  step 'Avro: JDK 25 > Kafka > Flink JDK 17 > Kafka > JDK 25' avro_pipeline &&
  step 'Outage isolation: services stay ready without Kafka' kafka_outage ||
  status=1

teardown "${1:-}"
if [ "$status" -ne 0 ]; then
  echo 'COMPATIBILITY GATE: FAILED'
  exit 1
fi
echo 'COMPATIBILITY GATE: PASSED'
