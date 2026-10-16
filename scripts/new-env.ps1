<#
Creates .env from .env.example with freshly generated secrets. Refuses to overwrite an existing .env.
Secret values are never printed.

Usage (from the project root):  powershell -ExecutionPolicy Bypass -File .\scripts\new-env.ps1
#>
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"
$examplePath = Join-Path $root ".env.example"

if (Test-Path $envPath) {
    throw ".env already exists. Delete it yourself if you really want to regenerate secrets."
}

function New-Secret([int]$Bytes) {
    $buffer = New-Object byte[] $Bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    return ([System.BitConverter]::ToString($buffer) -replace "-", "").ToLowerInvariant()
}

$values = @{
    "DATABASE_PASSWORD"         = New-Secret 24
    "REDIS_PASSWORD"            = New-Secret 24
    "JWT_SECRET"                = New-Secret 32
    "BOOTSTRAP_ADMIN_PASSWORD"  = New-Secret 16
}

$lines = Get-Content $examplePath
$output = foreach ($line in $lines) {
    $replaced = $line
    foreach ($key in $values.Keys) {
        if ($line -match "^$key=") { $replaced = "$key=$($values[$key])" }
    }
    $replaced
}
Set-Content -Path $envPath -Value $output -Encoding UTF8
Write-Host "Created .env with generated secrets. Values are not shown. Keep this file private."
