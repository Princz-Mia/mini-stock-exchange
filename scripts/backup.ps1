# Backs up Execution's database, the only authority: orders, trades, the ledger, accounts, users, sessions, and the
# outbox of every event it has published. Everything else (Kafka's topics, Market Data's projections, Flink's
# candles) is derived from it, and scripts\restore.ps1 rebuilds those from a backup.
#
#   scripts\backup.ps1 [-Project NAME] [-EnvFile FILE] [-To DIRECTORY]
#
# Without -To, the backup goes to backups\<UTC time>\. It holds execution_db.dump (pg_dump's custom format, taken
# from one consistent snapshot, so trading can go on meanwhile) and manifest.txt, whose figures come from that same
# snapshot. Run it against the stack Compose started with the same project name and env file.
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

if (-not $To) { $To = Join-Path 'backups' ([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')) }
New-Item -ItemType Directory -Force $To | Out-Null

# The dump and the manifest's figures come from one snapshot, inside the container (infra/postgres/backup-execution-db.sh);
# the dump is written there and copied out, as binary data must not pass through a PowerShell pipeline.
Invoke-Compose @('cp', (Join-Path 'infra' (Join-Path 'postgres' 'backup-execution-db.sh')), 'postgres:/tmp/backup-execution-db.sh') | Out-Null
$figures = @(& docker @($compose + @('exec', '-T', 'postgres', 'sh', '/tmp/backup-execution-db.sh')) | Where-Object { $_ })
if ($LASTEXITCODE -ne 0) { throw 'the dump inside the container failed' }
Invoke-Compose @('cp', 'postgres:/tmp/execution_db.dump', (Join-Path $To 'execution_db.dump')) | Out-Null
Invoke-Compose @('exec', '-T', 'postgres', 'rm', '-f', '/tmp/execution_db.dump', '/tmp/backup-execution-db.sh')

$commit = (& git rev-parse --short HEAD 2>$null)
if (-not $commit) { $commit = 'unknown' }
# The snapshot's time first, then the commit of this checkout, then the snapshot's counts.
$manifest = @($figures[0], "commit=$commit") + @($figures | Select-Object -Skip 1)
# Plain ASCII lines, so the bash restore reads the same manifest.
[IO.File]::WriteAllLines((Join-Path (Resolve-Path $To) 'manifest.txt'), $manifest)
"Backed up execution_db to $To"
$manifest
