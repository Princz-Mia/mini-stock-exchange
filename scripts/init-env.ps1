# Creates a local .env from .env.example with a random value for every placeholder. Never overwrites.
$root = Split-Path -Parent $PSScriptRoot
$target = Join-Path $root '.env'
if (Test-Path $target) {
    Write-Output '.env already exists; leaving it unchanged.'
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
