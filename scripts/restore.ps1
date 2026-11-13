# Restores Execution's database from a backup made by scripts\backup.ps1, then rebuilds everything derived from it.
#
#   scripts\restore.ps1 BACKUP_DIRECTORY [-Project NAME] [-EnvFile FILE] [-Yes]
#
# Execution is the only authority, so after its database goes back to the backup, anything newer elsewhere would
# describe trades that no longer happened. In order:
#   1. stop the services that write or derive: simulator, Flink, Market Data, Execution;
#   2. restore execution_db from the dump, in one transaction;
#   3. mark every outbox event unpublished again, so the relay republishes the whole history in order;
#   4. empty Market Data's projections and checkpoints, and delete Kafka's and Flink's volumes;
#   5. recreate the topics, and start again whatever was running before.
# Market Data and the candles then rebuild from the republished events. This deletes data: it asks first unless
# -Yes is given. Revoking a bot's token after the backup was taken is undone by restoring it; revoke it again.
param([Parameter(Mandatory = $true, Position = 0)][string]$Backup, [string]$Project, [string]$EnvFile = '.env',
    [switch]$Yes)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
$dump = Join-Path $Backup 'execution_db.dump'
if (-not (Test-Path $dump)) { throw "No execution_db.dump in $Backup" }
$compose = @('compose')
if ($Project) { $compose += @('-p', $Project) }
$compose += @('--env-file', $EnvFile, '--profile', 'full', '--profile', 'observability')

function Invoke-Compose {
    param([string[]]$Arguments)
    & docker @($compose + $Arguments)
    if ($LASTEXITCODE -ne 0) { throw "docker compose $($Arguments -join ' ') failed with exit $LASTEXITCODE" }
}

function Invoke-Sql {
    param([string]$Database, [string]$Sql)
    Invoke-Compose @('exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', $Database, '-v', 'ON_ERROR_STOP=1', '-q', '-c', $Sql)
}

$name = ((& docker @($compose + @('config', '--format', 'json'))) -join "`n" | ConvertFrom-Json).name
Get-Content (Join-Path $Backup 'manifest.txt')
if (-not $Yes) {
    $answer = Read-Host "Restore project $name to this backup? Newer trades, and all of Kafka, Market Data and Flink state, are deleted. [y/N]"
    if ($answer -ne 'y') { 'Nothing changed.'; exit 1 }
}

$running = @(& docker @($compose + @('ps', '--services', '--status', 'running')) | Where-Object { $_ })
"Running before: $($running -join ' ')"
Invoke-Compose @('stop', 'simulator', 'flink-taskmanager', 'flink-jobmanager', 'market-data-service', 'execution-service')

'Restoring execution_db'
Invoke-Compose @('cp', $dump, 'postgres:/tmp/restore.dump') | Out-Null
Invoke-Compose @('exec', '-T', 'postgres', 'pg_restore', '-U', 'postgres', '-d', 'execution_db', '--clean', '--if-exists', '--single-transaction', '--exit-on-error', '/tmp/restore.dump')
Invoke-Compose @('exec', '-T', 'postgres', 'rm', '-f', '/tmp/restore.dump')
# Publication is one-way for the services; only this maintenance step, as the database superuser, resets it.
Invoke-Sql 'execution_db' 'BEGIN; ALTER TABLE outbox DISABLE TRIGGER outbox_published_once; UPDATE outbox SET published_at = NULL; ALTER TABLE outbox ENABLE TRIGGER outbox_published_once; COMMIT;'

'Emptying Market Data, Kafka and Flink'
Invoke-Sql 'market_data_db' 'TRUNCATE instrument_projection, market_snapshot, processed_event, projection_checkpoint, public_trade, instrument_session, session_minute, candle_1m'
Invoke-Compose @('rm', '-sf', 'kafka', 'flink-jobmanager', 'flink-taskmanager') | Out-Null
& docker volume rm -f "${name}_kafka-data" "${name}_flink-checkpoints" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'removing the Kafka and Flink volumes failed' }

'Recreating topics and starting again'
Invoke-Compose @('up', '-d', '--wait', 'kafka')
Invoke-Compose @('run', '--rm', '--no-deps', 'kafka-init') | Out-Null
Invoke-Compose (@('up', '-d', '--wait', '--wait-timeout', '300') + $running)
$events = (Get-Content (Join-Path $Backup 'manifest.txt') | Where-Object { $_ -like 'outbox_events=*' }) -replace '^outbox_events=', ''
"Restored. Execution republishes $events events; Market Data and the candles rebuild from them."
