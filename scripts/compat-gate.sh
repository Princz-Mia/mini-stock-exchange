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
# The browser test's counterparty; the Kafka outage step trades as it too.
counterparty_password=$(secret)
grafana_password=$(secret)
umask 077
cat > "$env_file" <<EOF
POSTGRES_PASSWORD=$(secret)
EXECUTION_DB_OWNER_PASSWORD=$execution_owner_password
EXECUTION_DB_APP_PASSWORD=$execution_app_password
MARKET_DATA_DB_OWNER_PASSWORD=$market_data_owner_password
MARKET_DATA_DB_APP_PASSWORD=$market_data_app_password
GRAFANA_ADMIN_PASSWORD=$grafana_password
DEV_OPERATOR_PASSWORD=$operator_password
DEV_TRADER_PASSWORD=$trader_password
MSE_EXPORT_TOKEN=$(secret)
MSE_SIMULATOR_TOKEN=$(secret)
WEB_HOST_PORT=$web_port
POSTGRES_HOST_PORT=25432
KAFKA_HOST_PORT=$kafka_port
FLINK_UI_HOST_PORT=28081
PROMETHEUS_HOST_PORT=29090
GRAFANA_HOST_PORT=23000
EOF

# MSYS_NO_PATHCONV only here: Git Bash must not rewrite container paths, but Maven still needs its conversion.
compose() { MSYS_NO_PATHCONV=1 docker compose -p mse-gate --env-file runtime/compat-gate/gate.env --profile full --profile observability "$@"; }
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

smoke() { # module test phase; the Avro step sets the simulation and minute both phases share
  mvnw -f "$1" test-compile failsafe:integration-test failsafe:verify \
    "-Dit.test=$2" -Dfailsafe.failIfNoSpecifiedTests=true "-Dsmoke.kafka=localhost:$kafka_port" \
    "-Dsmoke.simulationId=$smoke_simulation" "-Dsmoke.minute=$smoke_minute" "-Dsmoke.phase=$3" >> "$log" 2>&1
}

flink_rest() { # path
  compose exec -T flink-jobmanager curl -fsS "http://localhost:8081$1" 2>/dev/null
}

flink_job() { flink_rest /jobs/overview | grep -o '"jid":"[0-9a-f]*","name":"mse-analytics"[^}]*"state":"RUNNING"' | cut -d'"' -f4; }

flink_count() { # job counter: the first match is in the checkpoint counts
  flink_rest "/jobs/$1/checkpoints" | grep -o "\"$2\":[0-9]*" | head -n 1 | cut -d: -f2
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
    assert_equal '/api/v1/instruments/x/orders (Market Data, denied)' 403 "$(http_status /api/v1/instruments/x/orders)" &&
    assert_match '/api/v1/market-events, no market yet (Market Data)' '"code":"INSTRUMENT_NOT_FOUND"' "$(curl -s -m 10 "http://localhost:$web_port/api/v1/market-events?instrumentId=00000000-0000-0000-0000-000000000001")" &&
    assert_equal '/api/v1/internal/x (blocked at proxy)' 404 "$(http_status /api/v1/internal/x)" &&
    assert_match '/internal/exports/trades serves the SPA, not the export' '^<!doctype html>' \
      "$(curl -s "http://localhost:$web_port/internal/exports/trades?instrumentId=00000000-0000-0000-0000-000000000001")" &&
    assert_match '/internal/simulator/assignment serves the SPA, not the assignment' '^<!doctype html>'       "$(curl -s "http://localhost:$web_port/internal/simulator/assignment")" &&
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
  compose cp web/e2e/seed-users.sql postgres:/tmp/seed-users.sql >> "$log" 2>&1 &&
    compose exec -T -e PGPASSWORD="$execution_owner_password" postgres psql -h 127.0.0.1 -q -U execution_owner       -d execution_db -v counterparty_password="$counterparty_password" -f /tmp/seed-users.sql >> "$log" 2>&1 &&
    (cd web && npx playwright install chromium &&
      E2E_BASE_URL="http://localhost:$web_port" E2E_TRADER_PASSWORD="$trader_password"         E2E_OPERATOR_PASSWORD="$operator_password" E2E_COUNTERPARTY_PASSWORD="$counterparty_password"         npm run e2e) >> "$log" 2>&1
}

