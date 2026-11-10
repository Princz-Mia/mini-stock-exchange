# Creates a local .env from .env.example with a random value for every placeholder. Never overwrites.
$root = Split-Path -Parent $PSScriptRoot
$target = Join-Path $root '.env'
function Test-Ports {
    # Warns about host ports something else already uses, which would stop the stack from starting.
    $listening = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | ForEach-Object { $_.Port }
    foreach ($line in Get-Content $target) {
        if ($line -match '^([A-Z_]+_HOST_PORT)=([0-9]+)$' -and $listening -contains [int]$Matches[2]) {
            Write-Output "Port $($Matches[2]) ($($Matches[1])) is already in use. Unless this stack is running, set $($Matches[1]) in .env to a free port."
        }
    }
}

if (Test-Path $target) {
    Write-Output '.env already exists; leaving it unchanged.'
    Test-Ports
    exit 0
}

$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$lines = Get-Content (Join-Path $root '.env.example') | ForEach-Object {
    if ($_ -match '=change-me$') {
        $bytes = New-Object byte[] 24
        $random.GetBytes($bytes)
        $_ -replace 'change-me$', (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
    }
    else {
        $_
    }
}
[System.IO.File]::WriteAllLines($target, [string[]]$lines, (New-Object System.Text.UTF8Encoding $false))
Write-Output 'Created .env with random development secrets.'
Test-Ports
