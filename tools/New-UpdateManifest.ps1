param(
    [Parameter(Mandatory = $true)][string]$ApkUrl,
    [string]$ApkPath = "$PSScriptRoot/../app/build/outputs/apk/debug/app-debug.apk",
    [string]$OutputPath = "$PSScriptRoot/../updates/latest.json",
    [string]$Notes = ''
)
$ErrorActionPreference = 'Stop'
$url = [uri]$ApkUrl
if ($url.Scheme -ne 'https' -or $url.UserInfo) { throw 'ApkUrl debe ser una URL HTTPS sin credenciales.' }
$apk = Get-Item -LiteralPath $ApkPath
$metadata = Get-Content -LiteralPath (Join-Path $apk.DirectoryName 'output-metadata.json') -Raw | ConvertFrom-Json
$entry = @($metadata.elements | Where-Object { $_.outputFile -eq $apk.Name })
if ($entry.Count -ne 1) { throw 'No se encuentra la versión de este APK en output-metadata.json.' }
if ($metadata.applicationId -ne 'com.example.capillaflix') { throw 'El APK pertenece a otra aplicación.' }
$result = [ordered]@{
    versionCode = [long]$entry[0].versionCode
    versionName = $entry[0].versionName
    apkUrl = $ApkUrl
    sha256 = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    minSdk = 26
    notes = $Notes
}
$fullOutput = [IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Force (Split-Path $fullOutput -Parent) | Out-Null
$result | ConvertTo-Json | Set-Content -LiteralPath $fullOutput -Encoding utf8NoBOM
Write-Output "Creado $fullOutput para CapillaFlix $($result.versionName) ($($result.versionCode))."
