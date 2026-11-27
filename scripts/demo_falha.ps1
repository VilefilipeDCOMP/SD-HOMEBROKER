param(
    [string]$Url = "http://localhost:8086",
    [switch]$Simular
)

$ErrorActionPreference = "Stop"

function mandarStomp($ws, [string]$texto) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($texto + [char]0)
    $seg = [System.ArraySegment[byte]]::new($bytes)
    $null = $ws.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, [Threading.CancellationToken]::None).GetAwaiter().GetResult()
}

function lerFrame($sessao, [int]$timeoutMs = 10000) {
    if ($sessao.fila.Count -gt 0) {
        return $sessao.fila.Dequeue()
    }

    $buf = New-Object byte[] 16384
    $acum = ""
    while ($true) {
        $cts = [Threading.CancellationTokenSource]::new($timeoutMs)
        try {
            $seg = [System.ArraySegment[byte]]::new($buf)
            $res = $sessao.ws.ReceiveAsync($seg, $cts.Token).GetAwaiter().GetResult()
            if ($res.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
                throw "Conexao WebSocket encerrada pelo servidor"
            }
            $acum += [System.Text.Encoding]::UTF8.GetString($buf, 0, $res.Count)
            if ($res.EndOfMessage) {
                foreach ($parte in ($acum -split "`0")) {
                    $limpo = $parte.Trim("`r", "`n")
                    if ($limpo.Length -gt 0) {
                        $sessao.fila.Enqueue($limpo)
                    }
                }
                $acum = ""
                if ($sessao.fila.Count -gt 0) {
                    return $sessao.fila.Dequeue()
                }
            }
        } finally {
            $cts.Dispose()
        }
    }
}

function extrairEvento([string]$frame) {
    $idx = $frame.IndexOf("`n`n")
    if ($idx -lt 0) { $idx = $frame.IndexOf("`r`n`r`n") }
    if ($idx -lt 0) { return $null }
    $json = $frame.Substring($idx).Trim()
    if (-not $json.StartsWith("{")) { return $null }
    try { return ($json | ConvertFrom-Json) } catch { return $null }
}

function abrirSessao($wsUrl, $token) {
    $ws = [System.Net.WebSockets.ClientWebSocket]::new()
    $ws.Options.SetRequestHeader("Origin", "http://localhost:5173")
    $null = $ws.ConnectAsync([uri]$wsUrl, [Threading.CancellationToken]::None).GetAwaiter().GetResult()
    $sessao = [pscustomobject]@{
        ws = $ws
        fila = [System.Collections.Generic.Queue[string]]::new()
    }
    mandarStomp $ws "CONNECT`naccept-version:1.2`nAuthorization:Bearer $token`n`n"
    while ($true) {
        $frame = lerFrame $sessao
        if ($frame.StartsWith("CONNECTED")) { break }
        if ($frame.StartsWith("ERROR")) { throw "Erro STOMP: $frame" }
    }
    mandarStomp $ws "SUBSCRIBE`nid:s1`ndestination:/user/queue/orders`n`n"
    mandarStomp $ws "SUBSCRIBE`nid:s2`ndestination:/user/queue/replies`n`n"
    return $sessao
}

function fecharSessao($sessao) {
    try {
        if ($sessao.ws.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
            $null = $sessao.ws.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, "fim", [Threading.CancellationToken]::None).GetAwaiter().GetResult()
        }
    } catch {}
    $sessao.ws.Dispose()
}

function consultarEstado($sessao) {
    mandarStomp $sessao.ws "SEND`ndestination:/app/wallet/me`ncontent-type:application/json`n`n{}"
    mandarStomp $sessao.ws "SEND`ndestination:/app/orderbook/snapshot`ncontent-type:application/json`n`n{`"symbol`":`"PETR4`"}"
    $carteira = $null
    $livro = $null
    while ($null -eq $carteira -or $null -eq $livro) {
        $ev = extrairEvento (lerFrame $sessao)
        if ($null -eq $ev) { continue }
        if ($ev.type -eq "WALLET_SNAPSHOT") { $carteira = $ev.data }
        if ($ev.type -eq "ORDER_BOOK_SNAPSHOT") { $livro = $ev.data }
    }
    return [pscustomobject]@{ carteira = $carteira; livro = $livro }
}

$wsUrl = ($Url -replace "^http", "ws") + "/ws"
$id = [guid]::NewGuid().ToString().Substring(0, 6)
$user = "demo_$id"

$auth = Invoke-RestMethod -Uri "$Url/auth/register" -Method Post -ContentType "application/json" -Body (@{
    username = $user
    password = "senha123"
} | ConvertTo-Json)

Write-Host "Usuario: $user (saldo inicial: $($auth.balance))"

$sessao = abrirSessao $wsUrl $auth.token
$ordem = @{
    clientOrderId = "ordem-$id"
    symbol = "PETR4"
    side = "BUY"
    quantity = 10
    price = "35.00"
} | ConvertTo-Json -Compress

mandarStomp $sessao.ws "SEND`ndestination:/app/orders/create`ncontent-type:application/json`n`n$ordem"
while ($true) {
    $ev = extrairEvento (lerFrame $sessao)
    if ($null -ne $ev -and $ev.type -eq "ORDER_UPDATED") { break }
}

$antes = consultarEstado $sessao
Write-Host "Antes do restart:"
Write-Host "  Saldo disponivel: $($antes.carteira.balance) | Reservado: $($antes.carteira.reservedBalance)"
Write-Host "  Livro PETR4 (compra): $($antes.livro.bids | ConvertTo-Json -Compress)"
fecharSessao $sessao

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

$sessao2 = abrirSessao $wsUrl $auth.token
$depois = consultarEstado $sessao2
Write-Host "Depois do restart:"
Write-Host "  Saldo disponivel: $($depois.carteira.balance) | Reservado: $($depois.carteira.reservedBalance)"
Write-Host "  Livro PETR4 (compra): $($depois.livro.bids | ConvertTo-Json -Compress)"
fecharSessao $sessao2
