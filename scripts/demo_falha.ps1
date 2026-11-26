param(
    [string]$BaseUrl = "http://localhost:8080",
    [switch]$UseDockerRestart
)

$ErrorActionPreference = "Stop"

$health = Invoke-RestMethod -Uri "$BaseUrl/system/health" -Method Get
$health | ConvertTo-Json -Depth 4 | Write-Host

$suffix = [guid]::NewGuid().ToString().Substring(0, 6)
$username = "investidor_demo_$suffix"
$auth = Invoke-RestMethod -Uri "$BaseUrl/auth/register" -Method Post -ContentType "application/json" -Body (@{
    username = $username
    password = "senha123"
} | ConvertTo-Json)
Write-Host "Usuario criado: $($auth.userId)"

if ($UseDockerRestart) {
    docker compose stop backend
    Start-Sleep -Seconds 3
    docker compose start backend
    Start-Sleep -Seconds 6
    docker compose logs backend --tail 50 | Select-String -Pattern "CRASH_RECOVERY|ORDER_RECOVERED|ORDER_BOOK_RESTORED"
} else {
    $recovery = Invoke-RestMethod -Uri "$BaseUrl/system/fault/simulate-crash-recovery" -Method Post
    $recovery | ConvertTo-Json -Depth 5 | Write-Host
}

$metrics = Invoke-RestMethod -Uri "$BaseUrl/metrics" -Method Get
$metrics | ConvertTo-Json -Depth 5 | Write-Host
