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
$envLines = @(
    "POSTGRES_PASSWORD=$($passwords.postgres)"
    "EXECUTION_DB_OWNER_PASSWORD=$($passwords.execution_owner)"
    "EXECUTION_DB_APP_PASSWORD=$($passwords.execution_app)"
    "MARKET_DATA_DB_OWNER_PASSWORD=$($passwords.market_data_owner)"
    "MARKET_DATA_DB_APP_PASSWORD=$($passwords.market_data_app)"
    "GRAFANA_ADMIN_PASSWORD=$(New-Secret)"
    "DEV_OPERATOR_PASSWORD=$operatorPassword"
    "DEV_TRADER_PASSWORD=$traderPassword"
    "MSE_EXPORT_TOKEN=$(New-Secret)"
    "MSE_SIMULATOR_TOKEN=$(New-Secret)"
    "WEB_HOST_PORT=$webPort"
    'POSTGRES_HOST_PORT=25432'
    "KAFKA_HOST_PORT=$kafkaPort"
    'FLINK_UI_HOST_PORT=28081'
)
[System.IO.File]::WriteAllLines($envFile, [string[]]$envLines, (New-Object System.Text.UTF8Encoding $false))

$compose = @('compose', '-p', 'mse-gate', '--env-file', $envFile, '--profile', 'full')

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
        Assert-ClassVersion 'event-contracts\target\event-contracts-0.1.0.jar' 'com.mia_princz.mse.contracts.events.TradeExecuted' 61
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
        Assert-Equal '/ (SPA)' '200' (Get-Http '/')
        Assert-Equal '/deep/link (SPA fallback)' '200' (Get-Http '/deep/link')
        Assert-Equal '/api/v1/orders (Execution, denied)' '401' (Get-Http '/api/v1/orders')
        Assert-Match '/api/v1/instruments (Market Data, public)' '"instruments":\[' ((& curl.exe -s -m 10 "http://localhost:$webPort/api/v1/instruments") | Out-String)
        Assert-Equal '/api/v1/instruments/x/orders (Market Data, denied)' '403' (Get-Http '/api/v1/instruments/x/orders')
        Assert-Match '/api/v1/market-events, no market yet (Market Data)' '"code":"INSTRUMENT_NOT_FOUND"' ((& curl.exe -s -m 10 "http://localhost:$webPort/api/v1/market-events?instrumentId=00000000-0000-0000-0000-000000000001") | Out-String)
        Assert-Equal '/api/v1/internal/x (blocked at proxy)' '404' (Get-Http '/api/v1/internal/x')
        Assert-Match '/internal/exports/trades serves the SPA, not the export' '^<!doctype html>' ((& curl.exe -s "http://localhost:$webPort/internal/exports/trades?instrumentId=00000000-0000-0000-0000-000000000001") | Out-String)
        Assert-Match '/internal/simulator/assignment serves the SPA, not the assignment' '^<!doctype html>' ((& curl.exe -s "http://localhost:$webPort/internal/simulator/assignment") | Out-String)
        $actuator = (& curl.exe -s "http://localhost:$webPort/actuator/health") | Out-String
        Assert-Match '/actuator/health serves the SPA, not Actuator' '^\s*<!doctype html>' $actuator
    }

    Step 'Login: session and CSRF through the proxy' {
        $base = "http://localhost:$webPort/api/v1"
        $csrf = Invoke-RestMethod "$base/auth/csrf" -SessionVariable browser
        $body = @{ username = 'trader'; password = $traderPassword } | ConvertTo-Json
        $login = Invoke-RestMethod "$base/auth/login" -Method Post -WebSession $browser -ContentType 'application/json' -Body $body -Headers @{ $csrf.headerName = $csrf.token }
        Assert-Equal 'login user' 'trader' $login.username
        Assert-Equal 'me' 'TRADER' (Invoke-RestMethod "$base/me" -WebSession $browser).roles
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

    Step 'Browser: trading, phone layout, halt and resume' {
        # Users have no API, so the counterparty's login is inserted; the test sets up the market as the operator.
        $counterpartyPassword = New-Secret
        Invoke-Checked 'docker' ($compose + @('cp', 'web/e2e/seed-users.sql', 'postgres:/tmp/seed-users.sql'))
        Invoke-Checked 'docker' ($compose + @('exec', '-T', '-e', "PGPASSWORD=$($passwords.execution_owner)", 'postgres', 'psql', '-h', '127.0.0.1', '-q',
            '-U', 'execution_owner', '-d', 'execution_db', '-v', "counterparty_password=$counterpartyPassword", '-f', '/tmp/seed-users.sql'))
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
        Assert-Match 'token files' '^\s*2 600 mse\s*$' (Invoke-InContainer 'execution-service' @('sh', '-c', 'stat -c "%a %U" /run/mse/bot-tokens/*.token | uniq -c'))
        # Tokens are read inside the container, so they never reach this shell.
        $assignment = { param($variable) 'curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $' + $variable + '" http://localhost:8080/internal/simulator/assignment' }
        Assert-Equal 'the simulator token opens its assignment' '200' (Invoke-InContainer 'execution-service' @('sh', '-c', (& $assignment 'MSE_SIMULATOR_TOKEN')))
        Assert-Equal 'the export token does not' '403' (Invoke-InContainer 'execution-service' @('sh', '-c', (& $assignment 'MSE_EXPORT_TOKEN')))
    }

    Step 'Bots: the simulator trades and the tape shows it' {
        # The operator starts a run for the bots the browser test provisioned. The simulator follows it, their trades
        # reach the public market data, and a stop ends with the stop confirmed and no bot order left open.
        $base = "http://localhost:$webPort/api/v1"
        $csrf = Invoke-RestMethod "$base/auth/csrf" -SessionVariable operator
        $body = @{ username = 'operator'; password = $operatorPassword } | ConvertTo-Json
        [void](Invoke-RestMethod "$base/auth/login" -Method Post -WebSession $operator -ContentType 'application/json' -Body $body -Headers @{ $csrf.headerName = $csrf.token })
        $csrf = Invoke-RestMethod "$base/auth/csrf" -WebSession $operator
        $headers = @{ $csrf.headerName = $csrf.token; 'Idempotency-Key' = [guid]::NewGuid().ToString() }
        $started = Invoke-RestMethod "$base/admin/bot-runs" -Method Post -WebSession $operator -ContentType 'application/json' -Headers $headers -Body '{"noise":2,"seed":7,"commandsPerSecond":"4.00"}'
        Assert-Match 'bot run started' '^[0-9a-f-]{36}$' $started.runId
        $sequence = 0
        foreach ($attempt in 1..60) {
            $result = Invoke-Psql 'execution_owner' $passwords.execution_owner 'execution_db' "SELECT coalesce(max(t.trade_sequence), 0) FROM trades t JOIN account b ON b.id = t.buyer_account_id JOIN account s ON s.id = t.seller_account_id WHERE b.kind = 'BOT' OR s.kind = 'BOT'"
            if ($result.Code -eq 0 -and [long]$result.Output -gt 0) { $sequence = [long]$result.Output; break }
            Start-Sleep -Seconds 2
        }
        Assert-Match 'latest bot trade sequence' '^[1-9][0-9]*$' $sequence
        $published = 0
        foreach ($attempt in 1..30) {
            $listing = (& curl.exe -s -m 10 "http://localhost:$webPort/api/v1/instruments" | Out-String)
            $published = ([regex]::Matches($listing, '"lastTradeSequence":"(\d+)"') | ForEach-Object { [long]$_.Groups[1].Value } | Measure-Object -Maximum).Maximum
            if ($published -ge $sequence) { break }
            Start-Sleep -Seconds 2
        }
        if (-not ($published -ge $sequence)) { throw "public tape at '$published', bot trade at $sequence" }
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
        $open = Invoke-Psql 'execution_owner' $passwords.execution_owner 'execution_db' "SELECT count(*) FROM orders o JOIN account a ON a.id = o.account_id WHERE a.kind = 'BOT' AND o.state IN ('OPEN', 'PARTIALLY_FILLED')"
        Assert-Equal 'bot orders left open' '0' $open.Output
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
        $report = Invoke-InContainer 'market-data-service' @('curl', '-fsS', 'http://localhost:8080/internal/reconciliation/candles')
        Assert-Match 'candles reconcile with the trade export' '"reconciled":true' $report
        Assert-Match 'candles were checked' '"candlesChecked":[1-9]' $report
    }

    Step 'Outage isolation: services stay ready without Kafka' {
        Invoke-Checked 'docker' ($compose + @('stop', 'kafka'))
        Assert-Match 'Execution readiness' '"status":"UP"' (Get-Readiness 'execution-service')
        Assert-Match 'Market Data readiness' '"status":"UP"' (Get-Readiness 'market-data-service')
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
