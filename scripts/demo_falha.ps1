param(
    [string]$Url = "http://localhost:8086",
    [switch]$Simular
)

$ErrorActionPreference = "Stop"

function mandarStomp($ws, [string]$texto) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($texto + [char]0)
    $seg = [System.ArraySegment[byte]]::new($bytes)
    $ws.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, [Threading.CancellationToken]::None).GetAwaiter().GetResult()
}

function lerStomp($ws, [int]$ms = 1500) {
    $lista = @()
    $buf = New-Object byte[] 16384
    $fim = (Get-Date).AddMilliseconds($ms)
    while ((Get-Date) -lt $fim) {
        $resto = [int]($fim - (Get-Date)).TotalMilliseconds
        if ($resto -le 0) { break }
        $cts = [Threading.CancellationTokenSource]::new($resto)
        try {
            $seg = [System.ArraySegment[byte]]::new($buf)
            $res = $ws.ReceiveAsync($seg, $cts.Token).GetAwaiter().GetResult()
            $str = [System.Text.Encoding]::UTF8.GetString($buf, 0, $res.Count)
            foreach ($p in ($str -split "`0")) {
                $idx = $p.IndexOf("`n`n")
                if ($idx -lt 0) { $idx = $p.IndexOf("`r`n`r`n") }
                if ($idx -ge 0) {
                    $json = $p.Substring($idx).Trim()
                    if ($json.StartsWith("{")) {
                        try { $lista += ($json | ConvertFrom-Json) } catch {}
                    }
                }
            }
        } catch {
            break
        } finally {
            $cts.Dispose()
        }
    }
    return $lista
}

function abrirSessao($wsUrl, $token) {
    $ws = [System.Net.WebSockets.ClientWebSocket]::new()
    $ws.Options.SetRequestHeader("Origin", "http://localhost:5173")
    $ws.ConnectAsync([uri]$wsUrl, [Threading.CancellationToken]::None).GetAwaiter().GetResult()
    mandarStomp $ws "CONNECT`naccept-version:1.2`nAuthorization:Bearer $token`n`n"
    $null = lerStomp $ws 600
    mandarStomp $ws "SUBSCRIBE`nid:s1`ndestination:/user/queue/orders`n`n"
    mandarStomp $ws "SUBSCRIBE`nid:s2`ndestination:/user/queue/replies`n`n"
    Start-Sleep -Milliseconds 250
    return $ws
}

function consultarEstado($ws) {
    mandarStomp $ws "SEND`ndestination:/app/wallet/me`ncontent-type:application/json`n`n{}"
    mandarStomp $ws "SEND`ndestination:/app/orderbook/snapshot`ncontent-type:application/json`n`n{`"symbol`":`"PETR4`"}"
    $eventos = lerStomp $ws 1200
    $carteira = ($eventos | Where-Object { $_.type -eq "WALLET_SNAPSHOT" } | Select-Object -Last 1).data
    $livro = ($eventos | Where-Object { $_.type -eq "ORDER_BOOK_SNAPSHOT" } | Select-Object -Last 1).data
    return @{ carteira = $carteira; livro = $livro }
}

$wsUrl = ($Url -replace "^http", "ws") + "/ws"
$id = [guid]::NewGuid().ToString().Substring(0, 6)
$user = "demo_$id"

$auth = Invoke-RestMethod -Uri "$Url/auth/register" -Method Post -ContentType "application/json" -Body (@{
    username = $user
    password = "senha123"
} | ConvertTo-Json)

Write-Host "Usuario: $user (saldo inicial: $($auth.balance))"

$ws = abrirSessao $wsUrl $auth.token
$ordem = @{
    clientOrderId = "ordem-$id"
    symbol = "PETR4"
    side = "BUY"
    quantity = 10
    price = "35.00"
} | ConvertTo-Json -Compress

mandarStomp $ws "SEND`ndestination:/app/orders/create`ncontent-type:application/json`n`n$ordem"
$null = lerStomp $ws 1000

$antes = consultarEstado $ws
Write-Host "Antes do restart:"
Write-Host "  Saldo disponivel: $($antes.carteira.balance) | Reservado: $($antes.carteira.reservedBalance)"
Write-Host "  Livro PETR4 (compra): $($antes.livro.bids | ConvertTo-Json -Compress)"
$ws.Dispose()

if ($Simular) {
    $rec = Invoke-RestMethod -Uri "$Url/system/fault/simulate-crash-recovery" -Method Post
    Write-Host "Ordens recuperadas: $($rec.totalRecoveredOrders)"
} else {
    Write-Host "Reiniciando backend no docker..."
    docker compose restart backend

    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 2
        try {
            $h = Invoke-RestMethod -Uri "$Url/system/health" -Method Get -TimeoutSec 2
            if ($h.status -eq "UP") { break }
        } catch {}
    }

    docker compose logs backend --tail 60 | Select-String -Pattern "CRASH_RECOVERY|ORDER_RECOVERED|ORDER_BOOK_RESTORED"
}

$ws2 = abrirSessao $wsUrl $auth.token
$depois = consultarEstado $ws2
Write-Host "Depois do restart:"
Write-Host "  Saldo disponivel: $($depois.carteira.balance) | Reservado: $($depois.carteira.reservedBalance)"
Write-Host "  Livro PETR4 (compra): $($depois.livro.bids | ConvertTo-Json -Compress)"
$ws2.Dispose()