bot_tokens() {
  # The browser test's setup provisioned two bots; each token is a file only its owner can read.
  assert_equal 'token directory' '700 mse' "$(compose exec -T execution-service stat -c '%a %U' /run/mse/bot-tokens)" &&
    assert_match 'token files' '^ *2 600 mse$' \
      "$(compose exec -T execution-service sh -c 'stat -c "%a %U" /run/mse/bot-tokens/*.token | uniq -c')" &&
    assert_equal 'the simulator token opens its assignment' 200 "$(internal_status MSE_SIMULATOR_TOKEN)" &&
    assert_equal 'the export token does not' 403 "$(internal_status MSE_EXPORT_TOKEN)"
}

internal_status() { # the variable holding the token, read inside the container so it never reaches this shell
  compose exec -T execution-service sh -c "curl -s -o /dev/null -w '%{http_code}' -H \"Authorization: Bearer \$$1\" http://localhost:8080/internal/simulator/assignment"
}

uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || powershell.exe -NoProfile -Command '[guid]::NewGuid().ToString()' | tr -d '\r'; }

# The outage steps work through the proxy as users would: sessions, CSRF tokens and a fresh key per command.
api="http://localhost:$web_port/api/v1"
api_csrf() { curl -s -b "$1" -c "$1" "$api/auth/csrf" | sed -E 's/.*"token":"([^"]+)".*/\1/'; }
api_login() { # jar username password
  rm -f "$1"
  curl -s -b "$1" -c "$1" -H "X-CSRF-TOKEN: $(api_csrf "$1")" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$2\",\"password\":\"$3\"}" "$api/auth/login" | grep -q "\"username\":\"$2\"" ||
    fail "login as $2"
}
api_command() { # jar path [json]: the body, then the status on the last line
  if [ -n "${3:-}" ]; then
    curl -s -b "$1" -H "X-CSRF-TOKEN: $(api_csrf "$1")" -H "Idempotency-Key: $(uuid)" \
      -H 'Content-Type: application/json' -d "$3" -w '\n%{http_code}' "$api$2"
  else
    curl -s -b "$1" -H "X-CSRF-TOKEN: $(api_csrf "$1")" -H "Idempotency-Key: $(uuid)" -X POST \
      -w '\n%{http_code}' "$api$2"
  fi
}
status_of() { printf '%s' "$1" | tail -n 1; }
body_of() { printf '%s' "$1" | sed '$d'; }
limit_order() { # instrument side price
  printf '{"instrumentId":"%s","side":"%s","type":"LIMIT","timeInForce":"GTC","quantity":1,"limitPrice":"%s"}' "$1" "$2" "$3"
}
retry() { # attempts command...: every two seconds until the command succeeds
  local attempts=$1 i
  shift
  for i in $(seq 1 "$attempts"); do
    "$@" && return 0
    sleep 2
  done
  return 1
}

execution_sql() { psql_as execution_owner execution_db "$1" | tr -d '\r'; }
market_data_sql() { psql_as market_data_app market_data_db "$1" | tr -d '\r'; }
nova() { execution_sql "SELECT id FROM instrument WHERE symbol = 'NOVA'"; }
latest_bot_trade() {
  execution_sql "SELECT coalesce(max(t.trade_sequence), 0) FROM trades t JOIN account b ON b.id = t.buyer_account_id
    JOIN account s ON s.id = t.seller_account_id WHERE b.kind = 'BOT' OR s.kind = 'BOT'"
}
bot_trade_after() { [ "$(latest_bot_trade)" -gt "$1" ] 2>/dev/null; }
# NOVA's newest trade on the public tape. Every trade in the stack is NOVA's; the smoke tests' simulations are not listed.
published() {
  curl -s -m 10 "$api/instruments" | sed 's/"instrumentId"/\n&/g' | grep '"symbol":"NOVA"' |
    grep -o '"lastTradeSequence":"[0-9]*"' | grep -o '[0-9]*'
}
tape_reaches() { [ "$(published)" -ge "$1" ] 2>/dev/null; }
feed_state() { curl -s -m 10 "$api/instruments" | grep -o '"feed":{"state":"[A-Z_]*"' | grep -o '[A-Z_]*"$' | tr -d '"'; }
feed_is() { [ "$(feed_state)" = "$1" ]; }
trades_behind() { curl -s -m 10 "$api/instruments/$1/candles" | grep -o '"tradesBehind":[0-9-]*' | cut -d: -f2; }
candles_behind() { [ "$(trades_behind "$1")" -gt 0 ] 2>/dev/null; }
candles_current() { [ "$(trades_behind "$1")" = 0 ]; }
outbox_pending() { execution_sql 'SELECT count(*) FROM outbox WHERE published_at IS NULL'; }
outbox_drained() { [ "$(outbox_pending)" = 0 ]; }
totals="SELECT count(*) || ' ' || sum(quantity) || ' ' || sum(price_cents * quantity) || ' ' || max(trade_sequence)"
totals_match() {
  [ "$(execution_sql "$totals FROM trades WHERE instrument_id = '$(nova)'")" = \
    "$(market_data_sql "$totals FROM public_trade WHERE instrument_id = '$(nova)'")" ]
}

