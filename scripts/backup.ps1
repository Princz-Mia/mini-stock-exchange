# Backs up Execution's database, the only authority: orders, trades, the ledger, accounts, users, sessions, and the
# outbox of every event it has published. Everything else (Kafka's topics, Market Data's projections, Flink's
# candles) is derived from it, and scripts\restore.ps1 rebuilds those from a backup.
#
#   scripts\backup.ps1 [-Project NAME] [-EnvFile FILE] [-To DIRECTORY]
#
# Without -To, the backup goes to backups\<UTC time>\. It holds execution_db.dump (pg_dump's custom format, taken
# from one consistent snapshot, so trading can go on meanwhile) and manifest.txt. Run it against the stack Compose
# started with the same project name and env file.
param([string]$Project, [string]$EnvFile = '.env', [string]$To)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
$compose = @('compose')
if ($Project) { $compose += @('-p', $Project) }
$compose += @('--env-file', $EnvFile, '--profile', 'full', '--profile', 'observability')

function Invoke-Compose {
    param([string[]]$Arguments)
    & docker @($compose + $Arguments)
    if ($LASTEXITCODE -ne 0) { throw "docker compose $($Arguments -join ' ') failed with exit $LASTEXITCODE" }
}

function Get-Value {
    param([string]$Sql)
    $value = & docker @($compose + @('exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', 'execution_db', '-v', 'ON_ERROR_STOP=1', '-Atc', $Sql))
    if ($LASTEXITCODE -ne 0) { throw "query failed: $Sql" }
    return ($value | Out-String).Trim()
}

if (-not $To) { $To = Join-Path 'backups' ([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')) }
New-Item -ItemType Directory -Force $To | Out-Null

# Written inside the container and copied out: binary data must not pass through a PowerShell pipeline.
Invoke-Compose @('exec', '-T', 'postgres', 'pg_dump', '-U', 'postgres', '-d', 'execution_db', '--format=custom', '--file=/tmp/execution_db.dump')
Invoke-Compose @('cp', 'postgres:/tmp/execution_db.dump', (Join-Path $To 'execution_db.dump')) | Out-Null
Invoke-Compose @('exec', '-T', 'postgres', 'rm', '-f', '/tmp/execution_db.dump')

$commit = (& git rev-parse --short HEAD 2>$null)
if (-not $commit) { $commit = 'unknown' }
$manifest = @(
    "created_at=$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))"
    "commit=$commit"
    "schema_version=$(Get-Value 'SELECT max(version::int) FROM flyway_schema_history WHERE success')"
    "last_command_sequence=$(Get-Value 'SELECT coalesce(max(command_sequence), 0) FROM outbox')"
    "trades=$(Get-Value 'SELECT count(*) FROM trades')"
    "last_trade_sequence=$(Get-Value 'SELECT coalesce(max(trade_sequence), 0) FROM trades')"
    "outbox_events=$(Get-Value 'SELECT count(*) FROM outbox')"
)
# Plain ASCII lines, so the bash restore reads the same manifest.
[IO.File]::WriteAllLines((Join-Path (Resolve-Path $To) 'manifest.txt'), $manifest)
"Backed up execution_db to $To"
$manifest
