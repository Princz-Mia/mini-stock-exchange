# Compatibility gate. Builds everything, then runs the full stack in a throwaway Compose project
# ("mse-gate") with its own volumes and ports, so a development stack and its data are never touched.
# Needs: JAVA_HOME = Oracle JDK 25.0.5, JAVA17_HOME = a JDK 17, Node 24.22.2, uv, running Docker.
param([switch]$KeepStack)

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$runDir = Join-Path $root 'runtime\compat-gate'
New-Item -ItemType Directory -Force $runDir | Out-Null
Get-ChildItem $runDir -Filter *.log | Remove-Item -Force
$envFile = Join-Path $runDir 'gate.env'
$script:results = @()
$script:currentLog = $null

$webPort = 18080
$kafkaPort = 29092

function New-Secret {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return ($bytes | ForEach-Object { $_.ToString('x2') }) -join ''
}

$passwords = @{
    execution_owner   = New-Secret
    execution_app     = New-Secret
    market_data_owner = New-Secret
    market_data_app   = New-Secret
    postgres          = New-Secret
}
$traderPassword = New-Secret
$operatorPassword = New-Secret
# The browser test's counterparty; the Kafka outage step trades as it too.
$counterpartyPassword = New-Secret
$grafanaPassword = New-Secret
# The commit the simulator image is built from, which its worker reports with each bot run; .dirty when tracked files
# have uncommitted changes, so a build of them never passes for the commit.
$revision = (& git -C $root rev-parse --short=12 HEAD).Trim()
if (& git -C $root status --porcelain --untracked-files=no) { $revision += '.dirty' }
$envLines = @(
    "POSTGRES_PASSWORD=$($passwords.postgres)"
    "EXECUTION_DB_OWNER_PASSWORD=$($passwords.execution_owner)"
    "EXECUTION_DB_APP_PASSWORD=$($passwords.execution_app)"
    "MARKET_DATA_DB_OWNER_PASSWORD=$($passwords.market_data_owner)"
    "MARKET_DATA_DB_APP_PASSWORD=$($passwords.market_data_app)"
    "GRAFANA_ADMIN_PASSWORD=$grafanaPassword"
    "DEV_OPERATOR_PASSWORD=$operatorPassword"
    "DEV_TRADER_PASSWORD=$traderPassword"
    "MSE_EXPORT_TOKEN=$(New-Secret)"
    "MSE_SIMULATOR_TOKEN=$(New-Secret)"
    "MSE_SIMULATOR_REVISION=$revision"
    "WEB_HOST_PORT=$webPort"
    'POSTGRES_HOST_PORT=25432'
    "KAFKA_HOST_PORT=$kafkaPort"
    'FLINK_UI_HOST_PORT=28081'
    'PROMETHEUS_HOST_PORT=29090'
    'GRAFANA_HOST_PORT=23000'
)
[System.IO.File]::WriteAllLines($envFile, [string[]]$envLines, (New-Object System.Text.UTF8Encoding $false))

$compose = @('compose', '-p', 'mse-gate', '--env-file', $envFile, '--profile', 'full', '--profile', 'observability')

function Invoke-Checked {
    param([string]$Exe, [string[]]$Arguments)
    & $Exe @Arguments 2>&1 | Out-File -Append -Encoding utf8 -Width 400 $script:currentLog
    if ($LASTEXITCODE -ne 0) { throw "exit $LASTEXITCODE from: $Exe $($Arguments -join ' ')" }
}

function Step {
    param([string]$Name, [scriptblock]$Body)
    $slug = ($Name -replace '[^A-Za-z0-9]+', '-').ToLower()
    $script:currentLog = Join-Path $runDir "$slug.log"
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        & $Body
        $line = 'PASS  {0,-44} {1,5}s' -f $Name, [int]$watch.Elapsed.TotalSeconds
        Write-Output $line
        $script:results += $line
    }
    catch {
        $line = 'FAIL  {0,-44} {1,5}s  {2}' -f $Name, [int]$watch.Elapsed.TotalSeconds, $_.Exception.Message
        Write-Output $line
        $script:results += $line
        throw
    }
}

function Assert-Equal {
    param([string]$What, $Expected, $Actual)
    "$What => $Actual" | Add-Content -Encoding utf8 $script:currentLog
    if ("$Actual".Trim() -ne "$Expected") { throw "$What`: expected '$Expected', got '$Actual'" }
}

function Assert-Match {
    param([string]$What, [string]$Pattern, $Actual)
    "$What => $Actual" | Add-Content -Encoding utf8 $script:currentLog
    if ("$Actual" -notmatch $Pattern) { throw "$What`: expected /$Pattern/, got '$Actual'" }
}

function Invoke-Psql {
    param([string]$Role, [string]$Password, [string]$Database, [string]$Sql)
    $arguments = $compose + @('exec', '-T', '-e', "PGPASSWORD=$Password", 'postgres', 'psql', '-h', '127.0.0.1', '-v', 'ON_ERROR_STOP=1', '-U', $Role, '-d', $Database, '-Atc', $Sql)
    $output = & docker @arguments 2>&1 | Out-String
    return @{ Code = $LASTEXITCODE; Output = $output.Trim() }
}

function Assert-Sql {
    param([string]$Role, [string]$Database, [string]$Sql, [string]$Expected)
    $result = Invoke-Psql $Role $passwords[$Role] $Database $Sql
    if ($result.Code -ne 0) { throw "$Role@$Database '$Sql' failed: $($result.Output)" }
    Assert-Equal "$Role@$Database $Sql" $Expected $result.Output
}

function Assert-Denied {
    param([string]$Role, [string]$Database, [string]$Sql)
    $result = Invoke-Psql $Role $passwords[$Role] $Database $Sql
    "$Role@$Database '$Sql' => exit $($result.Code): $($result.Output)" | Add-Content -Encoding utf8 $script:currentLog
    if ($result.Code -eq 0) { throw "$Role@$Database was ALLOWED: $Sql" }
    if ($result.Output -notmatch 'permission denied') { throw "$Role@$Database failed for another reason: $($result.Output)" }
}

function Get-Http {
    param([string]$Path)
    return (& curl.exe -s -m 10 -o NUL -w '%{http_code}' "http://localhost:$webPort$Path")
}

function Get-Readiness {
    param([string]$Service)
    $arguments = $compose + @('exec', '-T', $Service, 'curl', '-fsS', 'http://localhost:8080/actuator/health/readiness')
    return (& docker @arguments 2>&1 | Out-String)
}

function Invoke-InContainer {
    param([string]$Service, [string[]]$Command)
    $arguments = $compose + @('exec', '-T', $Service) + $Command
    return (& docker @arguments 2>&1 | Out-String)
}

function Invoke-Smoke {
    # The Avro step sets the simulation and minute that both phases share.
    param([string]$Module, [string]$TestClass, [string]$Phase)
    Invoke-Checked '.\mvnw.cmd' @('-B', '-ntp', '-f', $Module, 'test-compile', 'failsafe:integration-test', 'failsafe:verify',
        "-Dit.test=$TestClass", '-Dfailsafe.failIfNoSpecifiedTests=true', "-Dsmoke.kafka=localhost:$kafkaPort",
        "-Dsmoke.simulationId=$script:smokeSimulation", "-Dsmoke.minute=$script:smokeMinute", "-Dsmoke.phase=$Phase")
}

function Get-FlinkJson {
    param([string]$Path)
    $arguments = $compose + @('exec', '-T', 'flink-jobmanager', 'curl', '-fsS', "http://localhost:8081$Path")
    return (& docker @arguments 2>$null | Out-String)
}

function Get-FlinkJob {
    $match = [regex]::Match((Get-FlinkJson '/jobs/overview'), '"jid":"([0-9a-f]+)","name":"mse-analytics"[^}]*"state":"RUNNING"')
    if ($match.Success) { return $match.Groups[1].Value }
    return ''
}