start_bot_run() { # jar seed: prints the run's ID
  local started
  started=$(api_command "$1" /admin/bot-runs "{\"marketMakers\":1,\"noise\":1,\"seed\":$2,\"commandsPerSecond\":\"4.00\"}")
  note "start: $(printf '%s' "$started" | tr '\n' ' ')"
  body_of "$started" | sed -nE 's/.*"runId":"([0-9a-f-]{36})".*/\1/p' | grep . || fail "no run started: $started"
}
run_stopped() { curl -s -b "$1" "$api/admin/bot-runs?limit=1" | grep -q '"status":"STOPPED"'; }
stop_bot_run() { # jar run
  note "stop: $(api_command "$1" "/admin/bot-runs/$2/stop" | tr '\n' ' ')"
  retry 30 run_stopped "$1" || { fail "run $2 did not stop"; return 1; }
  assert_equal 'bot orders left open' 0 "$(execution_sql "SELECT count(*) FROM orders o JOIN account a ON a.id = o.account_id
    WHERE a.kind = 'BOT' AND o.state IN ('OPEN', 'PARTIALLY_FILLED')")"
}

trader_round_trip() { # jar: a resting order placed and cancelled, then the balances read
  local placed order
  placed=$(api_command "$1" /orders "$(limit_order "$(nova)" BUY 1.00)")
  assert_equal 'the trader places an order' 201 "$(status_of "$placed")" || return 1
  order=$(body_of "$placed" | sed -nE 's/.*"orderId":"([0-9a-f-]{36})".*/\1/p')
  assert_equal 'and cancels it' 200 "$(status_of "$(api_command "$1" "/orders/$order/cancel")")" &&
    assert_match 'and reads the balances' '"cash":\{"total"' "$(curl -s -m 10 -b "$1" "$api/portfolio")"
}

