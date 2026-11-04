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
$envLines = @(
    "POSTGRES_PASSWORD=$($passwords.postgres)"
    "EXECUTION_DB_OWNER_PASSWORD=$($passwords.execution_owner)"
    "EXECUTION_DB_APP_PASSWORD=$($passwords.execution_app)"
    "MARKET_DATA_DB_OWNER_PASSWORD=$($passwords.market_data_owner)"
    "MARKET_DATA_DB_APP_PASSWORD=$($passwords.market_data_app)"
    "GRAFANA_ADMIN_PASSWORD=$(New-Secret)"
    "DEV_OPERATOR_PASSWORD=$(New-Secret)"
    "DEV_TRADER_PASSWORD=$traderPassword"
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
    param([string]$Module, [string]$TestClass, [string]$SimulationId)
    Invoke-Checked '.\mvnw.cmd' @('-B', '-ntp', '-f', $Module, 'test-compile', 'failsafe:integration-test', 'failsafe:verify',
        "-Dit.test=$TestClass", '-Dfailsafe.failIfNoSpecifiedTests=true', "-Dsmoke.kafka=localhost:$kafkaPort", "-Dsmoke.simulationId=$SimulationId")
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

    Step 'Frontend: npm ci, typecheck, lint, test, build' {
        Push-Location web
        try {
            foreach ($arguments in @(@('ci'), @('run', 'typecheck'), @('run', 'lint'), @('test'), @('run', 'build'))) {
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
        Assert-Equal '/api/v1/instruments/x/candles (Market Data, denied)' '403' (Get-Http '/api/v1/instruments/x/candles')
        Assert-Equal '/api/v1/internal/x (blocked at proxy)' '404' (Get-Http '/api/v1/internal/x')
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

    Step 'Avro: JDK 25 > Kafka > Flink JDK 17 > Kafka > JDK 25' {
        $simulationId = [guid]::NewGuid().ToString()
        Invoke-Smoke 'services/execution-service/pom.xml' 'TradePublishSmoke' $simulationId
        Invoke-Smoke 'services/market-data-service/pom.xml' 'CandleConsumeSmoke' $simulationId
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