function Get-FlinkCount {
    # The first match is in the checkpoint counts.
    param([string]$Job, [string]$Counter)
    $match = [regex]::Match((Get-FlinkJson "/jobs/$Job/checkpoints"), """$Counter"":(\d+)")
    if ($match.Success) { return [long]$match.Groups[1].Value }
    return -1
}

# The outage steps work through the proxy as users would: sessions, CSRF tokens and a fresh key per command.
$api = "http://localhost:$webPort/api/v1"

function New-ApiSession {
    param([string]$Username, [string]$Password)
    $csrf = Invoke-RestMethod "$api/auth/csrf" -SessionVariable session
    $body = @{ username = $Username; password = $Password } | ConvertTo-Json
    $login = Invoke-RestMethod "$api/auth/login" -Method Post -WebSession $session -ContentType 'application/json' -Body $body -Headers @{ $csrf.headerName = $csrf.token }
    if ($login.username -ne $Username) { throw "login as $Username" }
    return $session
}

function Invoke-ApiCommand {
    # The status and the body, whatever the status.
    param($Session, [string]$Path, [string]$Json)
    $csrf = Invoke-RestMethod "$api/auth/csrf" -WebSession $Session
    $request = @{ Uri = "$api$Path"; Method = 'Post'; WebSession = $Session; UseBasicParsing = $true
        Headers = @{ $csrf.headerName = $csrf.token; 'Idempotency-Key' = [guid]::NewGuid().ToString() } }
    if ($Json) { $request.ContentType = 'application/json'; $request.Body = $Json }
    try {
        $response = Invoke-WebRequest @request
        return @{ Status = [int]$response.StatusCode; Body = [string]$response.Content }
    }
    catch {
        if ($null -eq $_.Exception.Response) { throw }
        # Windows PowerShell 5.1 leaves ErrorDetails empty for these answers; the body is still in the response stream.
        $body = [string]$_.ErrorDetails.Message
        if (-not $body) {
            $stream = $_.Exception.Response.GetResponseStream()
            if ($stream.CanSeek) { $stream.Position = 0 }
            $body = (New-Object System.IO.StreamReader($stream)).ReadToEnd()
        }
        return @{ Status = [int]$_.Exception.Response.StatusCode; Body = $body }
    }
}

function New-LimitOrder {
    param([string]$Instrument, [string]$Side, [string]$Price)
    return '{"instrumentId":"' + $Instrument + '","side":"' + $Side + '","type":"LIMIT","timeInForce":"GTC","quantity":1,"limitPrice":"' + $Price + '"}'
}

function Wait-Until {
    # Every two seconds until the condition holds.
    param([int]$Attempts, [scriptblock]$Condition)
    foreach ($attempt in 1..$Attempts) {
        if (& $Condition) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Get-ExecutionValue {
    param([string]$Sql)
    return (Invoke-Psql 'execution_owner' $passwords.execution_owner 'execution_db' $Sql).Output
}

function Get-MarketDataValue {
    param([string]$Sql)
    return (Invoke-Psql 'market_data_app' $passwords.market_data_app 'market_data_db' $Sql).Output
}

# The current simulation, as both services define it: the one not closed or, once every one has, the one created last.
# Once the market has closed and a second simulation started, each simulation has its own NOVA and its own sequences,
# so every query below that means "the market" names this one.
$currentSimulation = "(SELECT id FROM simulation ORDER BY state = 'CLOSED', created_at DESC, id DESC LIMIT 1)"

function Get-CurrentSimulation { return Get-ExecutionValue "SELECT $currentSimulation" }

function Get-Nova { return Get-ExecutionValue "SELECT id FROM instrument WHERE symbol = 'NOVA' AND simulation_id = $currentSimulation" }

# Every simulation's NOVA, the oldest simulation first, each as the simulation, NOVA's id and the simulation's state.
function Get-EveryNova {
    return @((Get-ExecutionValue "SELECT string_agg(s.id || ' ' || i.id || ' ' || s.state, ',' ORDER BY s.created_at, s.id) FROM simulation s JOIN instrument i ON i.simulation_id = s.id WHERE i.symbol = 'NOVA'") -split ',')
}

function Get-LatestBotTrade { return [long](Get-ExecutionValue "SELECT coalesce(max(t.trade_sequence), 0) FROM trades t JOIN account b ON b.id = t.buyer_account_id JOIN account s ON s.id = t.seller_account_id WHERE t.simulation_id = $currentSimulation AND (b.kind = 'BOT' OR s.kind = 'BOT')") }

function Get-Listing {
    try { return Invoke-RestMethod "$api/instruments" -TimeoutSec 10 } catch { return $null }
}

function Get-ListedNova {
    # A simulation's instruments as Market Data lists them by the simulation's id, each as its id and venue state.
    param([string]$Simulation)
    try { $listing = Invoke-RestMethod "$api/instruments?simulationId=$Simulation" -TimeoutSec 10 } catch { return '' }
    return (@($listing.instruments | ForEach-Object { "$($_.instrumentId) $($_.venueState)" }) -join ',')
}

# NOVA's newest trade on the public tape: the current simulation's, the only one listed. The smoke tests' simulations
# are never listed.
function Get-Published {
    $listing = Get-Listing
    if ($null -eq $listing) { return -1 }
    return [long](@($listing.instruments | Where-Object { $_.symbol -eq 'NOVA' })[0].lastTradeSequence)
}

function Get-FeedState {
    $listing = Get-Listing
    if ($null -eq $listing) { return '' }
    return $listing.feed.state
}

function Get-TradesBehind {
    param([string]$Instrument)
    try { return [long](Invoke-RestMethod "$api/instruments/$Instrument/candles" -TimeoutSec 10).freshness.tradesBehind } catch { return -1 }
}

function Get-OutboxPending { return [long](Get-ExecutionValue 'SELECT count(*) FROM outbox WHERE published_at IS NULL') }

function Start-BotRun {
    param($Session, [int]$Seed)
    $started = Invoke-ApiCommand $Session '/admin/bot-runs' ('{"marketMakers":1,"noise":1,"seed":' + $Seed + ',"commandsPerSecond":"4.00"}')
    "start: $($started.Status) $($started.Body)" | Add-Content -Encoding utf8 $script:currentLog
    $run = [regex]::Match($started.Body, '"runId":"([0-9a-f-]{36})"')
    if (-not $run.Success) { throw "no run started: $($started.Body)" }
    return $run.Groups[1].Value
}

function Stop-BotRun {
    param($Session, [string]$Run)
    [void](Invoke-ApiCommand $Session "/admin/bot-runs/$Run/stop")
    if (-not (Wait-Until 30 { (Invoke-RestMethod "$api/admin/bot-runs?limit=1" -WebSession $Session).runs[0].status -eq 'STOPPED' })) {
        throw "run $Run did not stop"
    }
    Assert-Equal 'bot orders left open' '0' (Get-ExecutionValue "SELECT count(*) FROM orders o JOIN account a ON a.id = o.account_id WHERE o.simulation_id = $currentSimulation AND a.kind = 'BOT' AND o.state IN ('OPEN', 'PARTIALLY_FILLED')")
}

function Get-ApiStatus {
    param($Session, [string]$Path)
    try { return [int](Invoke-WebRequest "$api$Path" -WebSession $Session -UseBasicParsing).StatusCode }
    catch {
        if ($null -eq $_.Exception.Response) { throw }
        return [int]$_.Exception.Response.StatusCode
    }
}

function Set-UpMarket {
    # As the browser test set up the first simulation: NOVA at 100.00, the trader and the counterparty with 100,000.00
    # and 500 shares each, a market maker and a noise bot with 10,000.00 and 100 shares each; then open. NOVA's id.
    param($Operator)
    $added = Invoke-ApiCommand $Operator '/admin/instruments' '{"symbol":"NOVA","referencePrice":"100.00"}'
    Assert-Equal 'add NOVA' '201' $added.Status
    $instrument = ($added.Body | ConvertFrom-Json).instrumentId
    $holdings = ',"holdings":[{"instrumentId":"' + $instrument + '","quantity":'
    foreach ($username in 'trader', 'counterparty') {
        $funded = Invoke-ApiCommand $Operator '/admin/accounts' ('{"username":"' + $username + '","cash":"100000.00"' + $holdings + '500}]}')
        Assert-Equal "fund $username" '201' $funded.Status
    }
    foreach ($strategy in 'MARKET_MAKER', 'NOISE') {
        $bots = Invoke-ApiCommand $Operator '/admin/bots' ('{"count":1,"cash":"10000.00"' + $holdings + '100}],"strategy":"' + $strategy + '"}')
        Assert-Equal "provision a $strategy bot" '201' $bots.Status
    }
    Assert-Equal 'open the market' '200' (Invoke-ApiCommand $Operator '/admin/market/open').Status
    return $instrument
}

function Get-LedgerCheck {
    # A simulation's balances against its ledger, as Execution's startup reconciliation compares them: each account's
    # cash and shares, in total and reserved. How many differ, then how many were compared.
    param([string]$Simulation)
    $cash = "'00000000-0000-0000-0000-000000000000'::uuid"
    return Get-ExecutionValue ("WITH l AS (SELECT e.account_id, coalesce(e.instrument_id, $cash) AS asset, sum(e.delta) AS total, coalesce(sum(e.delta) FILTER (WHERE e.bucket = 'RESERVED'), 0) AS reserved FROM ledger_entry e JOIN account a ON a.id = e.account_id WHERE a.simulation_id = '$Simulation' AND a.kind <> 'ISSUANCE' GROUP BY 1, 2), " +
        "b AS (SELECT c.account_id, $cash AS asset, c.total_cents AS total, c.reserved_cents AS reserved FROM cash_balance c JOIN account a ON a.id = c.account_id WHERE a.simulation_id = '$Simulation' UNION ALL SELECT p.account_id, p.instrument_id, p.total_quantity, p.reserved_quantity FROM position p JOIN account a ON a.id = p.account_id WHERE a.simulation_id = '$Simulation') " +
        "SELECT count(*) FILTER (WHERE coalesce(l.total, 0) <> coalesce(b.total, 0) OR coalesce(l.reserved, 0) <> coalesce(b.reserved, 0)) || ' ' || count(*) FROM l FULL JOIN b ON l.account_id = b.account_id AND l.asset = b.asset")
}

# A bot's read with the token the close step copied inside Execution's container, so the token never reaches this
# shell. No double quotes: Windows PowerShell 5.1 strips them from native arguments.
$copiedBotRead = "curl -s -o /dev/null -w '%{http_code}' -H Authorization:\ Bearer\ `$(cat /tmp/closed-bot.token) http://localhost:8080/api/v1/portfolio"

function Invoke-TraderRoundTrip {
    # A resting order placed and cancelled, then the balances read.
    param($Session)
    $placed = Invoke-ApiCommand $Session '/orders' (New-LimitOrder (Get-Nova) 'BUY' '1.00')
    Assert-Equal 'the trader places an order' '201' $placed.Status
    $order = [regex]::Match($placed.Body, '"orderId":"([0-9a-f-]{36})"').Groups[1].Value
    Assert-Equal 'and cancels it' '200' (Invoke-ApiCommand $Session "/orders/$order/cancel").Status
    Assert-Match 'and reads the balances' '"cash":\{"total"' (Invoke-WebRequest "$api/portfolio" -WebSession $Session -UseBasicParsing).Content
}

function Assert-Reconciled {
    # Every stored candle exact. A candle missing altogether reconciles too, so for each instrument named the candles
    # must also cover every trade the export has: an instrument whose candles were never computed would pass otherwise.
    param([string[]]$Covering = @())
    $report = ''
    foreach ($attempt in 1..60) {
        $report = Invoke-InContainer 'market-data-service' @('curl', '-fsS', 'http://localhost:8080/internal/reconciliation/candles')
        if (@($Covering | Where-Object { $report -notmatch (Get-CoveredPattern $_) }).Count -eq 0) { break }
        Start-Sleep -Seconds 2
    }
    Assert-Match 'candles reconcile with the trade export' '"reconciled":true' $report
    Assert-Match 'candles were checked' '"candlesChecked":[1-9]' $report
    foreach ($instrument in $Covering) { Assert-Match "the candles of $instrument cover every exported trade" (Get-CoveredPattern $instrument) $report }
}

function Get-CoveredPattern {
    param([string]$Instrument)
    return '"instrumentId":"' + $Instrument + '","candlesThroughTradeSequence":"([1-9][0-9]*)","exportedThroughTradeSequence":"\1"'
}

# Recovery steps: every projection table Market Data derives (service_identity is configuration, not derived).
$projections = 'instrument_projection, market_snapshot, processed_event, projection_checkpoint, public_trade, instrument_session, session_minute, spread_minute, candle_1m'
$tradeTotals = "SELECT count(*) || ' ' || sum(quantity) || ' ' || sum(price_cents * quantity) || ' ' || max(trade_sequence)"
# The session figures Market Data keeps with the tape, worked out from the tape itself: count, shares, notional,
# high, low and the last price by sequence. Equal after every rebuild: the counts double if instrument_session is
# left out of a wipe while the tape is replayed into it.
$sessionFromTape = "SELECT count(*) || ' ' || sum(quantity) || ' ' || sum(price_cents * quantity) || ' ' || max(price_cents) || ' ' || min(price_cents) || ' ' || (array_agg(price_cents ORDER BY trade_sequence DESC))[1] FROM public_trade"
$sessionKept = "SELECT trade_count || ' ' || volume || ' ' || notional_cents || ' ' || high_cents || ' ' || low_cents || ' ' || last_price_cents FROM instrument_session"

function Test-FeedsLive {
    # All three Market Data feeds caught up and in contact with Kafka.
    $scrape = Invoke-InContainer 'market-data-service' @('curl', '-fsS', 'http://localhost:8080/actuator/prometheus')
    return @($scrape -split "`n" | Where-Object { $_ -match '^mse_feed_live\{.*\} 1' }).Count -eq 3
}

function Invoke-MarketDataOwner {
    param([string]$Sql)
    $result = Invoke-Psql 'market_data_owner' $passwords.market_data_owner 'market_data_db' $Sql
    "market_data_owner: $Sql => $($result.Code) $($result.Output)" | Add-Content -Encoding utf8 $script:currentLog
    if ($result.Code -ne 0) { throw "market_data_owner '$Sql' failed: $($result.Output)" }
}

function Assert-CaughtUp {
    # Nothing waiting in the outbox. In every simulation, the closed one too: NOVA's trades all public with the same
    # totals, its session figures as its public trades add up, and Market Data listing it by the simulation's id in the
    # simulation's state. The default list is the current simulation's, its NOVA's candles are current, and every
    # NOVA's candles cover its trades and reconcile exactly.
    if (-not (Wait-Until 60 { (Get-OutboxPending) -eq 0 })) { throw "outbox still holds $(Get-OutboxPending) events" }
    $novas = Get-EveryNova
    foreach ($entry in $novas) {
        $simulation, $instrument, $state = $entry -split ' '
        [void](Wait-Until 60 { (Get-ExecutionValue "$tradeTotals FROM trades WHERE instrument_id = '$instrument'") -eq (Get-MarketDataValue "$tradeTotals FROM public_trade WHERE instrument_id = '$instrument'") })
        Assert-Equal "NOVA's public trades in $simulation ($state): count, shares, notional, last sequence" `
            (Get-ExecutionValue "$tradeTotals FROM trades WHERE instrument_id = '$instrument'") `
            (Get-MarketDataValue "$tradeTotals FROM public_trade WHERE instrument_id = '$instrument'")
        Assert-Equal "NOVA's session figures in $simulation`: as its public trades add up" `
            (Get-MarketDataValue "$sessionFromTape WHERE instrument_id = '$instrument'") `
            (Get-MarketDataValue "$sessionKept WHERE instrument_id = '$instrument'")
        [void](Wait-Until 30 { (Get-ListedNova $simulation) -eq "$instrument $state" })
        Assert-Equal "Market Data lists $simulation by its id: NOVA alone, $state" "$instrument $state" (Get-ListedNova $simulation)
    }
    $current = Get-CurrentSimulation
    [void](Wait-Until 30 { "$((Get-Listing).simulationId)" -eq $current })
    Assert-Equal "Market Data's default list: the current simulation's" $current "$((Get-Listing).simulationId)"
    $nova = Get-Nova
    if (-not (Wait-Until 120 { (Get-TradesBehind $nova) -eq 0 })) { throw "candles still '$(Get-TradesBehind $nova)' trades behind" }
    Assert-Reconciled @($novas | ForEach-Object { ($_ -split ' ')[1] })
}

function Get-EveryNovaTotals {
    # Execution's trade totals for every simulation's NOVA, in Get-EveryNova's order.
    return (@(Get-EveryNova | ForEach-Object { Get-ExecutionValue "$tradeTotals FROM trades WHERE instrument_id = '$(($_ -split ' ')[1])'" }) -join ' | ')
}

# Security scanners, pinned by digest. Their vulnerability database is downloaded on every run, so a vulnerability
# published today can fail a run with no change here; scripts/security-report.py says which findings fail and why.
$trivyImage = 'ghcr.io/aquasecurity/trivy@sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c'
$gitleaksImage = 'ghcr.io/gitleaks/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f'
$securityDir = Join-Path $runDir 'security'

function Invoke-Trivy {
    param([string[]]$Arguments)
    Invoke-Checked 'docker' (@('run', '--rm', '-v', '/var/run/docker.sock:/var/run/docker.sock', '-v', 'mse-trivy-cache:/root/.cache', '-v', "${root}:/repo", '-w', '/repo', $trivyImage) + $Arguments)
}

function Get-Headers {
    # A response's headers in lower case, one per line, whatever the status.
    param([string]$Path)
    return ((& curl.exe -s -m 10 -o NUL -D - "http://localhost:$webPort$Path") -join "`n").ToLower()
}

function Get-SessionCookie {
    # Signs in as the trader and returns the session cookie the login sets. The CSRF token belongs to the session,
    # so it is fetched as a plain-HTTP client would.
    param([hashtable]$Headers)
    $base = "http://localhost:$webPort/api/v1"
    $csrf = Invoke-RestMethod "$base/auth/csrf" -SessionVariable browser
    $body = @{ username = 'trader'; password = $traderPassword } | ConvertTo-Json
    $all = @{ $csrf.headerName = $csrf.token } + $Headers
    $login = Invoke-WebRequest "$base/auth/login" -Method Post -WebSession $browser -ContentType 'application/json' -Body $body -Headers $all -UseBasicParsing
    $cookie = @($login.Headers['Set-Cookie'] -split ',(?=\s*[A-Za-z_-]+=)' | Where-Object { $_.Trim().StartsWith('SESSION=') })
    if ($cookie.Count -ne 1) { throw "login set no single session cookie: $($login.Headers['Set-Cookie'])" }
    return $cookie[0].Trim()
}

function Assert-ClassVersion {
    param([string]$ClassPath, [string]$ClassName, [int]$Major)
    $output = & (Join-Path $env:JAVA_HOME 'bin\javap.exe') -v -cp $ClassPath $ClassName | Select-String 'major version'
    Assert-Match "$ClassName bytecode" "major version: $Major" $output
}

$failed = $false
try {
    Step 'Toolchain versions' {
        if (-not $env:JAVA_HOME) { throw 'JAVA_HOME is not set' }
        if (-not $env:JAVA17_HOME) { throw 'JAVA17_HOME is not set' }
        $javaProperties = & cmd /c "`"$env:JAVA_HOME\bin\java.exe`" -XshowSettings:properties -version 2>&1" | Out-String
        Assert-Match 'JAVA_HOME version' 'java\.version = 25\.0\.5' $javaProperties
        Assert-Match 'JAVA_HOME vendor' 'java\.vendor = Oracle' $javaProperties
        Assert-Match 'JAVA17_HOME' 'version "17\.' (& cmd /c "`"$env:JAVA17_HOME\bin\java.exe`" -version 2>&1" | Out-String)
        Assert-Match 'Maven wrapper' 'Apache Maven 3\.9\.16' (& .\mvnw.cmd -v | Out-String)
        Assert-Equal 'Node' 'v24.22.2' (& node --version)
        Assert-Match 'Docker server' '\d' (& docker version --format '{{.Server.Version}}')
    }

    Step 'Maven verify: services, contracts, Flink on JDK 17' {
        Invoke-Checked '.\mvnw.cmd' @('-B', '-ntp', 'clean', 'verify')
        Invoke-Checked '.\mvnw.cmd' @('-B', '-ntp', '-f', 'event-contracts/pom.xml', '-DskipTests', 'install')
        Assert-ClassVersion 'services\execution-service\target\classes' 'com.mia_princz.mse.execution.ExecutionServiceApplication' 69
        Assert-ClassVersion 'services\market-data-service\target\classes' 'com.mia_princz.mse.marketdata.MarketDataServiceApplication' 69
        Assert-ClassVersion 'analytics-job\target\analytics-job.jar' 'com.mia_princz.mse.analytics.AnalyticsJob' 61
        Assert-ClassVersion 'event-contracts\target\event-contracts-0.2.1.jar' 'com.mia_princz.mse.contracts.events.TradeExecuted' 61
    }

    Step 'Frontend: npm ci, API contract, typecheck, lint, test, build' {
        Push-Location web
        try {
            foreach ($arguments in @(@('ci'), @('run', 'api:check'), @('run', 'typecheck'), @('run', 'lint'), @('test'), @('run', 'build'))) {
                Invoke-Checked 'npm.cmd' $arguments
            }
        }
        finally { Pop-Location }
        if (-not (Test-Path 'web\dist\index.html')) { throw 'web/dist/index.html was not produced' }
    }

    Step 'Simulator: frozen uv sync, ruff, pytest' {
        Push-Location simulator
        try {
            Invoke-Checked 'uv' @('sync', '--frozen')
            Assert-Equal 'Python' 'Python 3.14.10' (& uv run --frozen python --version)
            Invoke-Checked 'uv' @('run', '--frozen', 'ruff', 'check', '.')
            Invoke-Checked 'uv' @('run', '--frozen', 'ruff', 'format', '--check', '.')
            Invoke-Checked 'uv' @('run', '--frozen', 'pytest', '-q')
        }
        finally { Pop-Location }
    }

    Step 'Compose: clean stack builds and turns healthy' {
        & docker @compose down -v --remove-orphans 2>&1 | Out-File -Append -Encoding utf8 -Width 400 $script:currentLog
        Invoke-Checked 'docker' ($compose + @('up', '-d', '--build', '--wait', '--wait-timeout', '480'))
    }

    Step 'Runtimes: Oracle JDK 25 services, Flink JDK 17' {
        foreach ($service in 'execution-service', 'market-data-service') {
            $version = Invoke-InContainer $service @('java', '-version')
            Assert-Match "$service JDK" 'version "25\.0\.5"' $version
            Assert-Match "$service vendor" 'Java\(TM\) SE' $version
        }
        Assert-Match 'Flink JDK' 'version "17\.' (Invoke-InContainer 'flink-taskmanager' @('java', '-version'))
        Assert-Match 'Flink job' '"state":"RUNNING"' (Invoke-InContainer 'flink-jobmanager' @('curl', '-fsS', 'http://localhost:8081/jobs/overview'))
        foreach ($service in 'execution-service', 'market-data-service', 'web', 'simulator', 'flink-taskmanager') {
            $uid = (Invoke-InContainer $service @('id', '-u')).Trim()
            "$service uid => $uid" | Add-Content -Encoding utf8 $script:currentLog
            if ($uid -eq '0') { throw "$service runs as root" }
        }
    }

    Step 'Security: no secrets, nothing fixable here in dependencies or images' {
        # Secrets in the whole git history; the npm and uv lockfiles and the Dockerfiles; every image Compose runs.
        # The Java dependencies are scanned in the images, as the jars that actually ship, so Maven Central isn't
        # queried.
        if (Test-Path $securityDir) { Remove-Item -Recurse -Force $securityDir }
        New-Item -ItemType Directory -Force $securityDir | Out-Null
        # In a worktree, .git is a file naming the git directory by a host path the container can't follow, so the
        # repository's git directory is mounted on its own. gitleaks reports no leaks when it can read no history,
        # so a scan that read no commits fails.
        $gitCommon = (& git -C $root rev-parse --path-format=absolute --git-common-dir).Trim()
        $gitDir = (& git -C $root rev-parse --path-format=absolute --git-dir).Trim()
        if ($LASTEXITCODE -ne 0 -or -not $gitDir.StartsWith($gitCommon)) { throw 'no git directory' }
        Invoke-Checked 'docker' @('run', '--rm', '-v', "${root}:/repo", '-v', "${gitCommon}:/git", '-e', "GIT_DIR=/git$($gitDir.Substring($gitCommon.Length))", '-e', 'GIT_WORK_TREE=/repo', '-e', 'GIT_CONFIG_COUNT=1', '-e', 'GIT_CONFIG_KEY_0=safe.directory', '-e', 'GIT_CONFIG_VALUE_0=*', $gitleaksImage, 'git', '/repo', '--redact', '--no-banner', '--exit-code', '0', '--report-format', 'json', '--report-path', '/repo/runtime/compat-gate/security/gitleaks.json')
        $scanned = Select-String -Path $script:currentLog -Pattern '(\d+) commits scanned' | Select-Object -Last 1
        if ($null -eq $scanned -or [int]$scanned.Matches[0].Groups[1].Value -eq 0) { throw 'gitleaks read no commits' }
        Invoke-Trivy @('fs', '.', '--scanners', 'vuln,misconfig', '--ignorefile', '.trivyignore.yaml', '--skip-files', '**/pom.xml', '--skip-dirs', 'runtime', '--skip-dirs', 'local-docs', '--skip-dirs', '**/node_modules', '--skip-dirs', '**/target', '--skip-dirs', '**/.venv', '--skip-dirs', 'web/dist', '--skip-dirs', 'web/test-results', '--format', 'json', '--output', 'runtime/compat-gate/security/trivy-repo.json', '--quiet')
        foreach ($image in (& docker @($compose + @('config', '--images')) | Sort-Object -Unique)) {
            if ($image -match '@sha256:') { $name = 'third-party-' + (($image -split '@')[0] -replace '[/:]', '-') }
            else { $name = 'own-' + ($image -replace '[/:]', '-') }
            Invoke-Trivy @('image', $image, '--scanners', 'vuln', '--format', 'json', '--output', "runtime/compat-gate/security/image-$name.json", '--quiet')
        }
        & uv run --project simulator --frozen python scripts/security-report.py $securityDir 2>&1 | Out-File -Append -Encoding utf8 -Width 400 $script:currentLog
        if ($LASTEXITCODE -ne 0) { throw "something to fix here; see $securityDir\summary.md" }
    }

    Step 'Kafka topics: partitions and retention' {
        $describe = Invoke-InContainer 'kafka' @('/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'localhost:9092', '--describe')
        Assert-Match 'mse.trades.v1' 'Topic: mse\.trades\.v1\s.*PartitionCount: 3\s.*retention\.ms=604800000' $describe
        Assert-Match 'mse.market.v1' 'Topic: mse\.market\.v1\s.*PartitionCount: 3\s.*cleanup\.policy=compact' $describe
        Assert-Match 'mse.candles.v1' 'Topic: mse\.candles\.v1\s.*PartitionCount: 3\s.*retention\.ms=604800000' $describe
    }

    Step 'Migrations: each service owns its own history' {
        Assert-Sql 'execution_app' 'execution_db' 'SELECT service_name FROM service_identity' 'execution-service'
        Assert-Sql 'market_data_app' 'market_data_db' 'SELECT service_name FROM service_identity' 'market-data-service'
        Assert-Sql 'execution_owner' 'execution_db' "SELECT DISTINCT installed_by || ':' || success FROM flyway_schema_history" 'execution_owner:true'
        Assert-Sql 'market_data_owner' 'market_data_db' "SELECT DISTINCT installed_by || ':' || success FROM flyway_schema_history" 'market_data_owner:true'
        Assert-Sql 'execution_owner' 'execution_db' "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner <> 'execution_owner'" '0'
        Assert-Sql 'market_data_owner' 'market_data_db' "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner <> 'market_data_owner'" '0'
        Assert-Sql 'market_data_owner' 'market_data_db' "SELECT count(*) FROM pg_tables WHERE tablename = 'spring_session'" '0'
    }

    Step 'Database isolation: cross-service access denied' {
        foreach ($role in 'execution_owner', 'execution_app') { Assert-Denied $role 'market_data_db' 'SELECT 1' }
        foreach ($role in 'market_data_owner', 'market_data_app') { Assert-Denied $role 'execution_db' 'SELECT 1' }
        foreach ($role in 'execution_owner', 'execution_app', 'market_data_owner', 'market_data_app') { Assert-Denied $role 'postgres' 'SELECT 1' }
        Assert-Denied 'execution_app' 'execution_db' 'CREATE TABLE intruder (id INT)'
        Assert-Denied 'market_data_app' 'market_data_db' 'CREATE TABLE intruder (id INT)'
        Assert-Denied 'execution_app' 'execution_db' 'DELETE FROM flyway_schema_history'
        Assert-Denied 'market_data_app' 'market_data_db' 'DELETE FROM flyway_schema_history'
        Assert-Denied 'execution_owner' 'execution_db' 'CREATE EXTENSION dblink'
        Assert-Denied 'market_data_owner' 'market_data_db' 'CREATE EXTENSION dblink'
    }

    Step 'Proxy: routes by path to the owning service' {
        Assert-Equal '/healthz' '200' (Get-Http '/healthz')
        # Started after both services, the proxy found them on its first look-up.
        Assert-Equal 'proxy look-ups that failed at startup' '0' ((& docker @($compose + @('logs', '--no-log-prefix', 'web')) 2>&1 | Select-String 'could not be resolved' | Measure-Object).Count)
        Assert-Equal '/ (SPA)' '200' (Get-Http '/')
        Assert-Equal '/deep/link (SPA fallback)' '200' (Get-Http '/deep/link')
        Assert-Equal '/api/v1/orders (Execution, denied)' '401' (Get-Http '/api/v1/orders')
        Assert-Equal '/api/v1/me/tour (Execution, denied)' '401' (Get-Http '/api/v1/me/tour')
        Assert-Match '/api/v1/instruments (Market Data, public)' '"instruments":\[' ((& curl.exe -s -m 10 "http://localhost:$webPort/api/v1/instruments") | Out-String)
        Assert-Equal '/api/v1/instruments/x/orders (Market Data, denied)' '403' (Get-Http '/api/v1/instruments/x/orders')
        Assert-Match '/api/v1/market-events, no market yet (Market Data)' '"code":"INSTRUMENT_NOT_FOUND"' ((& curl.exe -s -m 10 "http://localhost:$webPort/api/v1/market-events?instrumentId=00000000-0000-0000-0000-000000000001") | Out-String)
        Assert-Match '/api/v1/instruments/{id}/market-quality, no market yet (Market Data, public)' '"code":"INSTRUMENT_NOT_FOUND"' ((& curl.exe -s -m 10 "http://localhost:$webPort/api/v1/instruments/00000000-0000-0000-0000-000000000001/market-quality?fromSequence=1&toSequence=1") | Out-String)
        Assert-Equal '/api/v1/internal/x (blocked at proxy)' '404' (Get-Http '/api/v1/internal/x')
        Assert-Match '/internal/exports/trades serves the SPA, not the export' '^<!doctype html>' ((& curl.exe -s "http://localhost:$webPort/internal/exports/trades?instrumentId=00000000-0000-0000-0000-000000000001") | Out-String)
        Assert-Match '/internal/simulator/assignment serves the SPA, not the assignment' '^<!doctype html>' ((& curl.exe -s "http://localhost:$webPort/internal/simulator/assignment") | Out-String)
        $actuator = (& curl.exe -s "http://localhost:$webPort/actuator/health") | Out-String
        Assert-Match '/actuator/health serves the SPA, not Actuator' '^\s*<!doctype html>' $actuator
    }

    Step 'Login: session and CSRF through the proxy' {
        # Traders are added by the operator during setup, as on the setup screen; the dev trader first, to sign in as.
        $operatorSession = New-ApiSession 'operator' $operatorPassword
        # The operator also trades: one session holds both roles.
        Assert-Equal 'operator roles' 'OPERATOR TRADER' ((Invoke-RestMethod "$api/me" -WebSession $operatorSession).roles -join ' ')
        $added =Invoke-ApiCommand $operatorSession '/admin/traders' (@{ username = 'trader'; password = $traderPassword } | ConvertTo-Json)
        Assert-Equal 'add the dev trader' 201 $added.Status
        $base = "http://localhost:$webPort/api/v1"
        $csrf = Invoke-RestMethod "$base/auth/csrf" -SessionVariable browser
        $body = @{ username = 'trader'; password = $traderPassword } | ConvertTo-Json
        $login = Invoke-RestMethod "$base/auth/login" -Method Post -WebSession $browser -ContentType 'application/json' -Body $body -Headers @{ $csrf.headerName = $csrf.token }
        Assert-Equal 'login user' 'trader' $login.username
        Assert-Equal 'me' 'TRADER' (Invoke-RestMethod "$base/me" -WebSession $browser).roles
    }

    Step 'Security: headers, the Secure cookie, ports on localhost only' {
        # What a browser and the network see: security headers on every kind of response, the session cookie Secure
        # exactly when the client used HTTPS, and nothing published beyond localhost.
        foreach ($path in '/', '/api/v1/instruments', '/api/v1/nowhere') {
            $headers = Get-Headers $path
            Assert-Match "$path content security policy" "(?m)^content-security-policy: default-src 'self';.*frame-ancestors 'none'" $headers
            Assert-Equal "$path nosniff, once" '1' ([regex]::Matches($headers, '(?m)^x-content-type-options: nosniff\r?$').Count)
            Assert-Equal "$path never framed, once" '1' ([regex]::Matches($headers, '(?m)^x-frame-options: deny\r?$').Count)
            Assert-Match "$path referrer policy" '(?m)^referrer-policy: no-referrer\r?$' $headers
            if ($headers -match '(?m)^server: nginx/') { throw "$path names the nginx version" }
        }
        $secure = Get-SessionCookie @{ 'X-Forwarded-Proto' = 'https' }
        Assert-Match 'session cookie behind HTTPS' '; Secure; HttpOnly; SameSite=Lax' $secure
        $plain = Get-SessionCookie @{}
        "session cookie over plain HTTP => $plain" | Add-Content -Encoding utf8 $script:currentLog
        if ($plain -match 'Secure') { throw "session cookie over plain HTTP is Secure: $plain" }
        # Failed sign-ins are limited per username: five guesses for a name that doesn't exist, then the sixth waits.
        $base = "http://localhost:$webPort/api/v1"
        foreach ($guess in 1..6) {
            $csrf = Invoke-RestMethod "$base/auth/csrf" -SessionVariable prober
            $body = @{ username = 'limit-probe'; password = "guess-$guess" } | ConvertTo-Json
            try {
                $answer = Invoke-WebRequest "$base/auth/login" -Method Post -WebSession $prober -ContentType 'application/json' -Body $body -Headers @{ $csrf.headerName = $csrf.token } -UseBasicParsing
                $status = [int]$answer.StatusCode; $retry = $answer.Headers['Retry-After']
            }
            catch {
                if ($null -eq $_.Exception.Response) { throw }
                $status = [int]$_.Exception.Response.StatusCode; $retry = $_.Exception.Response.Headers['Retry-After']
            }
        }
        Assert-Equal 'the sixth guess is refused' '429' $status
        Assert-Match 'the sixth guess waits' '^[0-9]+$' $retry
        # Every published port is bound to localhost; only the proxy is meant to be reached, and only from this machine.
        $config = (& docker @($compose + @('config', '--format', 'json'))) -join "`n" | ConvertFrom-Json
        $ports = @(foreach ($service in $config.services.PSObject.Properties) {
                foreach ($port in @($service.Value.ports)) { if ($null -ne $port) { "$($service.Name)=$($port.host_ip):$($port.published)" } }
            })
        "published ports => $($ports -join ' ')" | Add-Content -Encoding utf8 $script:currentLog
        if ($ports.Count -eq 0) { throw 'no published ports found' }
        $open = @($ports | Where-Object { $_ -notmatch '=127\.0\.0\.1:' })
        if ($open.Count -gt 0) { throw "published beyond localhost: $($open -join ' ')" }
    }

    Step 'Independence: each service survives the other stopping' {
        Invoke-Checked 'docker' ($compose + @('stop', 'market-data-service'))
        Assert-Match 'instruments with Market Data down' '^50[24]$' (Get-Http '/api/v1/instruments')
        Assert-Equal 'orders with Market Data down' '401' (Get-Http '/api/v1/orders')
        Assert-Match 'Execution readiness' '"status":"UP"' (Get-Readiness 'execution-service')
        Invoke-Checked 'docker' ($compose + @('start', 'market-data-service'))
        Invoke-Checked 'docker' ($compose + @('up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', 'market-data-service'))

        Invoke-Checked 'docker' ($compose + @('stop', 'execution-service'))
        Assert-Match 'orders with Execution down' '^50[24]$' (Get-Http '/api/v1/orders')
        Assert-Equal 'instruments with Execution down' '200' (Get-Http '/api/v1/instruments')
        Assert-Match 'Market Data readiness' '"status":"UP"' (Get-Readiness 'market-data-service')
        Invoke-Checked 'docker' ($compose + @('start', 'execution-service'))
        Invoke-Checked 'docker' ($compose + @('up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', 'execution-service'))
    }

    Step 'Browser: trading, halt, resume, bot runs and every screen' {
        # The tests add their other traders through the operator API, in Playwright's global setup.
        Push-Location web
        try {
            Invoke-Checked 'npx.cmd' @('playwright', 'install', 'chromium')
            $env:E2E_BASE_URL = "http://localhost:$webPort"
            $env:E2E_TRADER_PASSWORD = $traderPassword
            $env:E2E_OPERATOR_PASSWORD = $operatorPassword
            $env:E2E_COUNTERPARTY_PASSWORD = $counterpartyPassword
            Invoke-Checked 'npm.cmd' @('run', 'e2e')
        }
        finally {
            Remove-Item Env:E2E_BASE_URL, Env:E2E_TRADER_PASSWORD, Env:E2E_OPERATOR_PASSWORD, Env:E2E_COUNTERPARTY_PASSWORD -ErrorAction SilentlyContinue
            Pop-Location
        }
    }

    Step 'Bot tokens: owner-only files, and the simulator control token' {
        # The browser test's setup provisioned two bots; each token is a file only its owner can read.
        Assert-Equal 'token directory' '700 mse' (Invoke-InContainer 'execution-service' @('stat', '-c', '%a %U', '/run/mse/bot-tokens'))
        # No double quotes inside these sh commands: Windows PowerShell 5.1 strips them from native arguments.
        Assert-Match 'token files' '^\s*2 600 mse\s*$' (Invoke-InContainer 'execution-service' @('sh', '-c', "stat -c '%a %U' /run/mse/bot-tokens/*.token | uniq -c"))
        # Tokens are read inside the container, so they never reach this shell.
        $assignment = { param($variable) "curl -s -o /dev/null -w '%{http_code}' -H Authorization:\ Bearer\ `$$variable http://localhost:8080/internal/simulator/assignment" }
        Assert-Equal 'the simulator token opens its assignment' '200' (Invoke-InContainer 'execution-service' @('sh', '-c', (& $assignment 'MSE_SIMULATOR_TOKEN')))
        Assert-Equal 'the export token does not' '403' (Invoke-InContainer 'execution-service' @('sh', '-c', (& $assignment 'MSE_EXPORT_TOKEN')))
    }

    Step 'Bots: the simulator trades and the tape shows it' {
        # The operator starts a run for the two bots the browser test provisioned: a market maker and a noise bot.
        # The simulator follows it, their trades reach the public market data, and a stop ends with the stop confirmed
        # and no bot order left open: the maker's resting quotes are cancelled.
        $base = "http://localhost:$webPort/api/v1"
        $csrf = Invoke-RestMethod "$base/auth/csrf" -SessionVariable operator
        $body = @{ username = 'operator'; password = $operatorPassword } | ConvertTo-Json
        [void](Invoke-RestMethod "$base/auth/login" -Method Post -WebSession $operator -ContentType 'application/json' -Body $body -Headers @{ $csrf.headerName = $csrf.token })
        $csrf = Invoke-RestMethod "$base/auth/csrf" -WebSession $operator
        # The browser test's run left bot trades behind; only a newer one shows this run trading.
        $botTrades = "SELECT coalesce(max(t.trade_sequence), 0) FROM trades t JOIN account b ON b.id = t.buyer_account_id JOIN account s ON s.id = t.seller_account_id WHERE b.kind = 'BOT' OR s.kind = 'BOT'"
        $before = Invoke-Psql 'execution_owner' $passwords.execution_owner 'execution_db' $botTrades
        Assert-Match 'bot trades before the run' '^[0-9]+$' $before.Output
        $before = [long]$before.Output
        $headers = @{ $csrf.headerName = $csrf.token; 'Idempotency-Key' = [guid]::NewGuid().ToString() }
        $started = Invoke-RestMethod "$base/admin/bot-runs" -Method Post -WebSession $operator -ContentType 'application/json' -Headers $headers -Body '{"marketMakers":1,"noise":1,"seed":7,"commandsPerSecond":"4.00"}'
        Assert-Match 'bot run started' '^[0-9a-f-]{36}$' $started.runId
        $sequence = 0
        foreach ($attempt in 1..60) {
            $result = Invoke-Psql 'execution_owner' $passwords.execution_owner 'execution_db' $botTrades
            if ($result.Code -eq 0 -and [long]$result.Output -gt $before) { $sequence = [long]$result.Output; break }
            Start-Sleep -Seconds 2
        }
        Assert-Match 'latest bot trade sequence' '^[1-9][0-9]*$' $sequence
        if (-not ($sequence -gt $before)) { throw "no bot trade after $before; latest $sequence" }
        $published = 0
        foreach ($attempt in 1..30) {
            $listing = (& curl.exe -s -m 10 "http://localhost:$webPort/api/v1/instruments" | Out-String)
            $published = ([regex]::Matches($listing, '"lastTradeSequence":"(\d+)"') | ForEach-Object { [long]$_.Groups[1].Value } | Measure-Object -Maximum).Maximum
            if ($published -ge $sequence) { break }
            Start-Sleep -Seconds 2
        }
        if (-not ($published -ge $sequence)) { throw "public tape at '$published', bot trade at $sequence" }
        # While the run trades, the operator's health summary, through the proxy, counts the last minute's commands.
        $health = Invoke-RestMethod "$base/admin/health" -WebSession $operator
        Assert-Match 'health: commands in the last minute' '^[1-9][0-9]*$' "$($health.lastMinute.committed)"
        Assert-Equal 'health: writer available' 'True' "$($health.writer.available)"
        Assert-Equal 'health: outbox admitting' 'True' "$($health.outbox.admitting)"
        # The run's window so far, measured from the public market data while the market maker quotes both sides of
        # NOVA. Measured before the stop: the stop cancels its quotes, so the minute that ends the run is one-sided.
        $window = (Get-ExecutionValue "SELECT b.start_sequence || '&toSequence=' || s.last_command_sequence FROM bot_run b JOIN simulation s ON s.id = b.simulation_id WHERE b.id = '$($started.runId)'")
        $quality = "http://localhost:$webPort/api/v1/instruments/$(Get-Nova)/market-quality?fromSequence=$window"
        $measured = ''
        foreach ($attempt in 1..15) {
            $measured = (& curl.exe -s -m 10 $quality | Out-String)
            if ($measured -match '"twoSidedMinutes":[1-9]') { break }
            Start-Sleep -Seconds 2
        }
        "market quality $window => $measured" | Add-Content -Encoding utf8 $script:currentLog
        Assert-Match "the run's window has two-sided minutes" '"twoSidedMinutes":[1-9]' $measured
        Assert-Match "the run's window has an average spread" '"averageSpread":"[0-9]+\.[0-9]{4}"' $measured
        $headers = @{ $csrf.headerName = $csrf.token; 'Idempotency-Key' = [guid]::NewGuid().ToString() }
        [void](Invoke-RestMethod "$base/admin/bot-runs/$($started.runId)/stop" -Method Post -WebSession $operator -Headers $headers)
        $status = ''
        foreach ($attempt in 1..30) {
            $status = (Invoke-RestMethod "$base/admin/bot-runs?limit=1" -WebSession $operator).runs[0].status
            if ($status -eq 'STOPPED') { break }
            Start-Sleep -Seconds 2
        }
        Assert-Equal 'run status after the stop' 'STOPPED' $status
        $confirmed = Invoke-Psql 'execution_owner' $passwords.execution_owner 'execution_db' "SELECT stopped_at IS NOT NULL AND placed > 0 FROM bot_run WHERE id = '$($started.runId)'"
        Assert-Equal 'the worker confirmed it, after placing orders' 't' $confirmed.Output
        # With the run, the worker reported the commit this gate built its image from, once.
        $manifest = (Invoke-RestMethod "$base/admin/bot-runs?limit=1" -WebSession $operator).runs[0].manifest
        "manifest => $($manifest | ConvertTo-Json -Compress -Depth 5)" | Add-Content -Encoding utf8 $script:currentLog
        Assert-Match 'the worker reports the commit built' ('\+' + [regex]::Escape($revision) + '$') "$($manifest.version)"
        Assert-Equal 'one manifest for the run' '1' "$($manifest.reports)"
        $open = Invoke-Psql 'execution_owner' $passwords.execution_owner 'execution_db' "SELECT count(*) FROM orders o JOIN account a ON a.id = o.account_id WHERE a.kind = 'BOT' AND o.state IN ('OPEN', 'PARTIALLY_FILLED')"
        Assert-Equal 'bot orders left open' '0' $open.Output
        # The bots' commands were timed: Prometheus has a bucket edge at each latency budget, and the queue's size.
        $scrape = (Invoke-InContainer 'execution-service' @('curl', '-fsS', 'http://localhost:8080/actuator/prometheus')) -split "`n" |
            Where-Object { $_ -match '^mse_(commands_seconds_bucket\{command="place",outcome="committed",le="0\.(15|5)"\}|writer_queue_capacity) ' }
        $scrape = $scrape -join "`n"
        Assert-Match 'place latency bucket at 150 ms' 'le="0\.15"\} [1-9]' $scrape
        Assert-Match 'place latency bucket at 500 ms' 'le="0\.5"\} [1-9]' $scrape
        Assert-Match 'writer queue capacity' 'mse_writer_queue_capacity 256' $scrape
    }

    Step 'Account stream: a trader hears their own changes through the proxy' {
        # Through the proxy, as a browser would: the trader's stream opens with a frame, and an order placed and
        # cancelled far below the market is heard at its own command sequence. Proves the route and that nothing
        # buffers the frames.
        $trader = New-ApiSession 'trader' $traderPassword
        $cookie = $trader.Cookies.GetCookies("http://localhost:$webPort")['SESSION'].Value
        $out = Join-Path $runDir 'account-stream.txt'
        # One argument string, quoted by hand: Windows PowerShell 5.1 joins an argument array with spaces and no
        # quotes, which would split each header in two.
        $arguments = '-s -N -m 6 -H "Cookie: SESSION=' + $cookie + '" -H "Accept: text/event-stream" ' + "$api/account-events"
        $reader = Start-Process curl.exe -ArgumentList $arguments -RedirectStandardOutput $out -NoNewWindow -PassThru
        Start-Sleep -Seconds 1
        $placed = Invoke-ApiCommand $trader '/orders' (New-LimitOrder (Get-Nova) 'BUY' '1.00')
        Assert-Equal 'a resting buy far below the market' '201' $placed.Status
        $sequence = ($placed.Body | ConvertFrom-Json).commandSequence
        $order = ($placed.Body | ConvertFrom-Json).orderId
        Assert-Equal 'cancelled again' '200' (Invoke-ApiCommand $trader "/orders/$order/cancel").Status
        $reader.WaitForExit()
        $stream = Get-Content -Raw $out
        "stream => $stream" | Add-Content -Encoding utf8 $script:currentLog
        Assert-Match 'a frame on connecting' 'event:account' $stream
        Assert-Match "a frame at the order's sequence $sequence" ('"accountVersion":"' + $sequence + '"') $stream
    }

    Step 'Avro: JDK 25 > Kafka > Flink JDK 17 > Kafka > JDK 25' {
        $script:smokeSimulation = [guid]::NewGuid().ToString()
        $script:smokeMinute = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:00Z')
        Invoke-Smoke 'services/execution-service/pom.xml' 'TradePublishSmoke' 'first'
        Invoke-Smoke 'services/market-data-service/pom.xml' 'CandleConsumeSmoke' 'first'
    }

    Step 'Analytics: a JobManager restart resumes from its checkpoint' {
        # A checkpoint that holds the Avro step's trades, then a JobManager restart that must resume from the newest
        # one: Flink records the restore and its path, and the next trade continues the same minute's candle exactly.
        $job = Get-FlinkJob
        $completed = Get-FlinkCount $job 'completed'
        # Two more completed checkpoints: the second was triggered after the trades were processed.
        foreach ($attempt in 1..30) {
            if ((Get-FlinkCount $job 'completed') -ge $completed + 2) { break }
            Start-Sleep -Seconds 2
        }
        $latest = [regex]::Match((Get-FlinkJson "/jobs/$job/checkpoints"), '"latest":\{"completed":\{[^}]*?"id":(\d+)')
        if (-not $latest.Success) { throw 'no completed checkpoint after the trades' }
        Invoke-Checked 'docker' ($compose + @('restart', 'flink-jobmanager'))
        foreach ($attempt in 1..60) {
            $restored = Get-FlinkJob
            if ($restored -ne '' -and (Get-FlinkCount $restored 'restored') -eq 1) { break }
            Start-Sleep -Seconds 2
        }
        # Restoring an older checkpoint would also give correct candles, after a longer replay, so the path is checked too.
        $restore = [regex]::Match((Get-FlinkJson "/jobs/$restored/checkpoints"), '"restored":\{"id":(\d+)[^}]*"external_path":"file:/opt/flink/checkpoints/([0-9a-f]+)/chk-')
        if (-not $restore.Success -or $restore.Groups[2].Value -ne $job -or [long]$restore.Groups[1].Value -lt [long]$latest.Groups[1].Value) {
            throw "the restore was not the newest checkpoint of job $job"
        }
        $entry = (& docker @($compose + @('logs', '--no-log-prefix', 'flink-jobmanager')) 2>&1 | Select-String 'the analytics job' | Select-Object -Last 1)
        Assert-Match 'entry point' 'Resuming the analytics job from checkpoint /opt/flink/checkpoints/' "$entry"
        Assert-Match 'checkpoint directories: the one resumed from and the running job' '^\s*[12]\s*$' (Invoke-InContainer 'flink-jobmanager' @('sh', '-c', 'ls /opt/flink/checkpoints | wc -l'))
        Invoke-Smoke 'services/execution-service/pom.xml' 'TradePublishSmoke' 'after-restart'
        Invoke-Smoke 'services/market-data-service/pom.xml' 'CandleConsumeSmoke' 'after-restart'
    }

    Step 'Candles: Market Data stores and serves the bars' {
        # The restart step's smoke candle, as Market Data stored it: four trades, 15 shares, notional 1,513.00.
        $sql = "SELECT trade_count || ' ' || volume || ' ' || notional_cents || ' ' || source_max_trade_sequence FROM candle_1m WHERE simulation_id = '$($script:smokeSimulation)'"
        foreach ($attempt in 1..30) {
            $stored = (Invoke-Psql 'market_data_app' $passwords.market_data_app 'market_data_db' $sql).Output
            if ($stored -eq '4 15 151300 4') { break }
            Start-Sleep -Seconds 2
        }
        Assert-Equal 'smoke candle in candle_1m: trades, volume, notional, sequence' '4 15 151300 4' $stored
        # And through the proxy's public API, with the VWAP worked out: 1,513.00 / 15 = 100.8667, rounded to 100.87.
        $instrument = (Invoke-Psql 'market_data_app' $passwords.market_data_app 'market_data_db' "SELECT instrument_id FROM candle_1m WHERE simulation_id = '$($script:smokeSimulation)'").Output
        $bars = (& curl.exe -s -m 10 "http://localhost:$webPort/api/v1/instruments/$instrument/candles?from=$($script:smokeMinute)" | Out-String)
        Assert-Match 'smoke candle from the candles API' '"tradeCount":4,"notional":"1513\.00","vwap":"100\.87"' $bars
    }

    Step 'Reconciliation: candles match the trade export exactly' {
        # Every instrument Execution has, its candles checked against the trade export: the trades from the browser
        # test and the bots, computed by the job across its restart. Exact, or the step fails with the mismatches.
        Assert-Reconciled
    }

    Step 'Dashboard: Grafana loads it and every query works' {
        # Grafana provisions the dashboard from the repository; every query on it must work against the live stack.
        $env:GRAFANA_ADMIN_PASSWORD = $grafanaPassword
        try {
            Invoke-Checked 'uv' @('run', '--project', 'simulator', '--frozen', 'python', 'scripts/check-dashboard.py', 'http://localhost:29090', 'http://localhost:23000')
        }
        finally {
            Remove-Item Env:GRAFANA_ADMIN_PASSWORD -ErrorAction SilentlyContinue
        }
    }

    Step 'Flink outage: trading goes on, candles catch up' {
        # With the analytics job down, the bots keep trading: their trades commit and reach the public tape, a trader
        # places and cancels, and balances read as usual. Only the candles fall behind, and they say so. The job then
        # resumes from its checkpoint, and the candles catch up and reconcile exactly.
        $operator = New-ApiSession 'operator' $operatorPassword
        $trader = New-ApiSession 'trader' $traderPassword
        $nova = Get-Nova
        $before = Get-LatestBotTrade
        $run = Start-BotRun $operator 13
        if (-not (Wait-Until 60 { (Get-LatestBotTrade) -gt $before })) { throw "no bot trade after $before" }
        Invoke-Checked 'docker' ($compose + @('stop', 'flink-taskmanager', 'flink-jobmanager'))
        $before = Get-LatestBotTrade
        if (-not (Wait-Until 60 { (Get-LatestBotTrade) -gt $before })) { throw "no bot trade after $before with Flink down" }
        $during = Get-LatestBotTrade
        if (-not (Wait-Until 30 { (Get-Published) -ge $during })) { throw "public tape at '$(Get-Published)', bot trade at $during" }
        Invoke-TraderRoundTrip $trader
        if (-not (Wait-Until 30 { (Get-TradesBehind $nova) -gt 0 })) { throw "candles say '$(Get-TradesBehind $nova)' trades behind" }
        "candles behind with Flink down => $(Get-TradesBehind $nova)" | Add-Content -Encoding utf8 $script:currentLog
        Stop-BotRun $operator $run
        Invoke-Checked 'docker' ($compose + @('start', 'flink-jobmanager', 'flink-taskmanager'))
        if (-not (Wait-Until 120 { (Get-TradesBehind $nova) -eq 0 })) {
            throw "candles still '$(Get-TradesBehind $nova)' trades behind after the restart"
        }
        Assert-Reconciled
    }

    Step 'Close and start again: history kept, a new market trades' {
        # The operator closes the market while people's orders rest and a bot run trades. Every resting order is
        # cancelled and its hold released, the run ends with the close, the bots' tokens stop working, nothing more
        # trades, and the trader still reads their portfolio. Then the operator starts a second simulation by name,
        # sets it up again with the same symbol, the same traders and new bots, opens it, and people and bots trade in
        # it, with sequences from 1. The steps after this one run with both simulations in the database.
        $operator = New-ApiSession 'operator' $operatorPassword
        $trader = New-ApiSession 'trader' $traderPassword
        $counterparty = New-ApiSession 'counterparty' $counterpartyPassword
        $closed = Get-CurrentSimulation
        Assert-Equal 'the current simulation, by its row and by the market status' $closed (Invoke-RestMethod "$api/market" -WebSession $operator).simulationId
        $oldNova = Get-Nova
        $bid = Invoke-ApiCommand $trader '/orders' (New-LimitOrder $oldNova 'BUY' '1.00')
        Assert-Equal 'the trader bids far below the market' '201' $bid.Status
        $offer = Invoke-ApiCommand $counterparty '/orders' (New-LimitOrder $oldNova 'SELL' '500.00')
        Assert-Equal 'the counterparty offers far above it' '201' $offer.Status
        $people = "'$(($bid.Body | ConvertFrom-Json).orderId)', '$(($offer.Body | ConvertFrom-Json).orderId)'"
        $cash = "SELECT sum(c.total_cents) || ' ' || sum(c.reserved_cents) FROM cash_balance c JOIN account a ON a.id = c.account_id WHERE a.simulation_id = '$closed'"
        $shares = "SELECT sum(p.total_quantity) || ' ' || sum(p.reserved_quantity) FROM position p JOIN account a ON a.id = p.account_id WHERE a.simulation_id = '$closed'"
        # A bot's token, copied inside the container while it still works, to send again once the close revoked it.
        [void](Invoke-InContainer 'execution-service' @('sh', '-c', 'umask 077; cp $(ls /run/mse/bot-tokens/*.token | head -n 1) /tmp/closed-bot.token'))
        Assert-Equal 'a bot read before the close' '200' (Invoke-InContainer 'execution-service' @('sh', '-c', $copiedBotRead))
        $before = Get-LatestBotTrade
        $run = Start-BotRun $operator 19
        if (-not (Wait-Until 60 { (Get-LatestBotTrade) -gt $before })) { throw "no bot trade after $before" }
        $cashBefore = Get-ExecutionValue $cash
        $sharesBefore = Get-ExecutionValue $shares
        Assert-Match 'cash in total, and held for orders, before the close' '^[1-9][0-9]* [1-9][0-9]*$' $cashBefore
        Assert-Match 'shares in total, and held for orders, before the close' '^[1-9][0-9]* [1-9][0-9]*$' $sharesBefore
        "bot orders resting before the close => $(Get-ExecutionValue "SELECT count(*) FROM orders o JOIN account a ON a.id = o.account_id WHERE o.simulation_id = '$closed' AND a.kind = 'BOT' AND o.state IN ('OPEN', 'PARTIALLY_FILLED')")" | Add-Content -Encoding utf8 $script:currentLog

        $close = Invoke-ApiCommand $operator '/admin/market/close'
        Assert-Equal 'the operator closes the market' '200' $close.Status
        $answer = $close.Body | ConvertFrom-Json
        Assert-Equal 'runs the close ended' '1' $answer.endedRuns
        Assert-Equal 'orders still resting' '0' (Get-ExecutionValue "SELECT count(*) FROM orders WHERE simulation_id = '$closed' AND state IN ('OPEN', 'PARTIALLY_FILLED')")
        Assert-Equal 'orders cancelled with MARKET_CLOSED, as many as the close says' "$($answer.canceledOrders)" (Get-ExecutionValue "SELECT count(*) FROM orders WHERE simulation_id = '$closed' AND state = 'CANCELED' AND reason = 'MARKET_CLOSED'")
        Assert-Equal "the people's two orders among them" 'CANCELED MARKET_CLOSED,CANCELED MARKET_CLOSED' (Get-ExecutionValue "SELECT string_agg(state || ' ' || reason, ',') FROM orders WHERE id IN ($people)")
        Assert-Equal 'cash in total as before, none held' "$(($cashBefore -split ' ')[0]) 0" (Get-ExecutionValue $cash)
        Assert-Equal 'shares in total as before, none held' "$(($sharesBefore -split ' ')[0]) 0" (Get-ExecutionValue $shares)
        Assert-Match "the closed simulation's balances match its ledger: differing, compared" '^0 [1-9][0-9]*$' (Get-LedgerCheck $closed)
        if (-not (Wait-Until 15 { $null -ne (Invoke-RestMethod "$api/admin/bot-runs/$run" -WebSession $operator).figures })) { throw "run $run has no figures" }
        $detail = Invoke-RestMethod "$api/admin/bot-runs/$run" -WebSession $operator
        Assert-Equal "the run: ended by the close, at its sequence" "STOPPED MARKET_CLOSE $($answer.commandSequence)" "$($detail.run.status) $($detail.run.window.endedBy) $($detail.run.window.endSequence)"
        Assert-Equal "the run's figures, per strategy" 'MARKET_MAKER NOISE' ((@($detail.figures.strategies | ForEach-Object { $_.strategy }) | Sort-Object) -join ' ')
        Assert-Match "the closed simulation's bot tokens: unrevoked, of all" '^0 [2-9][0-9]*$' (Get-ExecutionValue "SELECT count(*) FILTER (WHERE t.revoked_at IS NULL) || ' ' || count(*) FROM api_token t JOIN account a ON a.id = t.account_id WHERE a.simulation_id = '$closed'")
        Assert-Equal 'a bot read after the close' '401' (Invoke-InContainer 'execution-service' @('sh', '-c', $copiedBotRead))
        [void](Invoke-InContainer 'execution-service' @('rm', '-f', '/tmp/closed-bot.token'))
        $refused = Invoke-ApiCommand $trader '/orders' (New-LimitOrder $oldNova 'BUY' '1.00')
        Assert-Equal 'an order after the close' '422' $refused.Status
        Assert-Match 'is refused as final' '"code":"MARKET_CLOSED"' $refused.Body
        $refused = Invoke-ApiCommand $operator '/admin/bot-runs' '{"marketMakers":1,"noise":1,"seed":21,"commandsPerSecond":"4.00"}'
        Assert-Equal 'a bot run after the close' '409' $refused.Status
        Assert-Match 'is refused as final' '"code":"MARKET_CLOSED"' $refused.Body
        $status = Invoke-RestMethod "$api/market" -WebSession $operator
        Assert-Equal 'the market status' "CLOSED $closed" "$($status.state) $($status.simulationId)"
        [void](Wait-Until 15 { (Get-ListedNova $closed) -eq "$oldNova CLOSED" })
        Assert-Equal "NOVA's venue state at Market Data" "$oldNova CLOSED" (Get-ListedNova $closed)
        $portfolio = Invoke-RestMethod "$api/portfolio" -WebSession $trader
        $holding = @($portfolio.holdings | Where-Object { $_.instrumentId -eq $oldNova })[0]
        Assert-Equal "the trader's final portfolio: cash, reserved, NOVA, reserved" `
            (Get-ExecutionValue "SELECT (c.total_cents / 100) || '.' || lpad((c.total_cents % 100)::text, 2, '0') || ' 0.00 ' || p.total_quantity || ' 0' FROM account a JOIN app_user u ON u.id = a.owner_user_id JOIN cash_balance c ON c.account_id = a.id JOIN position p ON p.account_id = a.id AND p.instrument_id = '$oldNova' WHERE a.simulation_id = '$closed' AND u.username = 'trader'") `
            "$($portfolio.cash.total) $($portfolio.cash.reserved) $($holding.total) $($holding.reserved)"

        $started = Invoke-ApiCommand $operator '/admin/simulations' '{"name":"Second simulation"}'
        Assert-Equal 'the operator starts a new simulation' '201' $started.Status
        $second = $started.Body | ConvertFrom-Json
        $new = $second.simulationId
        Assert-Equal 'in setup, as its first command' 'SETUP 1' "$($second.state) $($second.commandSequence)"
        if ($new -eq $closed) { throw 'the start answered with the closed simulation' }
        Assert-Equal 'the current simulation, by its row' $new (Get-CurrentSimulation)
        $status = Invoke-RestMethod "$api/market" -WebSession $operator
        Assert-Equal 'the market status' "SETUP $new Second simulation" "$($status.state) $($status.simulationId) $($status.name)"
        Assert-Equal "the closed simulation's writer epoch, which no writer holds" '0' (Get-ExecutionValue "SELECT writer_epoch FROM simulation WHERE id = '$closed'")
        Assert-Equal 'the trader has no account until funded' '403' (Get-ApiStatus $trader '/portfolio')
        $nova = Set-UpMarket $operator
        if ($nova -eq $oldNova) { throw 'the new NOVA has the closed NOVA id' }
        Assert-Equal "NOVA by its symbol: the new simulation's" $nova (Get-Nova)
        Assert-Equal 'the counterparty offers one share' '201' (Invoke-ApiCommand $counterparty '/orders' (New-LimitOrder $nova 'SELL' '100.00')).Status
        Assert-Match 'the trader buys it' '"state":"FILLED"' (Invoke-ApiCommand $trader '/orders' (New-LimitOrder $nova 'BUY' '100.00')).Body
        Assert-Equal "NOVA's trades from 1: first, last" '1 1' (Get-ExecutionValue "SELECT min(trade_sequence) || ' ' || max(trade_sequence) FROM trades WHERE instrument_id = '$nova'")
        $last = Get-ExecutionValue "SELECT last_command_sequence FROM simulation WHERE id = '$new'"
        Assert-Equal 'a stored answer for each command from 1: count, first, last' "$last 1 $last" (Get-ExecutionValue "SELECT count(*) || ' ' || min(command_sequence) || ' ' || max(command_sequence) FROM command_result WHERE simulation_id = '$new'")
        if ([long]$last -ge [long](Get-ExecutionValue "SELECT last_command_sequence FROM simulation WHERE id = '$closed'")) { throw "the new simulation's sequence $last did not start again" }
        [void](Wait-Until 15 { "$((Get-Listing).simulationId)" -eq $new })
        $listing = Get-Listing
        Assert-Equal 'the instrument list: the new simulation, its NOVA alone' "$new $nova" "$($listing.simulationId) $((@($listing.instruments) | ForEach-Object { $_.instrumentId }) -join ' ')"
        Assert-Equal "the closed simulation's list, by its id" "$oldNova CLOSED" (Get-ListedNova $closed)
        Assert-Match "the closed NOVA's tape, by its id" '"trades":\[\{' ((& curl.exe -s -m 10 "$api/instruments/$oldNova/trades") | Out-String)
        Assert-Match "the closed NOVA's candles, by its id" '"candles":\[\{' ((& curl.exe -s -m 10 "$api/instruments/$oldNova/candles") | Out-String)
        $run = Start-BotRun $operator 23
        if (-not (Wait-Until 60 { (Get-LatestBotTrade) -gt 0 })) { throw 'no bot trade in the new simulation' }
        $listed = (Invoke-RestMethod "$api/admin/bot-runs?limit=1" -WebSession $operator).runs[0]
        Assert-Equal 'the simulator took up the new run' "$run RUNNING" "$($listed.runId) $($listed.status)"
        $files = ((Invoke-InContainer 'execution-service' @('ls', '/run/mse/bot-tokens')) -split "`n" | ForEach-Object { $_.Trim() -replace '\.token$', '' } | Where-Object { $_ } | Sort-Object) -join ' '
        $bots = ((Get-ExecutionValue "SELECT string_agg(id::text, ' ') FROM account WHERE kind = 'BOT' AND simulation_id = '$new'") -split ' ' | Sort-Object) -join ' '
        Assert-Equal "the bots' token files: the new bots' alone" $bots $files
        Stop-BotRun $operator $run
        Assert-CaughtUp
        Assert-Match "the new simulation's balances match its ledger: differing, compared" '^0 [1-9][0-9]*$' (Get-LedgerCheck $new)
    }

    Step 'Kafka outage: people trade, the feed catches up' {
        # With Kafka down, both services stay ready and people still trade: the counterparty sells to the trader, the
        # trader places and cancels, and balances read as usual. The public feed says it is stale, and its tape stops
        # short of the new trade while the outbox holds the events. Once Kafka is back, the outbox drains, the tape and
        # its totals match Execution's trades exactly, the feed is live again, and the candles catch up and reconcile.
        $trader = New-ApiSession 'trader' $traderPassword
        $counterparty = New-ApiSession 'counterparty' $counterpartyPassword
        $nova = Get-Nova
        Invoke-Checked 'docker' ($compose + @('stop', 'kafka'))
        Assert-Match 'Execution readiness' '"status":"UP"' (Get-Readiness 'execution-service')
        Assert-Match 'Market Data readiness' '"status":"UP"' (Get-Readiness 'market-data-service')
        # A price no other order rests at, so the two orders can only meet each other.
        Assert-Equal 'the counterparty offers one share' '201' (Invoke-ApiCommand $counterparty '/orders' (New-LimitOrder $nova 'SELL' '87.65')).Status
        $bought = Invoke-ApiCommand $trader '/orders' (New-LimitOrder $nova 'BUY' '87.65')
        Assert-Equal 'the trader buys it' '201' $bought.Status
        Assert-Match 'filled' '"state":"FILLED"' $bought.Body
        $trade = [long](Get-ExecutionValue "SELECT max(trade_sequence) FROM trades WHERE instrument_id = '$nova'")
        Invoke-TraderRoundTrip $trader
        if (-not (Wait-Until 20 { (Get-FeedState) -eq 'STALE' })) { throw "public feed is '$(Get-FeedState)' with Kafka down" }
        if (-not ((Get-Published) -lt $trade)) { throw "public tape reached trade $trade with Kafka down" }
        Assert-Match 'unpublished events in the outbox' '^[1-9][0-9]*$' (Get-OutboxPending)

        Invoke-Checked 'docker' ($compose + @('start', 'kafka'))
        Invoke-Checked 'docker' ($compose + @('up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', 'kafka'))
        if (-not (Wait-Until 60 { (Get-OutboxPending) -eq 0 })) { throw "outbox still holds $(Get-OutboxPending) events" }
        if (-not (Wait-Until 60 { (Get-Published) -ge $trade })) { throw "public tape at '$(Get-Published)', trade at $trade" }
        if (-not (Wait-Until 30 { (Get-FeedState) -eq 'LIVE' })) { throw "public feed is '$(Get-FeedState)' after Kafka returned" }
        $totals = "SELECT count(*) || ' ' || sum(quantity) || ' ' || sum(price_cents * quantity) || ' ' || max(trade_sequence)"
        # The tape comes from market snapshots; public_trade from the trade topic's own consumer, which can be a moment behind.
        [void](Wait-Until 30 { (Get-ExecutionValue "$totals FROM trades WHERE instrument_id = '$nova'") -eq (Get-MarketDataValue "$totals FROM public_trade WHERE instrument_id = '$nova'") })
        Assert-Equal "NOVA's public trades: count, shares, notional, last sequence" `
            (Get-ExecutionValue "$totals FROM trades WHERE instrument_id = '$nova'") `
            (Get-MarketDataValue "$totals FROM public_trade WHERE instrument_id = '$nova'")
        if (-not (Wait-Until 120 { (Get-TradesBehind $nova) -eq 0 })) {
            throw "candles still '$(Get-TradesBehind $nova)' trades behind after Kafka returned"
        }
        Assert-Reconciled
    }

    Step 'Crash: Execution killed mid-trading restarts and reconciles' {
        # Execution is killed (SIGKILL) during a bot run, cutting off whatever was in flight. It must start again with
        # its startup reconciliation passing (it refuses to start on any mismatch), the bots must carry on, and their
        # retries must apply nothing twice. Once the run is stopped, every trade is public with the same totals and the
        # candles reconcile.
        $operator = New-ApiSession 'operator' $operatorPassword
        $before = Get-LatestBotTrade
        $run = Start-BotRun $operator 17
        if (-not (Wait-Until 60 { (Get-LatestBotTrade) -gt $before })) { throw "no bot trade after $before" }
        Invoke-Checked 'docker' ($compose + @('kill', '-s', 'SIGKILL', 'execution-service'))
        "killed after bot trade $(Get-LatestBotTrade)" | Add-Content -Encoding utf8 $script:currentLog
        Invoke-Checked 'docker' ($compose + @('up', '-d', '--no-deps', '--wait', '--wait-timeout', '240', 'execution-service'))
        $before = Get-LatestBotTrade
        if (-not (Wait-Until 60 { (Get-LatestBotTrade) -gt $before })) { throw 'the bots did not trade again after the restart' }
        $operator = New-ApiSession 'operator' $operatorPassword
        Stop-BotRun $operator $run
        Assert-CaughtUp
    }

    Step 'Rebuild: Market Data rebuilt from Kafka matches exactly' {
        # Everything Market Data derived is deleted while it is stopped: instruments, books, tape, candles, the events it
        # saw and its checkpoints. Started again, it must rebuild all of it from Kafka's retained topics until every feed
        # is live, with the same trade totals as Execution and candles that reconcile exactly: for both simulations, the
        # closed one listed by its id as CLOSED and the current one listed by default.
        Invoke-Checked 'docker' ($compose + @('stop', 'market-data-service'))
        Invoke-MarketDataOwner "TRUNCATE $projections"
        Assert-Equal 'public trades after the wipe' '0' (Get-MarketDataValue 'SELECT count(*) FROM public_trade')
        Invoke-Checked 'docker' ($compose + @('start', 'market-data-service'))
        Invoke-Checked 'docker' ($compose + @('up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', 'market-data-service'))
        if (-not (Wait-Until 60 { Test-FeedsLive })) { throw 'the feeds did not all become live after the rebuild' }
        Assert-CaughtUp
    }

    Step 'Retry: a refused projection write is retried, not lost' {
        # The trade projection's writes are refused for a while (INSERT revoked from Market Data's runtime role) while
        # people trade. The feed must keep retrying the same records, neither skipping them nor pausing for good; the
        # public trades stay behind meanwhile. Once the grant is back, every trade is public exactly once.
        $trader = New-ApiSession 'trader' $traderPassword
        $counterparty = New-ApiSession 'counterparty' $counterpartyPassword
        $nova = Get-Nova
        Invoke-MarketDataOwner 'REVOKE INSERT ON public_trade FROM market_data_app'
        Assert-Equal 'the counterparty offers one share' '201' (Invoke-ApiCommand $counterparty '/orders' (New-LimitOrder $nova 'SELL' '87.70')).Status
        $bought = Invoke-ApiCommand $trader '/orders' (New-LimitOrder $nova 'BUY' '87.70')
        Assert-Equal 'the trader buys it' '201' $bought.Status
        Assert-Match 'filled' '"state":"FILLED"' $bought.Body
        Start-Sleep -Seconds 10
        $public = [long](Get-MarketDataValue "SELECT count(*) FROM public_trade WHERE instrument_id = '$nova'")
        $executed = [long](Get-ExecutionValue "SELECT count(*) FROM trades WHERE instrument_id = '$nova'")
        if (-not ($public -lt $executed)) { throw 'the trade became public while its insert was refused' }
        Invoke-MarketDataOwner 'GRANT INSERT ON public_trade TO market_data_app'
        if (-not (Wait-Until 60 { Test-FeedsLive })) { throw 'the trade feed did not become live again' }
        Assert-CaughtUp
    }

    Step 'Restore: a backup restored, and everything derived rebuilt to match' {
        # A backup, one more trade after it, then a restore. Execution must be back at the backup exactly, so the
        # extra trade is gone, and Market Data and the candles must rebuild from the republished history to match it,
        # both simulations' history.
        $backup = Join-Path $runDir 'backup'
        if (Test-Path $backup) { Remove-Item -Recurse -Force $backup }
        Invoke-Checked 'powershell.exe' @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', 'scripts\backup.ps1', '-Project', 'mse-gate', '-EnvFile', $envFile, '-To', $backup)
        $nova = Get-Nova
        # Both simulations' trades, the closed one's too: a restore republishes every simulation's history.
        $atBackup = Get-EveryNovaTotals
        "at the backup => $atBackup" | Add-Content -Encoding utf8 $script:currentLog
        $trader = New-ApiSession 'trader' $traderPassword
        $counterparty = New-ApiSession 'counterparty' $counterpartyPassword
        Assert-Equal 'the counterparty offers one share' '201' (Invoke-ApiCommand $counterparty '/orders' (New-LimitOrder $nova 'SELL' '87.80')).Status
        Assert-Match 'a trade after the backup' '"state":"FILLED"' (Invoke-ApiCommand $trader '/orders' (New-LimitOrder $nova 'BUY' '87.80')).Body
        Invoke-Checked 'powershell.exe' @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', 'scripts\restore.ps1', $backup, '-Project', 'mse-gate', '-EnvFile', $envFile, '-Yes')
        Assert-Equal "Execution back at the backup, every simulation's NOVA" $atBackup (Get-EveryNovaTotals)
        Assert-CaughtUp
        # The book's minutes come back from the republished snapshots, and none from after the backup is left: the
        # newest minute ends at the market's sequence, which a minute kept from the extra trade would pass.
        $minutes = "SELECT (SELECT max(last_sequence) FROM spread_minute WHERE instrument_id = '$nova') = (SELECT source_command_sequence FROM market_snapshot WHERE instrument_id = '$nova')"
        [void](Wait-Until 15 { (Get-MarketDataValue $minutes) -eq 't' })
        Assert-Equal "NOVA's minutes end at its market" 't' (Get-MarketDataValue $minutes)
    }
}
catch {
    $failed = $true
    Write-Output "Stopped: $($_.Exception.Message)"
    Write-Output "Log: $script:currentLog"
}
finally {
    if ($KeepStack) {
        Write-Output 'Stack kept: docker compose -p mse-gate'
    }
    else {
        & docker @compose down -v --remove-orphans *> (Join-Path $runDir 'teardown.log')
    }
    $script:results | Set-Content (Join-Path $runDir 'summary.txt')
}

if ($failed) {
    Write-Output 'COMPATIBILITY GATE: FAILED'
    exit 1
}
Write-Output 'COMPATIBILITY GATE: PASSED'