reconciled() {
  local report
  report=$(compose exec -T market-data-service curl -fsS http://localhost:8080/internal/reconciliation/candles 2>&1)
  note "reconciliation => $report"
  assert_match 'candles reconcile with the trade export' '"reconciled":true' "$report" &&
    assert_match 'candles were checked' '"candlesChecked":[1-9]' "$report"
}

bot_trading() {
  # The operator starts a run for the two bots the browser test provisioned: a market maker and a noise bot.
  # The simulator follows it, their trades reach the public market data, and a stop ends with the stop confirmed
  # and no bot order left open: the maker's resting quotes are cancelled.
  local base="http://localhost:$web_port/api/v1" jar="$run_dir/operator-cookies.txt" run status open
  local sequence=0 published=0 before i
  local bot_trades="SELECT coalesce(max(t.trade_sequence), 0) FROM trades t JOIN account b ON b.id = t.buyer_account_id JOIN account s ON s.id = t.seller_account_id WHERE b.kind = 'BOT' OR s.kind = 'BOT'"
  csrf() { curl -s -b "$jar" -c "$jar" "$base/auth/csrf" | sed -E 's/.*"token":"([^"]+)".*/\1/'; }
  curl -s -b "$jar" -c "$jar" -H "X-CSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' \
    -d "{\"username\":\"operator\",\"password\":\"$operator_password\"}" "$base/auth/login" >> "$log" 2>&1
  # The browser test's run left bot trades behind; only a newer one shows this run trading.
  before=$(psql_as execution_owner execution_db "$bot_trades" | tr -d '\r')
  assert_match 'bot trades before the run' '^[0-9]+$' "$before" || return 1
  run=$(curl -s -b "$jar" -H "X-CSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuid)" \
    -d '{"marketMakers":1,"noise":1,"seed":7,"commandsPerSecond":"4.00"}' "$base/admin/bot-runs")
  note "start: $run"
  run=$(printf '%s' "$run" | sed -nE 's/.*"runId":"([0-9a-f-]{36})".*/\1/p')
  assert_match 'bot run started' '^[0-9a-f-]{36}$' "$run" || return 1
  for i in $(seq 1 60); do
    sequence=$(psql_as execution_owner execution_db "$bot_trades" | tr -d '\r')
    [ "${sequence:-0}" -gt "$before" ] 2>/dev/null && break
    sleep 2
  done
  assert_match 'latest bot trade sequence' '^[1-9][0-9]*$' "$sequence" &&
    [ "$sequence" -gt "$before" ] || { fail "no bot trade after $before; latest $sequence"; return 1; }
  for i in $(seq 1 30); do
    published=$(curl -s -m 10 "http://localhost:$web_port/api/v1/instruments" |
      grep -o '"lastTradeSequence":"[0-9]*"' | grep -o '[0-9]*' | sort -n | tail -1)
    [ "${published:-0}" -ge "$sequence" ] && break
    sleep 2
  done
  assert_match "public tape reached bot trade $sequence" '^[0-9]+$' "$published" &&
    [ "$published" -ge "$sequence" ] || { fail "public tape at '$published', bot trade at $sequence"; return 1; }
  curl -s -b "$jar" -H "X-CSRF-TOKEN: $(csrf)" -H "Idempotency-Key: $(uuid)" -X POST \
    "$base/admin/bot-runs/$run/stop" >> "$log" 2>&1
  for i in $(seq 1 30); do
    status=$(curl -s -b "$jar" "$base/admin/bot-runs?limit=1" | grep -o '"status":"[A-Z]*"' | head -1)
    [ "$status" = '"status":"STOPPED"' ] && break
    sleep 2
  done
  open="SELECT count(*) FROM orders o JOIN account a ON a.id = o.account_id
    WHERE a.kind = 'BOT' AND o.state IN ('OPEN', 'PARTIALLY_FILLED')"
  assert_equal 'run status after the stop' '"status":"STOPPED"' "$status" &&
    assert_equal 'the worker confirmed it, after placing orders' 't' \
      "$(psql_as execution_owner execution_db "SELECT stopped_at IS NOT NULL AND placed > 0 FROM bot_run WHERE id = '$run'" | tr -d '\r')" &&
    assert_equal 'bot orders left open' 0 "$(psql_as execution_owner execution_db "$open" | tr -d '\r')" || return 1
  # The bots' commands were timed: Prometheus has a bucket edge at each latency budget, and the queue's size.
  local scrape
  scrape=$(compose exec -T execution-service curl -fsS http://localhost:8080/actuator/prometheus 2>&1 |
    grep -E '^mse_(commands_seconds_bucket\{command="place",outcome="committed",le="0\.(15|5)"\}|writer_queue_capacity) ')
  assert_match 'place latency bucket at 150 ms' 'le="0\.15"\} [1-9]' "$scrape" &&
    assert_match 'place latency bucket at 500 ms' 'le="0\.5"\} [1-9]' "$scrape" &&
    assert_match 'writer queue capacity' 'mse_writer_queue_capacity 256' "$scrape"
}

projected_candles() {
  # The restart step's smoke candle, as Market Data stored it: four trades, 15 shares, notional 1,513.00.
  local sql stored i
  sql="SELECT trade_count || ' ' || volume || ' ' || notional_cents || ' ' || source_max_trade_sequence
    FROM candle_1m WHERE simulation_id = '$smoke_simulation'"
  for i in $(seq 1 30); do
    stored=$(psql_as market_data_app market_data_db "$sql" | tr -d '\r')
    [ "$stored" = '4 15 151300 4' ] && break
    sleep 2
  done
  assert_equal 'smoke candle in candle_1m: trades, volume, notional, sequence' '4 15 151300 4' "$stored" || return 1
  # And through the proxy's public API, with the VWAP worked out: 1,513.00 / 15 = 100.8667, rounded to 100.87.
  local instrument
  instrument=$(psql_as market_data_app market_data_db \
    "SELECT instrument_id FROM candle_1m WHERE simulation_id = '$smoke_simulation'" | tr -d '\r')
  assert_match 'smoke candle from the candles API' '"tradeCount":4,"notional":"1513.00","vwap":"100.87"' \
    "$(curl -s -m 10 "http://localhost:$web_port/api/v1/instruments/$instrument/candles?from=$smoke_minute")"
}

reconciled_candles() {
  # Every instrument Execution has, its candles checked against the trade export: the trades from the browser test
  # and the bots, computed by the job across its restart. Exact, or the step fails with the mismatches.
  reconciled
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
  smoke_simulation=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || powershell.exe -NoProfile -Command '[guid]::NewGuid().ToString()' | tr -d '\r')
  smoke_minute=$(date -u +%Y-%m-%dT%H:%M:00Z)
  note "simulationId => $smoke_simulation, minute => $smoke_minute"
  smoke services/execution-service/pom.xml TradePublishSmoke first &&
    smoke services/market-data-service/pom.xml CandleConsumeSmoke first
}

flink_restart() {
  # A checkpoint that holds the Avro step's trades, then a JobManager restart that must resume from the newest one:
  # Flink records the restore and its path, and the next trade continues the same minute's candle exactly.
  local job before latest restored i
  job=$(flink_job)
  before=$(flink_count "$job" completed)
  # Two more completed checkpoints: the second was triggered after the trades were processed.
  for i in $(seq 1 30); do
    [ "$(flink_count "$job" completed)" -ge $((before + 2)) ] && break
    sleep 2
  done
  latest=$(flink_rest "/jobs/$job/checkpoints" | grep -o '"latest":{"completed":{[^}]*' | grep -o '"id":[0-9]*' | cut -d: -f2)
  note "job $job, newest completed checkpoint => $latest"
  assert_match 'checkpoint after the trades' '^[0-9]+$' "$latest" || return 1
  compose restart flink-jobmanager >> "$log" 2>&1 || return 1
  for i in $(seq 1 60); do
    restored=$(flink_job)
    [ -n "$restored" ] && [ "$(flink_count "$restored" restored)" = 1 ] && break
    sleep 2
  done
  # Restoring an older checkpoint would also give correct candles, after a longer replay, so the path is checked too.
  assert_match "restored from job $job at checkpoint $latest or later" \
    "\"external_path\":\"file:/opt/flink/checkpoints/$job/chk-[0-9]+\"" \
    "$(flink_rest "/jobs/$restored/checkpoints" | grep -o '"restored":{[^}]*}')" &&
    [ "$(flink_rest "/jobs/$restored/checkpoints" | grep -o '"restored":{"id":[0-9]*' | grep -o '[0-9]*$')" -ge "$latest" ] ||
    { fail "the restore was not the newest checkpoint of job $job"; return 1; }
  assert_match 'entry point' 'Resuming the analytics job from checkpoint /opt/flink/checkpoints/' \
    "$(compose logs --no-log-prefix flink-jobmanager 2>&1 | grep 'the analytics job' | tail -n 1)" &&
    assert_match 'checkpoint directories: the one resumed from and the running job' '^ *[12]$' \
      "$(compose exec -T flink-jobmanager sh -c 'ls /opt/flink/checkpoints | wc -l')" &&
    smoke services/execution-service/pom.xml TradePublishSmoke after-restart &&
    smoke services/market-data-service/pom.xml CandleConsumeSmoke after-restart
}

flink_outage() {
  # With the analytics job down, the bots keep trading: their trades commit and reach the public tape, a trader places
  # and cancels, and balances read as usual. Only the candles fall behind, and they say so. The job then resumes from
  # its checkpoint, and the candles catch up and reconcile exactly.
  local operator="$run_dir/outage-operator.txt" trader="$run_dir/outage-trader.txt" run before during
  api_login "$operator" operator "$operator_password" && api_login "$trader" trader "$trader_password" || return 1
  before=$(latest_bot_trade)
  run=$(start_bot_run "$operator" 13) || return 1
  retry 60 bot_trade_after "$before" || { fail "no bot trade after $before"; return 1; }
  compose stop flink-taskmanager flink-jobmanager >> "$log" 2>&1 || return 1
  before=$(latest_bot_trade)
  retry 60 bot_trade_after "$before" || { fail "no bot trade after $before with Flink down"; return 1; }
  during=$(latest_bot_trade)
  retry 30 tape_reaches "$during" || { fail "public tape at '$(published)', bot trade at $during"; return 1; }
  trader_round_trip "$trader" || return 1
  retry 30 candles_behind "$(nova)" || { fail "candles say '$(trades_behind "$(nova)")' trades behind"; return 1; }
  note "candles behind with Flink down => $(trades_behind "$(nova)")"
  stop_bot_run "$operator" "$run" || return 1
  compose start flink-jobmanager flink-taskmanager >> "$log" 2>&1 || return 1
  retry 120 candles_current "$(nova)" ||
    { fail "candles still '$(trades_behind "$(nova)")' trades behind after the restart"; return 1; }
  reconciled
}

dashboard() {
  # Grafana provisions the dashboard from the repository; every query on it must work against the live stack.
  GRAFANA_ADMIN_PASSWORD="$grafana_password" uv run --project simulator --frozen python scripts/check-dashboard.py \
    http://localhost:29090 http://localhost:23000 >> "$log" 2>&1
}

kafka_outage() {
  # With Kafka down, both services stay ready and people still trade: the counterparty sells to the trader, the trader
  # places and cancels, and balances read as usual. The public feed says it is stale, and its tape stops short of the
  # new trade while the outbox holds the events. Once Kafka is back, the outbox drains, the tape and its totals match
  # Execution's trades exactly, the feed is live again, and the candles catch up and reconcile.
  local trader="$run_dir/outage-trader.txt" counterparty="$run_dir/outage-counterparty.txt" sold bought trade
  api_login "$trader" trader "$trader_password" &&
    api_login "$counterparty" counterparty "$counterparty_password" || return 1
  compose stop kafka >> "$log" 2>&1 &&
    assert_match 'Execution readiness' '"status":"UP"' "$(readiness execution-service)" &&
    assert_match 'Market Data readiness' '"status":"UP"' "$(readiness market-data-service)" || return 1
  # A price no other order rests at, so the two orders can only meet each other.
  sold=$(api_command "$counterparty" /orders "$(limit_order "$(nova)" SELL 87.65)")
  assert_equal 'the counterparty offers one share' 201 "$(status_of "$sold")" || return 1
  bought=$(api_command "$trader" /orders "$(limit_order "$(nova)" BUY 87.65)")
  assert_equal 'the trader buys it' 201 "$(status_of "$bought")" &&
    assert_match 'filled' '"state":"FILLED"' "$(body_of "$bought")" || return 1
  trade=$(execution_sql "SELECT max(trade_sequence) FROM trades WHERE instrument_id = '$(nova)'")
  trader_round_trip "$trader" || return 1
  retry 20 feed_is STALE || { fail "public feed is '$(feed_state)' with Kafka down"; return 1; }
  [ "$(published)" -lt "$trade" ] || { fail "public tape reached trade $trade with Kafka down"; return 1; }
  assert_match 'unpublished events in the outbox' '^[1-9][0-9]*$' "$(outbox_pending)" || return 1

  compose start kafka >> "$log" 2>&1 &&
    compose up -d --no-deps --wait --wait-timeout 180 kafka >> "$log" 2>&1 || return 1
  retry 60 outbox_drained || { fail "outbox still holds $(outbox_pending) events"; return 1; }
  retry 60 tape_reaches "$trade" || { fail "public tape at '$(published)', trade at $trade"; return 1; }
  retry 30 feed_is LIVE || { fail "public feed is '$(feed_state)' after Kafka returned"; return 1; }
  # The tape comes from market snapshots; public_trade from the trade topic's own consumer, which can be a moment behind.
  retry 30 totals_match
  assert_equal "NOVA's public trades: count, shares, notional, last sequence" \
    "$(execution_sql "$totals FROM trades WHERE instrument_id = '$(nova)'")" \
    "$(market_data_sql "$totals FROM public_trade WHERE instrument_id = '$(nova)'")" || return 1
  retry 120 candles_current "$(nova)" ||
    { fail "candles still '$(trades_behind "$(nova)")' trades behind after Kafka returned"; return 1; }
  reconciled
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
  step 'Browser: trading, halt, resume, bot runs and every screen' browser_flow &&
  step 'Bot tokens: owner-only files, and the simulator control token' bot_tokens &&
  step 'Bots: the simulator trades and the tape shows it' bot_trading &&
  step 'Avro: JDK 25 > Kafka > Flink JDK 17 > Kafka > JDK 25' avro_pipeline &&
  step 'Analytics: a JobManager restart resumes from its checkpoint' flink_restart &&
  step 'Candles: Market Data stores and serves the bars' projected_candles &&
  step 'Reconciliation: candles match the trade export exactly' reconciled_candles &&
  step 'Dashboard: Grafana loads it and every query works' dashboard &&
  step 'Flink outage: trading goes on, candles catch up' flink_outage &&
  step 'Kafka outage: people trade, the feed catches up' kafka_outage ||
  status=1

teardown "${1:-}"
if [ "$status" -ne 0 ]; then
  echo 'COMPATIBILITY GATE: FAILED'
  exit 1
fi
echo 'COMPATIBILITY GATE: PASSED'
