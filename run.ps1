# Pulse launcher. Usage:  powershell -ExecutionPolicy Bypass -File .\run.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

function Need($cmd, $hint) {
  if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) { Write-Host "Missing '$cmd'. $hint" -ForegroundColor Red; exit 1 }
}
Need java 'Install JDK 17+ (https://adoptium.net).'
Need mvn  'Install Apache Maven 3.9+ (https://maven.apache.org/download.cgi) and add it to PATH.'
Need npm  'Install Node.js 18+ (https://nodejs.org).'

if (-not (Test-Path "$root\frontend\node_modules")) {
  Write-Host 'Installing frontend dependencies...' -ForegroundColor Cyan
  Push-Location "$root\frontend"; npm install; Pop-Location
}
Write-Host 'Starting backend on http://localhost:8080 ...' -ForegroundColor Cyan
Start-Process powershell -ArgumentList '-NoExit','-Command',"Set-Location '$root\backend'; mvn spring-boot:run"
Write-Host 'Starting frontend on http://localhost:5173 ...' -ForegroundColor Cyan
Start-Process powershell -ArgumentList '-NoExit','-Command',"Set-Location '$root\frontend'; npm run dev"
Start-Sleep -Seconds 12
Start-Process 'http://localhost:5173'
Write-Host 'Both servers run in their own windows. Close those windows to stop the app.' -ForegroundColor Green
