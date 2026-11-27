# Entrega Parte 2 — Home Broker (Simulador de Bolsa de Valores)

## 1. Entendimento da sua parte (Home Broker)

Sua responsabilidade na Parte 2 é evoluir exclusivamente a pasta `frontend/` (`index.html`, `style.css`, `app.js` e `app.test.cjs`) para entregar a experiência completa do Home Broker (seção 5 da especificação) e a resiliência do lado cliente (seções 7.2 e 7.5), mantendo o servidor como única fonte de verdade para saldo, carteira e execuções.

### Lista de entregáveis

* **Carteira e patrimônio:** saldo disponível, saldo reservado, patrimônio total, lista de ativos possuídos, quantidade (total e reservada), preço médio e lucro/prejuízo por ativo e consolidado.

* **Mercado e livro de ofertas:** lista de ativos com preço atual, variação percentual, volume negociado e melhores ofertas (melhor compra e melhor venda), além do livro de ofertas (bids/asks ordenados e spread) do ativo selecionado.

* **Ordens e histórico:** envio de compra e venda, cancelamento de ordens abertas do próprio usuário, consulta de status por ID (e por linha da tabela), tabela de ordens com execução parcial e aba de histórico de negociações (trades).

* **Resiliência e alertas:** aviso destacado de rejeição de ordem e de desconexão, bloqueio seguro de operações offline, reconexão automática STOMP e recarga completa de estado por snapshot ao reconectar (já que eventos perdidos durante a queda não são reenviados).

### Conexões com os outros integrantes

| Integrante | Conexão com o Home Broker | 
| ----- | ----- | 
| **Danilo (Motor)** | Consome envio (`/app/orders/create`), consulta (`/app/orders/status`), histórico de ordens (`/app/orders/mine`), cancelamento (`/app/orders/cancel`) e snapshot do livro (`/app/orderbook/snapshot`). Publica atualizações de ordem (`/user/queue/orders`), livro (`/topic/orderbook`), preço/volume (`/topic/market`) e negócios (`/topic/trades`). | 
| **Bruno (Carteira e Banco)** | Consome consultas de carteira (`/app/wallet/me`) e histórico de negociações (`/app/trades/mine`). Devolve saldo disponível, saldo reservado, posições com preço médio e trades persistidos em `/user/queue/replies` (e atualizações após liquidação), refletindo as tabelas `usuarios`, `carteiras`, `ordens` e `historico_negociacoes` do `schema.sql`. | 
| **Filipe (Agentes e Eventos)** | Opcionalmente publica notícias/eventos econômicos (`ECONOMIC_EVENT`) em `/topic/market` ou `/topic/events`, que o Home Broker exibe no feed e como aviso de mercado. | 
| **Sidnei (Falhas e Carga)** | Usa o Home Broker na demonstração de falha controlada (derrubar backend → cliente detecta queda → reconecta automaticamente → recarrega snapshot sem perder consistência). | 

## 2. Convenções da Parte 1 e Plano de Implementação

### Convenções identificadas no código existente

* **Stack e arquitetura:** Vanilla HTML5, CSS3 com variáveis em `:root` (tema escuro `#0b1220` / `#111a2e`, fontes `IBM Plex Sans` e `IBM Plex Mono`) e JavaScript ES6 sem framework nem bundler, usando `@stomp/stompjs@7` via CDN.

* **Nomenclatura e estilo:** funções, estado e IDs HTML em português (`estado`, `desenharSaldo`, `desenharTabela`, `achar`, `fmt`, `log`, `tela-login`, `tela-mercado`); comentários informais e curtos em minúsculas.

* **Contratos do backend:** envelope padrão `MarketEvent<T> = { type, requestId, eventId, timestamp, data }` no STOMP; autenticação REST em `/auth/login` e `/auth/register` retornando `AuthResponse { type, token, userId, username, balance, portfolio }`; criação de ordem via `SubmitOrderMessage { clientOrderId, symbol, side, quantity, price }` sem enviar `userId`.

* **Consistência e testes:** suíte em `frontend/app.test.cjs` usando `node:test`, `node:assert/strict` e `node:vm` validando que o frontend **nunca** altera saldo ou carteira por conta própria em `RECEIVED`, `ACCEPTED` ou modo offline, e mantendo `estado.carteira` como mapa `codigo -> quantidade`.

### Ordem de implementação seguida

1. Preservar todos os IDs, contratos e testes da Parte 1 (mantendo `estado.carteira[codigo]` numérico e adicionando `estado.posicoes` para preço médio e quantidade reservada).

2. Expandir `frontend/index.html` e `frontend/style.css` com resumo patrimonial, status/banners de conexão e rejeição, colunas de volume/melhores ofertas, livro de ofertas, carteira detalhada e painel de abas (Minhas ordens, Histórico de negociações e Feed).

3. Implementar em `frontend/app.js` os cálculos financeiros derivados, normalização/ordenação do livro, fluxo de cancelamento e consulta de status, detecção real de queda (`onWebSocketClose` + `onDisconnect`), e rotina `recarregarEstadoCompleto` disparada no login e em toda reconexão.

4. Manter os 4 testes originais de `frontend/app.test.cjs` intactos e adicionar testes isolados para todos os requisitos da Parte 2.

## 3. Contratos novos para combinar com Danilo, Bruno e Filipe

Como o escopo impede alterar o backend de outros integrantes, o frontend já está pronto e segue os contratos abaixo:

### Para Danilo (Motor) — Contrato fechado

* **Atualizações e cancelamento de ordem:**
  * Cliente solicita cancelamento publicando em `/app/orders/cancel` com `{"orderId": "..."}`.
  * Resposta enviada sempre na fila `/user/queue/orders`, no envelope `MarketEvent` com `type: "ORDER_UPDATED"` e o `status` dentro de `data` (`OrderResult`, ex.: `status: "CANCELLED"`).
  * Em caso de recusa de cancelamento (ex.: ordem de outro usuário ou já executada), enviar `type: "ORDER_CANCEL_REJECTED"` em `/user/queue/orders` com `reason` (`FORBIDDEN`, `ORDER_NOT_FOUND`, `ORDER_NOT_CANCELLABLE`).

* **Execução parcial, total e autonegociação em `OrderResult`:**
  * Manter os campos existentes e incluir `filledQuantity` (e `remainingQuantity`, se aplicável) com `status: "PARTIALLY_FILLED"` ou `"FILLED"`.
  * Em caso de autonegociação, finalizar com `status: "CANCELLED"` e `reason: "SELF_TRADE"`, já que parte da ordem pode ter sido executada antes do cancelamento (`filledQuantity > 0`).

* **Livro de ofertas:**
  * Snapshot sob demanda: cliente publica em `/app/orderbook/snapshot` com `{"symbol": "PETR4"}` → resposta em `/user/queue/replies` com `type: "ORDER_BOOK_SNAPSHOT"`.
  * Atualizações em tempo real: broadcast exclusivo no tópico `/topic/orderbook` com `type: "ORDER_BOOK_UPDATED"`.
  * Payload `data`:

    ```json
    {
      "symbol": "PETR4",
      "bids": [{ "price": 34.90, "quantity": 200 }],
      "asks": [{ "price": 35.10, "quantity": 150 }],
      "bestBid": 34.90,
      "bestAsk": 35.10
    }
    ```

* **Negócios e mercado:**
  * Broadcast exclusivo de negócios no tópico `/topic/trades` com `type: "TRADE_EXECUTED"`:
    ```json
    {
      "tradeId": "...",
      "symbol": "PETR4",
      "price": 35.10,
      "quantity": 100,
      "volume": 1250,
      "executedAt": "..."
    }
    ```
  * Semântica dos campos: `volume` representa o **total acumulado do ativo**, enquanto `quantity` representa **apenas a quantidade daquele negócio**.
  * No `PRICE_UPDATE` em `/topic/market`, incluir `volume`, `bestBid` e `bestAsk` quando disponíveis.

### Para Bruno (Carteira e Banco)

* **Snapshot da carteira:** cliente publica em `/app/wallet/me` com `{}` → resposta em `/user/queue/replies` com `type: "WALLET_SNAPSHOT"` (e enviar `type: "WALLET_UPDATED"` em `/user/queue/replies` ou `/user/queue/orders` após cada liquidação).

  * Payload `data`:

    ```json
    {
      "balance": 8500.00,
      "reservedBalance": 1500.00,
      "positions": [
        { "symbol": "PETR4", "quantity": 100, "reservedQuantity": 20, "averagePrice": 34.50 }
      ]
    }
    ```

* **Histórico de negociações do usuário:** cliente publica em `/app/trades/mine` com `{}` → resposta em `/user/queue/replies` com `type: "TRADE_HISTORY"`, com `data` sendo uma lista de:

  ```json
  {
    "tradeId": "...",
    "orderId": "...",
    "symbol": "PETR4",
    "side": "BUY",
    "quantity": 100,
    "price": 35.00,
    "executedAt": "..."
  }
  ```

### Para Filipe (Eventos Econômicos — opcional)

* Broadcast em `/topic/market` (ou `/topic/events`) com `type: "ECONOMIC_EVENT"` e `data: { "title": "...", "message": "...", "symbol": "PETR4" }`.

## 4. Implementação

### `frontend/index.html`

```html
<!doctype html>
<html lang="pt-BR">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Simulador de Bolsa de Valores</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@500&family=IBM+Plex+Sans:wght@400;500;600&display=swap">
  <link rel="stylesheet" href="style.css">
</head>
<body>

  <main id="tela-login" class="login">
    <svg class="login-fundo" aria-hidden="true" viewBox="0 0 1200 300" preserveAspectRatio="none">
      <polyline points="0,260 100,230 200,245 300,190 400,205 500,150 600,170 700,110 800,130 900,70 1000,90 1100,40 1200,55"
                fill="none" stroke="#1B2A4A" stroke-width="3" vector-effect="non-scaling-stroke"/>
    </svg>

    <form id="form-login" class="cartao login-cartao">
      <div class="logo logo-grande">
        <svg aria-hidden="true" width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="#06202E" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="3 17 9 11 13 15 21 7"/><polyline points="15 7 21 7 21 13"/>
        </svg>
      </div>
      <h1>Simulador de Bolsa de Valores</h1>
      <p class="sub">Pratique operações no mercado com saldo virtual, cuida papai</p>

      <div class="campo-grupo">
        <label for="usuario">Usuário</label>
        <input id="usuario" type="text" placeholder="Seu nome" autocomplete="off">
      </div>
      <div class="campo-grupo">
        <label for="senha">Senha</label>
        <input id="senha" type="password" placeholder="••••••••">
      </div>

      <button id="btn-submit-auth" type="submit" class="btn btn-azul">Entrar no mercado</button>

      <p class="auth-toggle">
        <span id="label-toggle-auth">Não tem conta?</span>
        <button id="btn-toggle-auth" type="button" class="link link-azul">Criar conta</button>
      </p>

      <p class="nota">Crie sua conta ou acerte seu login para operar no mercado.</p>
    </form>
  </main>

  <div id="tela-mercado" hidden>

    <header class="topo">
      <div class="topo-esq">
        <div class="logo">
          <svg aria-hidden="true" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#06202E" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="3 17 9 11 13 15 21 7"/><polyline points="15 7 21 7 21 13"/>
          </svg>
        </div>
        <div class="identidade">
          <strong id="ola">Olá!</strong>
          <span id="status-conexao" class="status-pill status-desconectado">Desconectado</span>
        </div>
      </div>

      <div class="topo-dir">
        <div class="resumo-financeiro">
          <div class="saldo">
            <span>Saldo disponível</span>
            <strong id="saldo" class="mono">R$ 0,00</strong>
          </div>
          <div class="saldo saldo-secundario">
            <span>Reservado</span>
            <strong id="saldo-reservado" class="mono">R$ 0,00</strong>
          </div>
          <div class="saldo saldo-secundario">
            <span>Patrimônio total</span>
            <strong id="patrimonio" class="mono">R$ 0,00</strong>
          </div>
          <div class="saldo saldo-secundario">
            <span>Lucro / Prejuízo</span>
            <strong id="lucro-prejuizo" class="mono neutro">R$ 0,00 (0,00%)</strong>
          </div>
        </div>

        <div class="acoes-topo">
          <button id="btn-sincronizar" type="button" class="btn-mini">Sincronizar</button>
          <button id="btn-sair" type="button" class="link">Sair</button>
        </div>
      </div>
    </header>

    <main class="conteudo">
      <!-- aviso persistente quando a conexao cai -->
      <div id="banner-conexao" class="banner banner-erro" role="alert" hidden>
        <div class="banner-texto">
          <strong>Conexão perdida com o servidor.</strong>
          <span id="banner-conexao-msg">Tentando reconectar automaticamente. Eventos perdidos não são reenviados; o estado completo será recarregado ao reconectar.</span>
        </div>
        <button id="btn-reconectar" type="button" class="btn-mini">Reconectar agora</button>
      </div>

      <!-- aviso de rejeicao de ordem ou erro operacional -->
      <div id="banner-alerta" class="banner banner-aviso" role="status" hidden>
        <span id="banner-alerta-msg"></span>
        <button id="btn-fechar-alerta" type="button" class="link">Fechar</button>
      </div>

      <div class="colunas">
        <!-- mercado -->
        <section class="cartao mercado">
          <div class="cabecalho-secao">
            <h2>Mercado</h2>
            <span class="nota">Clique em um ativo para ver o livro e operar</span>
          </div>
          <div class="tabela-wrap">
            <div class="linha linha-mercado cabecalho-tabela">
              <span>Ativo</span>
              <span class="dir">Preço atual</span>
              <span class="dir">Variação</span>
              <span class="dir">Volume</span>
              <span class="dir">Melhor compra</span>
              <span class="dir">Melhor venda</span>
            </div>
            <div id="tabela" class="tabela"></div>
          </div>
          <p class="nota">Os preços, volumes e ofertas são atualizados em tempo real pelo servidor.</p>
        </section>

        <!-- livro de ofertas -->
        <section class="cartao livro">
          <div class="cabecalho-secao">
            <h2>Livro de ofertas · <span id="livro-ativo" class="mono">PETR4</span></h2>
            <span id="livro-spread" class="nota mono">Spread: —</span>
          </div>
          <div class="livro-grade">
            <div class="livro-lado">
              <div class="livro-cabecalho alta">
                <span>Compra (Bid)</span>
                <span class="dir">Qtd</span>
              </div>
              <div id="livro-compras" class="livro-lista"></div>
            </div>
            <div class="livro-lado">
              <div class="livro-cabecalho baixa">
                <span>Venda (Ask)</span>
                <span class="dir">Qtd</span>
              </div>
              <div id="livro-vendas" class="livro-lista"></div>
            </div>
          </div>
          <p class="nota">Clique em um preço do livro para preencher a boleta.</p>
        </section>

        <!-- boleta -->
        <section class="cartao boleta">
          <h2>Boleta</h2>

          <div class="campo-grupo">
            <label for="ativo">Selecione o ativo</label>
            <select id="ativo"></select>
            <span class="nota" id="posicao"></span>
          </div>

          <div class="campo-grupo">
            <label for="qtd">Quantidade</label>
            <input id="qtd" type="text" inputmode="numeric" value="100" class="mono">
          </div>

          <div class="campo-grupo">
            <label for="preco">Preço (R$)</label>
            <input id="preco" type="text" inputmode="decimal" value="35,00" class="mono">
            <span class="nota" id="preco-mercado"></span>
            <span class="nota mono" id="total-ordem"></span>
          </div>

          <p id="aviso-boleta" class="nota erro" hidden></p>

          <div class="botoes">
            <button id="btn-comprar" type="button" class="btn btn-compra">COMPRAR</button>
            <button id="btn-vender" type="button" class="btn btn-venda">VENDER</button>
          </div>
        </section>
      </div>

      <!-- carteira detalhada -->
      <section class="cartao carteira">
        <div class="cabecalho-secao">
          <h2>Minha carteira</h2>
          <span id="resumo-carteira" class="nota mono">Em ativos: R$ 0,00</span>
        </div>
        <div class="tabela-wrap">
          <div class="linha linha-carteira cabecalho-tabela">
            <span>Ativo</span>
            <span class="dir">Quantidade</span>
            <span class="dir">Preço médio</span>
            <span class="dir">Preço atual</span>
            <span class="dir">Valor atual</span>
            <span class="dir">Lucro / Prejuízo</span>
          </div>
          <div id="tabela-carteira" class="tabela"></div>
        </div>
      </section>

      <!-- ordens, historico de negociacoes e feed -->
      <section class="cartao operacoes">
        <div class="abas">
          <button id="tab-ordens" type="button" class="aba ativa">Minhas ordens</button>
          <button id="tab-negocios" type="button" class="aba">Histórico de negociações</button>
          <button id="tab-feed" type="button" class="aba">Feed de atualizações</button>
        </div>

        <div id="painel-ordens" class="painel-aba">
          <div class="barra-ordens">
            <div class="campo-inline">
              <label for="filtro-ordens">Exibir</label>
              <select id="filtro-ordens">
                <option value="todas">Todas as ordens</option>
                <option value="abertas">Somente abertas</option>
              </select>
            </div>
            <div class="consulta-ordem">
              <input id="input-consulta-ordem" type="text" placeholder="Consultar status por orderId..." class="mono">
              <button id="btn-consultar-ordem" type="button" class="btn-mini">Consultar</button>
            </div>
          </div>

          <div id="resultado-consulta-ordem" class="consulta-resultado" hidden></div>

          <div class="tabela-wrap">
            <div class="linha linha-ordens cabecalho-tabela">
              <span>Ordem</span>
              <span>Ativo</span>
              <span>Lado</span>
              <span class="dir">Qtd (Exec/Total)</span>
              <span class="dir">Preço</span>
              <span>Status</span>
              <span class="dir">Ações</span>
            </div>
            <div id="tabela-ordens" class="tabela"></div>
          </div>
        </div>

        <div id="painel-negocios" class="painel-aba" hidden>
          <div class="tabela-wrap">
            <div class="linha linha-negocios cabecalho-tabela">
              <span>Hora</span>
              <span>Ativo</span>
              <span>Lado</span>
              <span class="dir">Quantidade</span>
              <span class="dir">Preço executado</span>
              <span class="dir">Total</span>
            </div>
            <div id="tabela-negocios" class="tabela"></div>
          </div>
        </div>

        <div id="painel-feed" class="painel-aba" hidden>
          <div id="feed" class="feed"></div>
        </div>
      </section>
    </main>
  </div>

  <script src="https://cdn.jsdelivr.net/npm/@stomp/stompjs@7/bundles/stomp.umd.min.js"></script>
  <script src="app.js"></script>
</body>
</html>
```

### `frontend/style.css`

```css
/* tema e variaveis globais */
:root {
  --bg: #0b1220;
  --painel: #111a2e;
  --linha-bg: #0f1830;
  --linha-hover: #152242;
  --borda: #1f2b45;
  --campo: #2a3856;
  --texto: #e6ebf5;
  --suave: #9aa8c4;
  --azul: #38bdf8;
  --azul-hover: #7dd3fc;
  --azul-texto: #06202e;
  --alta: #34d399;
  --baixa: #f87171;
  --aviso: #f59e0b;
  --compra: #15803d;
  --venda: #b91c1c;
  --sans: "IBM Plex Sans", system-ui, sans-serif;
  --mono: "IBM Plex Mono", ui-monospace, monospace;
}

* {
  box-sizing: border-box;
}

[hidden] {
  display: none !important;
}

body {
  margin: 0;
  background: var(--bg);
  color: var(--texto);
  font-family: var(--sans);
}

h1,
h2,
p {
  margin: 0;
}

.mono {
  font-family: var(--mono);
}

.dir {
  text-align: right;
}

.cartao {
  background: var(--painel);
  border: 1px solid var(--borda);
  border-radius: 16px;
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.cartao h2 {
  font-size: 16px;
  font-weight: 600;
}

.cabecalho-secao {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.logo {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: var(--azul);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.logo-grande {
  width: 48px;
  height: 48px;
  border-radius: 12px;
}

.campo-grupo {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

label {
  font-size: 13px;
  font-weight: 500;
  color: var(--suave);
}

.nota {
  font-size: 12px;
  color: var(--suave);
}

input,
select {
  height: 44px;
  width: 100%;
  padding: 0 14px;
  border-radius: 10px;
  border: 1px solid var(--campo);
  background: var(--bg);
  color: var(--texto);
  font-family: inherit;
  font-size: 15px;
}

input::placeholder {
  color: #7886a3;
}

input:focus,
select:focus,
button:focus-visible {
  outline: 2px solid var(--azul);
  outline-offset: 1px;
}

.btn {
  height: 46px;
  border: 0;
  border-radius: 10px;
  font-family: inherit;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
}

.btn:disabled,
.btn-mini:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.btn-azul {
  background: var(--azul);
  color: var(--azul-texto);
}

.btn-azul:hover:not(:disabled) {
  background: var(--azul-hover);
}

.btn-compra {
  background: var(--compra);
  color: #fff;
}

.btn-venda {
  background: var(--venda);
  color: #fff;
}

.btn-compra:hover:not(:disabled),
.btn-venda:hover:not(:disabled) {
  filter: brightness(1.1);
}

.btn-mini {
  height: 34px;
  padding: 0 12px;
  border-radius: 8px;
  border: 1px solid var(--campo);
  background: var(--bg);
  color: var(--texto);
  font-family: inherit;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
}

.btn-mini:hover:not(:disabled) {
  border-color: var(--azul);
  color: var(--azul);
}

.btn-mini-perigo {
  border-color: rgba(248, 113, 113, 0.45);
  color: var(--baixa);
}

.btn-mini-perigo:hover:not(:disabled) {
  border-color: var(--baixa);
  background: rgba(248, 113, 113, 0.12);
  color: var(--baixa);
}

.link {
  background: none;
  border: 0;
  padding: 0;
  color: var(--suave);
  font-family: inherit;
  font-size: 14px;
  cursor: pointer;
}

.link:hover {
  color: var(--texto);
}

/* login */
.login {
  position: relative;
  min-height: 100vh;
  padding: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.login-fundo {
  position: absolute;
  left: 0;
  bottom: 0;
  width: 100%;
  height: 45%;
}

.login-cartao {
  position: relative;
  width: 100%;
  max-width: 420px;
  padding: 36px 32px;
  gap: 22px;
}

.login-cartao h1 {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.2;
}

.sub {
  font-size: 14px;
  line-height: 1.5;
  color: var(--suave);
  margin-top: -8px;
}

.auth-toggle {
  display: flex;
  justify-content: center;
  gap: 6px;
  font-size: 14px;
  color: var(--suave);
  margin-top: -6px;
}

.link-azul {
  color: var(--azul);
  font-weight: 600;
}

.link-azul:hover {
  color: var(--azul-hover);
}

#erro-auth {
  font-size: 13px;
  margin-top: -8px;
}

/* cabecalho do home broker */
#tela-mercado {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.topo {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 32px;
  background: var(--painel);
  border-bottom: 1px solid var(--borda);
}

.topo-esq,
.topo-dir {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
}

.topo-esq {
  gap: 12px;
}

.identidade {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.identidade strong {
  font-size: 17px;
}

.topo-dir {
  gap: 20px;
}

.resumo-financeiro {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
}

.acoes-topo {
  display: flex;
  align-items: center;
  gap: 14px;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  width: fit-content;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  border: 1px solid var(--borda);
}

.status-conectado {
  color: var(--alta);
  border-color: rgba(52, 211, 153, 0.35);
  background: rgba(52, 211, 153, 0.1);
}

.status-reconectando {
  color: var(--aviso);
  border-color: rgba(245, 158, 11, 0.4);
  background: rgba(245, 158, 11, 0.12);
}

.status-desconectado {
  color: var(--baixa);
  border-color: rgba(248, 113, 113, 0.4);
  background: rgba(248, 113, 113, 0.12);
}

.status-demo {
  color: var(--azul);
  border-color: rgba(56, 189, 248, 0.35);
  background: rgba(56, 189, 248, 0.1);
}

.saldo {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}

.saldo span {
  font-size: 11px;
  color: var(--suave);
}

.saldo strong {
  font-size: 19px;
  font-weight: 500;
  color: var(--alta);
}

.saldo-secundario strong {
  font-size: 16px;
  color: var(--texto);
}

/* banners de alerta */
.banner {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 18px;
  border-radius: 12px;
  font-size: 14px;
}

.banner-texto {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.banner-erro {
  background: rgba(185, 28, 28, 0.18);
  border: 1px solid rgba(248, 113, 113, 0.45);
  color: #fecaca;
}

.banner-aviso {
  background: rgba(245, 158, 11, 0.14);
  border: 1px solid rgba(245, 158, 11, 0.45);
  color: #fde68a;
}

.banner-ok {
  background: rgba(21, 128, 61, 0.18);
  border: 1px solid rgba(52, 211, 153, 0.45);
  color: #bbf7d0;
}

/* layout principal */
.conteudo {
  flex: 1;
  padding: 24px 32px 32px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.colunas {
  display: flex;
  flex-wrap: wrap;
  align-items: stretch;
  gap: 20px;
}

.mercado {
  flex: 3 1 520px;
  min-width: 0;
}

.livro {
  flex: 2 1 320px;
  min-width: 0;
}

.boleta {
  flex: 1.5 1 280px;
  min-width: 0;
  gap: 14px;
}

/* tabelas */
.tabela-wrap {
  width: 100%;
  overflow-x: auto;
}

.linha {
  display: grid;
  gap: 10px;
  align-items: center;
}

.linha-mercado {
  grid-template-columns: 1.5fr 1fr 0.9fr 0.8fr 1fr 1fr;
  min-width: 560px;
}

.linha-carteira {
  grid-template-columns: 1.3fr 1fr 1fr 1fr 1fr 1.3fr;
  min-width: 580px;
}

.linha-ordens {
  grid-template-columns: 0.9fr 0.8fr 0.8fr 1fr 0.9fr 1.2fr 1.3fr;
  min-width: 680px;
}

.linha-negocios {
  grid-template-columns: 0.9fr 0.9fr 0.8fr 0.9fr 1fr 1fr;
  min-width: 540px;
}

.cabecalho-tabela {
  padding: 0 12px 8px;
  border-bottom: 1px solid var(--borda);
  font-size: 12px;
  font-weight: 500;
  color: var(--suave);
}

.tabela {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 6px;
}

.tabela .linha {
  padding: 11px 12px;
  border-radius: 10px;
  background: var(--linha-bg);
  font-size: 14px;
}

.linha-clicavel {
  cursor: pointer;
  border: 1px solid transparent;
  transition: background 0.12s ease, border-color 0.12s ease;
}

.linha-clicavel:hover {
  background: var(--linha-hover);
}

.linha-selecionada {
  border-color: var(--azul);
  background: var(--linha-hover);
}

.tabela .codigo {
  font-weight: 600;
}

.nome-empresa {
  font-weight: 400;
  font-size: 11px;
  color: var(--suave);
  margin-left: 6px;
}

.sub-dado {
  display: block;
  font-size: 11px;
  color: var(--suave);
}

.vazio {
  padding: 16px 12px;
  text-align: center;
  color: var(--suave);
  font-size: 13px;
  background: var(--linha-bg);
  border-radius: 10px;
}

/* livro de ofertas */
.livro-grade {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  flex: 1;
}

.livro-lado {
  display: flex;
  flex-direction: column;
  gap: 6px;
  background: var(--linha-bg);
  border-radius: 10px;
  padding: 10px;
}

.livro-cabecalho {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  font-weight: 600;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--borda);
}

.livro-lista {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.livro-nivel {
  display: flex;
  justify-content: space-between;
  padding: 6px 8px;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
}

.livro-nivel:hover {
  background: var(--linha-hover);
}

.livro-nivel-topo {
  background: rgba(56, 189, 248, 0.08);
  font-weight: 600;
}

.botoes {
  display: flex;
  gap: 12px;
  margin-top: 4px;
}

.botoes .btn {
  flex: 1;
}

/* abas e gestao de ordens */
.abas {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  border-bottom: 1px solid var(--borda);
  padding-bottom: 12px;
}

.aba {
  background: var(--bg);
  color: var(--suave);
  border: 1px solid var(--borda);
  border-radius: 8px;
  padding: 8px 14px;
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.aba:hover {
  color: var(--texto);
}

.aba.ativa {
  background: var(--azul);
  color: var(--azul-texto);
  border-color: var(--azul);
}

.painel-aba {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.barra-ordens {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.campo-inline {
  display: flex;
  align-items: center;
  gap: 8px;
}

.campo-inline select {
  width: auto;
  height: 36px;
  font-size: 13px;
}

.consulta-ordem {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1 1 280px;
  max-width: 420px;
}

.consulta-ordem input {
  height: 36px;
  font-size: 13px;
}

.consulta-resultado {
  padding: 10px 14px;
  border-radius: 10px;
  background: var(--linha-bg);
  border: 1px solid var(--borda);
  font-size: 13px;
}

.acoes-ordem {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}

.badge-status {
  display: inline-block;
  padding: 3px 8px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 600;
  background: rgba(154, 168, 196, 0.14);
  color: var(--texto);
}

.badge-ok {
  background: rgba(52, 211, 153, 0.15);
  color: var(--alta);
}

.badge-aviso {
  background: rgba(245, 158, 11, 0.16);
  color: var(--aviso);
}

.badge-erro {
  background: rgba(248, 113, 113, 0.16);
  color: var(--baixa);
}

/* feed */
.feed {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.feed-item {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  font-size: 14px;
  line-height: 1.5;
}

.feed-item time {
  font-family: var(--mono);
  font-size: 13px;
  color: var(--suave);
}

/* cores utilitarias */
.alta,
.ok {
  color: var(--alta);
}

.baixa,
.erro {
  color: var(--baixa);
}

.aviso {
  color: var(--aviso);
}

.neutro {
  color: var(--suave);
}

/* responsividade */
@media (max-width: 720px) {
  .topo,
  .conteudo {
    padding-left: 16px;
    padding-right: 16px;
  }

  .saldo {
    align-items: flex-start;
  }

  .livro-grade {
    grid-template-columns: 1fr;
  }
}
```

### `frontend/app.js`

```javascript
const MAX_LOGS = 25;
const MAX_NEGOCIOS = 100;
const RECONNECT_DELAY_MS = 3000;

// servidores locais sem proxy continuam acessando o backend diretamente
const WS_LOCAL = location.protocol === "file:" || ["5173", "5500"].includes(location.port);
const WS_PROTOCOL = location.protocol === "https:" ? "wss:" : "ws:";
const WS_HOST = WS_LOCAL ? `${location.hostname || "localhost"}:8086` : location.host;
const WS_URL = `${WS_PROTOCOL}//${WS_HOST}/ws`;
const AUTH_BASE = WS_LOCAL ? `${WS_PROTOCOL === "wss:" ? "https:" : "http:"}//${WS_HOST}` : "";

const RAZOES_REJEICAO = {
  INVALID_FIELDS: "Dados da ordem inválidos.",
  INVALID_QUANTITY: "Quantidade inválida.",
  INVALID_PRICE: "Preço inválido.",
  UNKNOWN_ASSET: "Ativo não encontrado no mercado.",
  INSUFFICIENT_FUNDS: "Saldo insuficiente para esta compra.",
  INSUFFICIENT_SHARES: "Você não possui ações suficientes para vender.",
  SELF_TRADE: "Autonegociação não permitida pelo mercado.",
  ORDER_NOT_FOUND: "Ordem não encontrada.",
  ORDER_NOT_CANCELLABLE: "Esta ordem não pode mais ser cancelada.",
  FORBIDDEN: "Você não tem permissão para alterar esta ordem.",
  SYSTEM_BUSY: "Sistema sobrecarregado. Fila cheia.",
  INTERNAL_ERROR: "Erro interno do servidor.",
};

const LABELS_STATUS = {
  RECEIVED: "Recebida",
  ACCEPTED: "Aberta",
  OPEN: "Aberta",
  PARTIALLY_FILLED: "Parcial",
  FILLED: "Executada",
  CANCELLED: "Cancelada",
  REJECTED: "Rejeitada",
};

const STATUS_CANCELAVEIS = new Set(["RECEIVED", "ACCEPTED", "OPEN", "PARTIALLY_FILLED"]);

const estado = {
  saldo: 10000,
  saldoReservado: 0,
  ativos: [
    { codigo: "PETR4", nome: "Petrobras", preco: 35.0, base: 35.0, volume: 0, melhorCompra: null, melhorVenda: null },
    { codigo: "VALE3", nome: "Vale", preco: 68.0, base: 68.0, volume: 0, melhorCompra: null, melhorVenda: null },
    { codigo: "ITUB4", nome: "Itaú Unibanco", preco: 30.5, base: 30.5, volume: 0, melhorCompra: null, melhorVenda: null },
    { codigo: "MGLU3", nome: "Magazine Luiza", preco: 2.1, base: 2.1, volume: 0, melhorCompra: null, melhorVenda: null },
  ],
  // mapa simples codigo -> quantidade total mantido para compatibilidade com a parte 1
  carteira: { PETR4: 0, VALE3: 0, ITUB4: 0, MGLU3: 0 },
  // detalhe por ativo vindo do servidor (quantidade total, reservada e preco medio)
  posicoes: {
    PETR4: { quantidade: 0, reservada: 0, precoMedio: 0 },
    VALE3: { quantidade: 0, reservada: 0, precoMedio: 0 },
    ITUB4: { quantidade: 0, reservada: 0, precoMedio: 0 },
    MGLU3: { quantidade: 0, reservada: 0, precoMedio: 0 },
  },
  livros: {},
  ordens: [],
  negocios: [],
  consultaOrdem: null,
  filtroOrdens: "todas",
  abaAtiva: "ordens",
  ativoSelecionado: "PETR4",
  conexao: {
    status: "desconectado",
    jaConectou: false,
    tentativas: 0,
    ultimaSincronizacao: null,
  },
  alerta: null,
  logs: [],
  usuario: null,
  modoAuth: "login", // "login" ou "register"
};

const $ = (id) => document.getElementById(id);
const fmt = new Intl.NumberFormat("pt-BR", {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
}).format;
const fmtInt = new Intl.NumberFormat("pt-BR", {
  maximumFractionDigits: 0,
}).format;
const agora = () => new Date().toLocaleTimeString("pt-BR", { hour12: false });
const achar = (codigo) => estado.ativos.find((a) => a.codigo === codigo);

// helpers defensivos para rodar igual no navegador e nos testes em node:vm
function esc(valor) {
  return String(valor ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function setTexto(id, texto) {
  const el = $(id);
  if (el) el.textContent = texto;
}

function setHtml(id, html) {
  const el = $(id);
  if (el) el.innerHTML = html;
}

function setHidden(id, oculto) {
  const el = $(id);
  if (el) el.hidden = Boolean(oculto);
}

function setClasse(id, classe) {
  const el = $(id);
  if (el) el.className = classe;
}

function gerarUuid() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `cid-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
}

function storageGet(chave) {
  return typeof localStorage !== "undefined" ? localStorage.getItem(chave) : null;
}

function storageSet(chave, valor) {
  if (typeof localStorage !== "undefined") localStorage.setItem(chave, valor);
}

function storageRemove(chave) {
  if (typeof localStorage !== "undefined") localStorage.removeItem(chave);
}

function formatarHora(valor) {
  if (!valor) return agora();
  const data = new Date(valor);
  if (Number.isNaN(data.getTime())) return String(valor);
  return data.toLocaleTimeString("pt-BR", { hour12: false });
}

function ehOrdemCancelavel(status) {
  return STATUS_CANCELAVEIS.has(String(status || "").toUpperCase());
}

function garantirPosicao(codigo) {
  if (!estado.posicoes[codigo]) {
    estado.posicoes[codigo] = { quantidade: 0, reservada: 0, precoMedio: 0 };
  }
  if (estado.carteira[codigo] === undefined) {
    estado.carteira[codigo] = estado.posicoes[codigo].quantidade;
  }
  return estado.posicoes[codigo];
}

// --- calculos financeiros derivados (somente exibicao; servidor e fonte de verdade) ---

function calcularVariacao(ativo) {
  if (!ativo || !(ativo.base > 0)) return 0;
  return ((ativo.preco - ativo.base) / ativo.base) * 100;
}

function calcularPosicao(codigo) {
  const pos = estado.posicoes[codigo] || { quantidade: estado.carteira[codigo] || 0, reservada: 0, precoMedio: 0 };
  const ativo = achar(codigo);
  const quantidade = Number(pos.quantidade ?? estado.carteira[codigo] ?? 0);
  const reservada = Number(pos.reservada ?? 0);
  const precoMedio = Number(pos.precoMedio ?? 0);
  const precoAtual = ativo ? Number(ativo.preco) : precoMedio;
  const valorAtual = quantidade * precoAtual;
  const custoTotal = quantidade * precoMedio;
  const lucroPrejuizo = quantidade > 0 && precoMedio > 0 ? valorAtual - custoTotal : 0;
  const lucroPercentual = custoTotal > 0 ? (lucroPrejuizo / custoTotal) * 100 : 0;

  return {
    codigo,
    nome: ativo?.nome || "",
    quantidade,
    reservada,
    precoMedio,
    precoAtual,
    valorAtual,
    custoTotal,
    lucroPrejuizo,
    lucroPercentual,
  };
}

function calcularResumoCarteira() {
  const codigos = new Set([
    ...Object.keys(estado.carteira),
    ...Object.keys(estado.posicoes),
  ]);

  let valorEmAtivos = 0;
  let custoTotal = 0;
  let lucroPrejuizoTotal = 0;
  const posicoesAtivas = [];

  for (const codigo of codigos) {
    const item = calcularPosicao(codigo);
    if (item.quantidade > 0 || item.reservada > 0) {
      valorEmAtivos += item.valorAtual;
      custoTotal += item.custoTotal;
      lucroPrejuizoTotal += item.lucroPrejuizo;
      posicoesAtivas.push(item);
    }
  }

  posicoesAtivas.sort((a, b) => a.codigo.localeCompare(b.codigo));

  const saldoDisponivel = Number(estado.saldo || 0);
  const saldoReservado = Number(estado.saldoReservado || 0);
  const patrimonioTotal = saldoDisponivel + saldoReservado + valorEmAtivos;
  const lucroPercentualTotal = custoTotal > 0 ? (lucroPrejuizoTotal / custoTotal) * 100 : 0;

  return {
    saldoDisponivel,
    saldoReservado,
    valorEmAtivos,
    patrimonioTotal,
    custoTotal,
    lucroPrejuizoTotal,
    lucroPercentualTotal,
    posicoesAtivas,
  };
}

function normalizarNiveisLivro(lista, direcao) {
  if (!Array.isArray(lista)) return [];
  const niveis = lista
    .map((n) => ({
      preco: Number(n.price ?? n.preco ?? n.preco_alvo),
      quantidade: Number(n.quantity ?? n.quantidade ?? n.remainingQuantity ?? 0),
    }))
    .filter((n) => Number.isFinite(n.preco) && n.preco > 0 && Number.isFinite(n.quantidade) && n.quantidade > 0);

  niveis.sort((a, b) => (direcao === "desc" ? b.preco - a.preco : a.preco - b.preco));
  return niveis;
}

function normalizarLivro(dados) {
  const bids = normalizarNiveisLivro(dados?.bids || dados?.compras || dados?.buyOrders, "desc");
  const asks = normalizarNiveisLivro(dados?.asks || dados?.vendas || dados?.sellOrders, "asc");
  const melhorCompra = dados?.bestBid != null
    ? Number(dados.bestBid)
    : (bids.length > 0 ? bids[0].preco : null);
  const melhorVenda = dados?.bestAsk != null
    ? Number(dados.bestAsk)
    : (asks.length > 0 ? asks[0].preco : null);
  const spread = melhorCompra != null && melhorVenda != null
    ? Math.max(0, melhorVenda - melhorCompra)
    : null;

  return { bids, asks, melhorCompra, melhorVenda, spread };
}

// --- aplicacao de dados vindos do servidor ---

function aplicarCarteiraMapa(mapa) {
  if (!mapa || typeof mapa !== "object") return;
  for (const [codigo, valor] of Object.entries(mapa)) {
    const pos = garantirPosicao(codigo);
    if (typeof valor === "object" && valor !== null) {
      const qtd = Number(valor.quantity ?? valor.quantidade ?? 0);
      const res = Number(valor.reservedQuantity ?? valor.quantidadeReservada ?? valor.reservada ?? 0);
      const pm = Number(valor.averagePrice ?? valor.precoMedio ?? valor.preco_medio ?? pos.precoMedio ?? 0);
      pos.quantidade = qtd;
      pos.reservada = res;
      pos.precoMedio = pm;
      estado.carteira[codigo] = qtd;
    } else {
      const qtd = Number(valor || 0);
      pos.quantidade = qtd;
      estado.carteira[codigo] = qtd;
    }
  }
}

function aplicarSnapshotCarteira(dados, substituirTudo = false) {
  if (!dados || typeof dados !== "object") return;

  const saldo = dados.saldo ?? dados.balance ?? dados.availableBalance;
  if (saldo != null) {
    estado.saldo = Number(saldo);
  }

  const reservado = dados.saldoReservado ?? dados.reservedBalance ?? dados.blockedBalance;
  if (reservado != null) {
    estado.saldoReservado = Number(reservado);
  }

  const listaPosicoes = dados.positions || dados.posicoes || dados.assets;
  const mapaCarteira = dados.carteira || dados.portfolio;

  if (substituirTudo && (Array.isArray(listaPosicoes) || mapaCarteira)) {
    Object.keys(estado.carteira).forEach((k) => (estado.carteira[k] = 0));
    Object.keys(estado.posicoes).forEach((k) => {
      estado.posicoes[k] = { quantidade: 0, reservada: 0, precoMedio: 0 };
    });
  }

  if (Array.isArray(listaPosicoes)) {
    listaPosicoes.forEach((p) => {
      const codigo = p.symbol || p.ticker || p.codigo || p.assetCode;
      if (!codigo) return;
      const pos = garantirPosicao(codigo);
      const disponivel = p.availableQuantity != null ? Number(p.availableQuantity) : null;
      const reservada = Number(p.reservedQuantity ?? p.quantidadeReservada ?? p.reservada ?? 0);
      const total = p.quantity != null
        ? Number(p.quantity)
        : (p.quantidade != null ? Number(p.quantidade) : (disponivel != null ? disponivel + reservada : 0));
      const precoMedio = Number(p.averagePrice ?? p.precoMedio ?? p.preco_medio ?? 0);

      pos.quantidade = total;
      pos.reservada = reservada;
      pos.precoMedio = precoMedio;
      estado.carteira[codigo] = total;
    });
  } else if (mapaCarteira) {
    aplicarCarteiraMapa(mapaCarteira);
  }

  desenharResumoFinanceiro();
  desenharCarteira();
  desenharBoleta();
}

function aplicarPreco(codigo, novoPreco, nome, extras = {}) {
  let ativo = achar(codigo);
  let adicionou = false;

  if (!ativo) {
    ativo = {
      codigo,
      nome: nome || "",
      preco: novoPreco,
      base: novoPreco,
      volume: 0,
      melhorCompra: null,
      melhorVenda: null,
    };
    estado.ativos.push(ativo);
    garantirPosicao(codigo);
    adicionou = true;
  } else if (nome && !ativo.nome) {
    ativo.nome = nome;
  }

  let mudou = adicionou;
  const precoAnterior = ativo.preco;

  if (novoPreco != null && Number.isFinite(novoPreco) && novoPreco !== ativo.preco) {
    ativo.preco = novoPreco;
    mudou = true;
  }
  if (extras.volume != null && Number.isFinite(Number(extras.volume))) {
    const vol = Number(extras.volume);
    if (vol !== ativo.volume) {
      ativo.volume = vol;
      mudou = true;
    }
  }
  if (extras.melhorCompra !== undefined && extras.melhorCompra !== ativo.melhorCompra) {
    ativo.melhorCompra = extras.melhorCompra != null ? Number(extras.melhorCompra) : null;
    mudou = true;
  }
  if (extras.melhorVenda !== undefined && extras.melhorVenda !== ativo.melhorVenda) {
    ativo.melhorVenda = extras.melhorVenda != null ? Number(extras.melhorVenda) : null;
    mudou = true;
  }

  if (adicionou) atualizarSelectAtivos();
  if (!mudou) return;

  desenharTabela();
  desenharBoleta();
  desenharCarteira();
  desenharResumoFinanceiro();

  if (novoPreco != null && novoPreco !== precoAnterior && !extras.silencioso) {
    log(`Preço da ${codigo} atualizou para R$ ${fmt(novoPreco)}.`);
  }
}

function aplicarLivro(symbol, dados) {
  if (!symbol) return;
  const livro = normalizarLivro(dados);
  estado.livros[symbol] = livro;

  const ativo = achar(symbol);
  if (ativo) {
    ativo.melhorCompra = livro.melhorCompra;
    ativo.melhorVenda = livro.melhorVenda;
  }

  desenharTabela();
  if (estado.ativoSelecionado === symbol) {
    desenharLivro();
  }
}

function normalizarOrdem(d) {
  const total = Number(d.quantity ?? d.quantidade ?? 0);
  const executada = d.filledQuantity != null
    ? Number(d.filledQuantity)
    : (d.executedQuantity != null ? Number(d.executedQuantity) : (d.status === "FILLED" ? total : 0));
  const restante = d.remainingQuantity != null
    ? Number(d.remainingQuantity)
    : Math.max(0, total - executada);

  return {
    orderId: d.orderId || d.id || null,
    clientOrderId: d.clientOrderId || null,
    symbol: d.symbol || d.ticker || d.codigo || "",
    side: d.side || d.tipo || "",
    quantity: total,
    filledQuantity: executada,
    remainingQuantity: restante,
    price: d.price != null ? Number(d.price) : (d.preco_alvo != null ? Number(d.preco_alvo) : null),
    status: String(d.status || "RECEIVED").toUpperCase(),
    reason: d.reason || d.motivo || null,
    updatedAt: d.updatedAt || d.createdAt || d.timestamp || agora(),
  };
}

function registrarOrdem(d) {
  const ordem = normalizarOrdem(d);
  if (!ordem.orderId && !ordem.clientOrderId) return ordem;

  const idx = estado.ordens.findIndex((o) =>
    (ordem.orderId && o.orderId === ordem.orderId) ||
    (ordem.clientOrderId && o.clientOrderId === ordem.clientOrderId)
  );

  if (idx >= 0) {
    estado.ordens[idx] = { ...estado.ordens[idx], ...ordem };
  } else {
    estado.ordens.unshift(ordem);
  }
  desenharOrdens();
  return ordem;
}

function normalizarNegocio(d) {
  const quantity = Number(d.quantity ?? d.quantidade ?? d.quantidade_executada ?? 0);
  const price = Number(d.price ?? d.preco ?? d.preco_executado ?? 0);
  return {
    tradeId: d.tradeId || d.id || `${d.symbol || ""}-${d.executedAt || Date.now()}-${quantity}-${price}`,
    orderId: d.orderId || null,
    symbol: d.symbol || d.ticker || d.codigo || "",
    side: d.side || d.tipo || "",
    quantity,
    price,
    total: quantity * price,
    executedAt: d.executedAt || d.executado_em || d.timestamp || agora(),
  };
}

function registrarNegocio(d) {
  const negocio = normalizarNegocio(d);
  if (!negocio.symbol || !(negocio.quantity > 0)) return;
  const jaExiste = estado.negocios.some((n) => n.tradeId === negocio.tradeId);
  if (!jaExiste) {
    estado.negocios.unshift(negocio);
    if (estado.negocios.length > MAX_NEGOCIOS) estado.negocios.length = MAX_NEGOCIOS;
    desenharNegocios();
  }
}

// --- renderizacao da interface ---

function desenharSaldo() {
  setTexto("saldo", "R$ " + fmt(estado.saldo));
}

function desenharResumoFinanceiro() {
  const r = calcularResumoCarteira();
  desenharSaldo();
  setTexto("saldo-reservado", "R$ " + fmt(r.saldoReservado));
  setTexto("patrimonio", "R$ " + fmt(r.patrimonioTotal));

  const sinal = r.lucroPrejuizoTotal > 0.005 ? "+" : r.lucroPrejuizoTotal < -0.005 ? "−" : "";
  const classe = r.lucroPrejuizoTotal > 0.005 ? "mono alta" : r.lucroPrejuizoTotal < -0.005 ? "mono baixa" : "mono neutro";
  setTexto(
    "lucro-prejuizo",
    `${sinal}R$ ${fmt(Math.abs(r.lucroPrejuizoTotal))} (${sinal}${fmt(Math.abs(r.lucroPercentualTotal))}%)`
  );
  setClasse("lucro-prejuizo", classe);
  setTexto("resumo-carteira", `Em ativos: R$ ${fmt(r.valorEmAtivos)}`);
}

function desenharTabela() {
  const html = estado.ativos
    .map((a) => {
      const v = calcularVariacao(a);
      const tipo = v > 0.005 ? "alta" : v < -0.005 ? "baixa" : "neutro";
      const sinal = tipo === "alta" ? "+" : tipo === "baixa" ? "−" : "";
      const sel = a.codigo === estado.ativoSelecionado ? " linha-selecionada" : "";
      const bid = a.melhorCompra != null ? `R$ ${fmt(a.melhorCompra)}` : "—";
      const ask = a.melhorVenda != null ? `R$ ${fmt(a.melhorVenda)}` : "—";

      return `
        <div class="linha linha-mercado linha-clicavel${sel}" data-codigo="${esc(a.codigo)}">
          <span class="codigo">${esc(a.codigo)}<small class="nome-empresa">${a.nome ? ` · ${esc(a.nome)}` : ""}</small></span>
          <span class="mono dir">R$ ${fmt(a.preco)}</span>
          <span class="mono dir ${tipo}">${sinal}${fmt(Math.abs(v))}%</span>
          <span class="mono dir">${fmtInt(a.volume || 0)}</span>
          <span class="mono dir alta">${bid}</span>
          <span class="mono dir baixa">${ask}</span>
        </div>`;
    })
    .join("");
  setHtml("tabela", html);
}

function desenharLivro() {
  const codigo = estado.ativoSelecionado;
  setTexto("livro-ativo", codigo);

  const livro = estado.livros[codigo] || { bids: [], asks: [], spread: null };
  setTexto("livro-spread", livro.spread != null ? `Spread: R$ ${fmt(livro.spread)}` : "Spread: —");

  const bidsHtml = livro.bids.length > 0
    ? livro.bids.slice(0, 8).map((n, i) => `
        <div class="livro-nivel mono${i === 0 ? " livro-nivel-topo" : ""}" data-preco="${n.preco}">
          <span class="alta">R$ ${fmt(n.preco)}</span>
          <span>${fmtInt(n.quantidade)}</span>
        </div>`).join("")
    : `<div class="vazio">Sem ofertas de compra</div>`;

  const asksHtml = livro.asks.length > 0
    ? livro.asks.slice(0, 8).map((n, i) => `
        <div class="livro-nivel mono${i === 0 ? " livro-nivel-topo" : ""}" data-preco="${n.preco}">
          <span class="baixa">R$ ${fmt(n.preco)}</span>
          <span>${fmtInt(n.quantidade)}</span>
        </div>`).join("")
    : `<div class="vazio">Sem ofertas de venda</div>`;

  setHtml("livro-compras", bidsHtml);
  setHtml("livro-vendas", asksHtml);
}

function desenharCarteira() {
  const { posicoesAtivas } = calcularResumoCarteira();
  if (posicoesAtivas.length === 0) {
    setHtml("tabela-carteira", `<div class="vazio">Você ainda não possui ativos em carteira.</div>`);
    return;
  }

  const html = posicoesAtivas
    .map((p) => {
      const tipo = p.lucroPrejuizo > 0.005 ? "alta" : p.lucroPrejuizo < -0.005 ? "baixa" : "neutro";
      const sinal = tipo === "alta" ? "+" : tipo === "baixa" ? "−" : "";
      const detalheRes = p.reservada > 0 ? `<small class="sub-dado">${fmtInt(p.reservada)} reservadas</small>` : "";
      const pmTxt = p.precoMedio > 0 ? `R$ ${fmt(p.precoMedio)}` : "—";

      return `
        <div class="linha linha-carteira">
          <span class="codigo">${esc(p.codigo)}<small class="nome-empresa">${p.nome ? ` · ${esc(p.nome)}` : ""}</small></span>
          <span class="mono dir">${fmtInt(p.quantidade)}${detalheRes}</span>
          <span class="mono dir">${pmTxt}</span>
          <span class="mono dir">R$ ${fmt(p.precoAtual)}</span>
          <span class="mono dir">R$ ${fmt(p.valorAtual)}</span>
          <span class="mono dir ${tipo}">${sinal}R$ ${fmt(Math.abs(p.lucroPrejuizo))} (${sinal}${fmt(Math.abs(p.lucroPercentual))}%)</span>
        </div>`;
    })
    .join("");
  setHtml("tabela-carteira", html);
}

function desenharBoleta() {
  const sel = $("ativo");
  const codigo = (sel && sel.value) || estado.ativoSelecionado;
  const pos = calcularPosicao(codigo);
  const ativo = achar(codigo);
  const livre = Math.max(0, pos.quantidade - pos.reservada);

  const infoRes = pos.reservada > 0 ? ` (${fmtInt(livre)} livres · ${fmtInt(pos.reservada)} reservadas)` : "";
  setTexto("posicao", `Você possui: ${pos.quantidade} ${codigo}${infoRes}`);
  setTexto("preco-mercado", ativo ? `Preço de mercado agora: R$ ${fmt(ativo.preco)}` : "");

  const qtdEl = $("qtd");
  const precoEl = $("preco");
  const qtd = qtdEl ? parseInt(qtdEl.value, 10) : 0;
  const preco = precoEl ? parseFloat(String(precoEl.value || "").replace(",", ".")) : 0;
  if (qtd > 0 && preco > 0) {
    setTexto("total-ordem", `Total estimado: R$ ${fmt(qtd * preco)}`);
  } else {
    setTexto("total-ordem", "");
  }

  const offline = estado.conexao.status !== "conectado";
  const btnCompra = $("btn-comprar");
  const btnVenda = $("btn-vender");
  if (btnCompra) btnCompra.disabled = offline;
  if (btnVenda) btnVenda.disabled = offline;

  if (offline && estado.usuario) {
    setTexto("aviso-boleta", "Sem conexão ativa com o servidor. Aguarde a reconexão para enviar ordens.");
    setHidden("aviso-boleta", false);
  } else {
    setHidden("aviso-boleta", true);
  }
}

function classeBadgeStatus(status) {
  if (status === "FILLED" || status === "ACCEPTED" || status === "OPEN") return "badge-status badge-ok";
  if (status === "PARTIALLY_FILLED" || status === "RECEIVED") return "badge-status badge-aviso";
  if (status === "REJECTED" || status === "CANCELLED") return "badge-status badge-erro";
  return "badge-status";
}

function desenharOrdens() {
  const filtradas = estado.filtroOrdens === "abertas"
    ? estado.ordens.filter((o) => ehOrdemCancelavel(o.status))
    : estado.ordens;

  if (filtradas.length === 0) {
    setHtml("tabela-ordens", `<div class="vazio">Nenhuma ordem registrada para este filtro.</div>`);
    return;
  }

  const offline = estado.conexao.status !== "conectado";
  const html = filtradas
    .map((o) => {
      const idCurto = o.orderId ? o.orderId.slice(0, 8) : (o.clientOrderId ? o.clientOrderId.slice(0, 8) : "—");
      const ladoTxt = o.side === "BUY" ? "Compra" : o.side === "SELL" ? "Venda" : esc(o.side);
      const ladoCls = o.side === "BUY" ? "alta" : o.side === "SELL" ? "baixa" : "";
      const statusLabel = LABELS_STATUS[o.status] || o.status;
      const motivoTxt = o.reason ? `<small class="sub-dado erro">${esc(RAZOES_REJEICAO[o.reason] || o.reason)}</small>` : "";
      const podeCancelar = Boolean(o.orderId) && ehOrdemCancelavel(o.status);

      return `
        <div class="linha linha-ordens">
          <span class="mono" title="${esc(o.orderId || o.clientOrderId || "")}">${esc(idCurto)}</span>
          <span class="codigo">${esc(o.symbol)}</span>
          <span class="${ladoCls}">${ladoTxt}</span>
          <span class="mono dir">${fmtInt(o.filledQuantity)} / ${fmtInt(o.quantity)}</span>
          <span class="mono dir">${o.price != null ? `R$ ${fmt(o.price)}` : "—"}</span>
          <span><span class="${classeBadgeStatus(o.status)}">${esc(statusLabel)}</span>${motivoTxt}</span>
          <span class="acoes-ordem">
            ${o.orderId ? `<button type="button" class="btn-mini" data-acao="status" data-order-id="${esc(o.orderId)}" ${offline ? "disabled" : ""}>Status</button>` : ""}
            ${podeCancelar ? `<button type="button" class="btn-mini btn-mini-perigo" data-acao="cancelar" data-order-id="${esc(o.orderId)}" ${offline ? "disabled" : ""}>Cancelar</button>` : ""}
          </span>
        </div>`;
    })
    .join("");
  setHtml("tabela-ordens", html);
}

function desenharConsultaOrdem() {
  if (!estado.consultaOrdem) {
    setHidden("resultado-consulta-ordem", true);
    return;
  }
  const c = estado.consultaOrdem;
  if (c.naoEncontrada) {
    setHtml("resultado-consulta-ordem", `<span class="erro">Ordem <strong class="mono">${esc(c.orderId || "")}</strong> não encontrada.</span>`);
  } else {
    const statusLabel = LABELS_STATUS[c.status] || c.status;
    setHtml(
      "resultado-consulta-ordem",
      `Ordem <strong class="mono">${esc(c.orderId || "")}</strong> · ${esc(c.symbol)} · ${c.side === "BUY" ? "Compra" : "Venda"} · Executada ${fmtInt(c.filledQuantity)}/${fmtInt(c.quantity)} @ R$ ${fmt(c.price || 0)} · <span class="${classeBadgeStatus(c.status)}">${esc(statusLabel)}</span>`
    );
  }
  setHidden("resultado-consulta-ordem", false);
}

function desenharNegocios() {
  if (estado.negocios.length === 0) {
    setHtml("tabela-negocios", `<div class="vazio">Nenhuma negociação executada até o momento.</div>`);
    return;
  }

  const html = estado.negocios
    .map((n) => {
      const ladoTxt = n.side === "BUY" ? "Compra" : n.side === "SELL" ? "Venda" : (n.side ? esc(n.side) : "Negócio");
      const ladoCls = n.side === "BUY" ? "alta" : n.side === "SELL" ? "baixa" : "neutro";
      return `
        <div class="linha linha-negocios">
          <span class="mono">${esc(formatarHora(n.executedAt))}</span>
          <span class="codigo">${esc(n.symbol)}</span>
          <span class="${ladoCls}">${ladoTxt}</span>
          <span class="mono dir">${fmtInt(n.quantity)}</span>
          <span class="mono dir">R$ ${fmt(n.price)}</span>
          <span class="mono dir">R$ ${fmt(n.total)}</span>
        </div>`;
    })
    .join("");
  setHtml("tabela-negocios", html);
}

function desenharFeed() {
  const html = estado.logs
    .map((l) => `<div class="feed-item"><time>${esc(l.hora)}</time><span class="${esc(l.tipo)}">${esc(l.msg)}</span></div>`)
    .join("");
  setHtml("feed", html);
}

function desenharStatusConexao() {
  const st = estado.conexao.status;
  const mapa = {
    conectado: { txt: "Conectado", cls: "status-pill status-conectado" },
    reconectando: { txt: "Reconectando...", cls: "status-pill status-reconectando" },
    desconectado: { txt: "Desconectado", cls: "status-pill status-desconectado" },
    demo: { txt: "Modo demonstração", cls: "status-pill status-demo" },
  };
  const info = mapa[st] || mapa.desconectado;
  setTexto("status-conexao", info.txt);
  setClasse("status-conexao", info.cls);

  const mostrarBanner = st === "desconectado" || st === "reconectando";
  setHidden("banner-conexao", !mostrarBanner);
  if (mostrarBanner) {
    const detalhe = st === "reconectando"
      ? `Tentativa ${estado.conexao.tentativas}. Eventos perdidos durante a queda não são reenviados; o estado completo será recarregado assim que reconectar.`
      : "Sem conexão ativa com o servidor. Reconecte para atualizar saldo, carteira, livro e ordens.";
    setTexto("banner-conexao-msg", detalhe);
  }

  const btnSync = $("btn-sincronizar");
  if (btnSync) btnSync.disabled = st !== "conectado";

  desenharBoleta();
  desenharOrdens();
}

function mostrarAlerta(msg, tipo = "aviso") {
  estado.alerta = { msg, tipo };
  const cls = tipo === "erro" ? "banner banner-erro" : tipo === "ok" ? "banner banner-ok" : "banner banner-aviso";
  setClasse("banner-alerta", cls);
  setTexto("banner-alerta-msg", msg);
  setHidden("banner-alerta", false);
}

function limparAlerta() {
  estado.alerta = null;
  setHidden("banner-alerta", true);
}

function atualizarSelectAtivos() {
  const sel = $("ativo");
  if (!sel) return;
  const atual = sel.value || estado.ativoSelecionado;
  sel.innerHTML = estado.ativos
    .map((a) => `<option value="${esc(a.codigo)}">${esc(a.codigo)}${a.nome ? ` — ${esc(a.nome)}` : ""}</option>`)
    .join("");
  if (atual && achar(atual)) {
    sel.value = atual;
    estado.ativoSelecionado = atual;
  }
}

function selecionarAtivo(codigo, preencherPreco = true) {
  if (!codigo || !achar(codigo)) return;
  estado.ativoSelecionado = codigo;
  const sel = $("ativo");
  if (sel) sel.value = codigo;
  const ativo = achar(codigo);
  if (preencherPreco && ativo && $("preco")) {
    $("preco").value = fmt(ativo.preco);
  }
  desenharTabela();
  desenharBoleta();
  desenharLivro();

  if (stomp && stomp.connected) {
    stomp.publish({ destination: "/app/orderbook/snapshot", body: JSON.stringify({ symbol: codigo }) });
  }
}

function selecionarAba(aba) {
  estado.abaAtiva = aba;
  setHidden("painel-ordens", aba !== "ordens");
  setHidden("painel-negocios", aba !== "negocios");
  setHidden("painel-feed", aba !== "feed");
  setClasse("tab-ordens", aba === "ordens" ? "aba ativa" : "aba");
  setClasse("tab-negocios", aba === "negocios" ? "aba ativa" : "aba");
  setClasse("tab-feed", aba === "feed" ? "aba ativa" : "aba");
}

function log(msg, tipo = "info") {
  estado.logs.unshift({ hora: agora(), msg, tipo });
  estado.logs.length = Math.min(estado.logs.length, MAX_LOGS);
  desenharFeed();
}

// --- autenticacao REST ---

const CONTA_TESTE = { username: "teste", password: "teste123" };

function erroOffline() {
  const e = new Error("Servidor indisponível");
  e.offline = true;
  return e;
}

async function authRest(endpoint, username, password) {
  let res;
  try {
    res = await fetch(`${AUTH_BASE}${endpoint}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });
  } catch {
    throw erroOffline();
  }
  if (res.status === 404) throw erroOffline();
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(data.message || data.error || `Erro ${res.status}`);
  }
  return data;
}

// modo demonstracao (apenas quando o backend esta desligado no login)
function contasLocais() {
  return JSON.parse(storageGet("contasDemo") || "{}");
}

function authLocal(endpoint, username, password) {
  const contas = contasLocais();
  if (endpoint === "/auth/register") {
    if (!username || !password) throw new Error("Username e senha são obrigatórios");
    if (contas[username] || username === CONTA_TESTE.username) {
      throw new Error("Username já está em uso");
    }
    contas[username] = password;
    storageSet("contasDemo", JSON.stringify(contas));
  } else {
    const ok =
      (username === CONTA_TESTE.username && password === CONTA_TESTE.password) ||
      (contas[username] !== undefined && contas[username] === password);
    if (!ok) throw new Error("Usuário ou senha inválidos");
  }
  return {
    token: null,
    username,
    balance: 10000,
    portfolio: { PETR4: 0, VALE3: 0, ITUB4: 0, MGLU3: 0 },
  };
}

async function autenticar(endpoint, username, password) {
  try {
    return await authRest(endpoint, username, password);
  } catch (e) {
    if (!e.offline) throw e;
    return authLocal(endpoint, username, password);
  }
}

function setErroAuth(msg) {
  let el = $("erro-auth");
  if (!el && typeof document.createElement === "function") {
    el = document.createElement("p");
    el.id = "erro-auth";
    el.className = "nota erro";
    $("form-login").insertBefore(el, $("btn-submit-auth"));
  }
  if (el) {
    el.textContent = msg || "";
    el.hidden = !msg;
  }
}

function toggleModoAuth() {
  estado.modoAuth = estado.modoAuth === "login" ? "register" : "login";
  const isRegister = estado.modoAuth === "register";
  setTexto("btn-submit-auth", isRegister ? "Criar conta" : "Entrar no mercado");
  setTexto("btn-toggle-auth", isRegister ? "Já tenho conta" : "Criar conta");
  setTexto("label-toggle-auth", isRegister ? "Já tem conta?" : "Não tem conta?");
  setErroAuth("");
}

// --- conexao websocket/stomp, tolerancia a falhas e recarga de estado ---

let stomp = null;
let timerPrecos = null;
let tokenAtual = null;
let saindoVoluntariamente = false;

// solicita snapshot completo ao backend no login e apos cada reconexao
function recarregarEstadoCompleto(ehReconexao = false, exigirConectado = false) {
  if (!stomp) return false;
  if (exigirConectado && !stomp.connected) {
    mostrarAlerta("Sem conexão com o servidor para sincronizar o estado.", "erro");
    return false;
  }

  stomp.publish({ destination: "/app/assets/list", body: "{}" });
  stomp.publish({ destination: "/app/wallet/me", body: "{}" });
  stomp.publish({ destination: "/app/orders/mine", body: "{}" });
  stomp.publish({ destination: "/app/trades/mine", body: "{}" });
  stomp.publish({
    destination: "/app/orderbook/snapshot",
    body: JSON.stringify({ symbol: estado.ativoSelecionado }),
  });

  estado.conexao.ultimaSincronizacao = agora();
  if (ehReconexao) {
    log("Reconectado ao servidor. Estado completo recarregado.", "ok");
    mostrarAlerta("Conexão restabelecida. Carteira, mercado, livro e ordens sincronizados.", "ok");
  }
  return true;
}

function tratarEventoMercado(msg) {
  try {
    const evento = JSON.parse(msg.body);
    const tipo = evento.type || "";
    const d = evento.data || evento;

    if (tipo === "ORDER_BOOK_UPDATED" || tipo === "ORDER_BOOK_SNAPSHOT") {
      aplicarLivro(d.symbol || d.ticker || d.codigo, d);
      return;
    }

    if (tipo === "ECONOMIC_EVENT") {
      const titulo = d.title || d.titulo || "Evento econômico";
      const texto = d.message || d.descricao || d.description || "";
      const msgEvento = texto ? `${titulo}: ${texto}` : titulo;
      log(msgEvento, "aviso");
      mostrarAlerta(msgEvento, "aviso");
      return;
    }

    if (tipo === "TRADE_EXECUTED") {
      const cod = d.symbol || d.ticker || d.codigo;
      const preco = d.price ?? d.preco ?? d.preco_executado;
      const ativo = achar(cod);
      const vol = d.volume != null
        ? Number(d.volume)
        : (ativo ? (ativo.volume || 0) + Number(d.quantity ?? d.quantidade ?? 0) : Number(d.quantity ?? 0));
      if (cod && preco != null) {
        aplicarPreco(cod, Number(preco), d.name || "", { volume: vol });
      }
      if (d.buyerUsername === estado.usuario || d.sellerUsername === estado.usuario || d.ownTrade) {
        registrarNegocio(d);
      }
      return;
    }

    const cod = d.symbol || d.ticker || d.codigo || d.assetCode;
    const preco = d.currentPrice ?? d.preco_atual ?? d.price ?? d.preco;
    const nome = d.name || d.nome || d.nome_empresa || "";
    if (cod && preco != null) {
      aplicarPreco(cod, Number(preco), nome, {
        volume: d.volume ?? d.tradedVolume,
        melhorCompra: d.bestBid !== undefined ? d.bestBid : undefined,
        melhorVenda: d.bestAsk !== undefined ? d.bestAsk : undefined,
      });
    }
  } catch (e) {
    console.error("Erro ao ler evento do mercado", e);
  }
}

function tratarRespostaPrivada(msg) {
  try {
    const resp = JSON.parse(msg.body);
    const d = resp.data !== undefined ? resp.data : resp;
    const tipo = resp.type || "";

    // 1. catalogo oficial de ativos
    const listaAtivos = Array.isArray(d) ? d : (Array.isArray(resp.ativos) ? resp.ativos : null);
    if (listaAtivos && (tipo === "ASSET_LIST" || !tipo)) {
      listaAtivos.forEach((a) => {
        const cod = a.symbol || a.ticker || a.codigo;
        const p = a.price ?? a.currentPrice ?? a.preco_atual ?? a.preco;
        const nome = a.name || a.nome || a.nome_empresa || "";
        if (cod && p != null) {
          aplicarPreco(cod, Number(p), nome, {
            volume: a.volume ?? a.tradedVolume,
            melhorCompra: a.bestBid !== undefined ? a.bestBid : undefined,
            melhorVenda: a.bestAsk !== undefined ? a.bestAsk : undefined,
            silencioso: true,
          });
        }
      });
      log("Catálogo oficial de ativos sincronizado.", "info");
      return;
    }

    // 2. snapshot ou atualizacao de carteira
    if (tipo === "WALLET_SNAPSHOT" || tipo === "WALLET_UPDATED" || tipo === "PORTFOLIO_UPDATED") {
      aplicarSnapshotCarteira(d, tipo === "WALLET_SNAPSHOT");
      if (tipo === "WALLET_SNAPSHOT") {
        log("Carteira sincronizada com o servidor.", "info");
      }
      return;
    }

    // 3. snapshot do livro de ofertas
    if (tipo === "ORDER_BOOK_SNAPSHOT" || tipo === "ORDER_BOOK_UPDATED") {
      if (Array.isArray(d)) {
        d.forEach((item) => aplicarLivro(item.symbol || item.ticker, item));
      } else if (d) {
        aplicarLivro(d.symbol || d.ticker || estado.ativoSelecionado, d);
      }
      return;
    }

    // 4. historico de ordens do usuario (substitui lista inteira para manter consistencia pos-reconexao)
    if (tipo === "ORDER_HISTORY" && Array.isArray(d)) {
      estado.ordens = d.map(normalizarOrdem).reverse();
      desenharOrdens();
      log(`Histórico sincronizado: ${d.length} ordem(ns) registrada(s).`, "info");
      return;
    }

    // 5. historico de negociacoes do usuario
    if (tipo === "TRADE_HISTORY" && Array.isArray(d)) {
      estado.negocios = d.map(normalizarNegocio).slice(0, MAX_NEGOCIOS);
      desenharNegocios();
      log(`Negociações sincronizadas: ${d.length} registro(s).`, "info");
      return;
    }

    // 6. consulta de status pontual
    if (tipo === "ORDER_STATUS" && d) {
      const ordem = registrarOrdem(d);
      estado.consultaOrdem = ordem;
      desenharConsultaOrdem();
      log(`Status da ordem ${ordem.orderId ? ordem.orderId.slice(0, 8) : ""}: ${ordem.status}`, ordem.status === "ACCEPTED" || ordem.status === "FILLED" ? "ok" : "info");
      return;
    }

    if (tipo === "ORDER_NOT_FOUND") {
      estado.consultaOrdem = { naoEncontrada: true, orderId: resp.requestId || "" };
      desenharConsultaOrdem();
      log("Ordem consultada não foi encontrada.", "erro");
      mostrarAlerta("Ordem não encontrada para o seu usuário.", "aviso");
      return;
    }

    if (tipo === "ORDER_CANCELLED" || tipo === "ORDER_CANCEL_REJECTED") {
      tratarEventoOrdem(msg);
      return;
    }

    // fallback compativel com respostas planas da parte 1
    if (d && typeof d === "object") {
      aplicarSnapshotCarteira(
        {
          saldo: d.saldo ?? d.balance ?? resp.saldo ?? resp.balance,
          saldoReservado: d.saldoReservado ?? d.reservedBalance ?? resp.saldoReservado ?? resp.reservedBalance,
          carteira: d.carteira || d.portfolio || resp.carteira || resp.portfolio,
          positions: d.positions || d.posicoes,
        },
        false
      );
    }
  } catch (e) {
    console.error("Erro na resposta do servidor", e);
  }
}

function tratarEventoOrdem(msg) {
  try {
    const resp = JSON.parse(msg.body);
    const d = resp.data || resp;
    const tipoEvento = resp.type || "";

    if (tipoEvento === "WALLET_UPDATED" || tipoEvento === "PORTFOLIO_UPDATED") {
      aplicarSnapshotCarteira(d, false);
      return;
    }

    if (tipoEvento === "TRADE_EXECUTED") {
      registrarNegocio(d);
      log(`Negócio executado: ${d.quantity} ${d.symbol} @ R$ ${fmt(d.price || 0)}.`, "ok");
      if (d.saldo != null || d.balance != null || d.carteira || d.portfolio || d.positions) {
        aplicarSnapshotCarteira(d, false);
      }
      return;
    }

    const status = String(
      d.status || (tipoEvento === "ORDER_CANCELLED" ? "CANCELLED" : (d.sucesso ? "ACCEPTED" : "REJECTED"))
    ).toUpperCase();

    const ordem = registrarOrdem({ ...d, status });

    const ladoDesc = d.side === "BUY" ? "Compra" : d.side === "SELL" ? "Venda" : (d.side || "Operação");
    const precoFmt = d.price != null ? ` @ R$ ${fmt(d.price)}` : "";
    const qtdFmt = d.quantity != null ? ` de ${d.quantity} ${d.symbol || ""}` : "";

    if (status === "RECEIVED") {
      log(`Ordem de ${ladoDesc}${qtdFmt}${precoFmt} recebida pelo backend. Aguardando processamento.`, "info");
      return;
    }

    let mensagem;
    let tipoVisual = "ok";

    if (status === "ACCEPTED" || status === "OPEN" || status === "OK") {
      if (tipoEvento === "ORDER_ACK") {
        mensagem = `Ordem de ${ladoDesc}${qtdFmt}${precoFmt} aceita pelo backend!`;
      } else if (tipoEvento === "ORDER_UPDATED") {
        mensagem = `Ordem de ${ladoDesc}${qtdFmt}${precoFmt} aceita pelo processador. Aguardando execução.`;
      } else {
        mensagem = `Ordem de ${ladoDesc}${qtdFmt}${precoFmt} aceita com sucesso!`;
      }
    } else if (status === "PARTIALLY_FILLED") {
      mensagem = `Ordem de ${ladoDesc}${qtdFmt}${precoFmt} parcialmente executada (${ordem.filledQuantity}/${ordem.quantity}).`;
    } else if (status === "FILLED") {
      mensagem = `Ordem de ${ladoDesc}${qtdFmt}${precoFmt} totalmente executada!`;
    } else if (status === "CANCELLED") {
      if (d.reason) {
        const motivo = RAZOES_REJEICAO[d.reason] || d.reason;
        const parcial = ordem.filledQuantity > 0
          ? ` (executada ${ordem.filledQuantity}/${ordem.quantity})`
          : "";
        tipoVisual = "aviso";
        mensagem = `Ordem de ${ladoDesc}${qtdFmt}${precoFmt} cancelada: ${motivo}${parcial}`;
        mostrarAlerta(mensagem, "aviso");
      } else {
        tipoVisual = "info";
        mensagem = `Ordem de ${ladoDesc}${qtdFmt}${precoFmt} cancelada.`;
      }
    } else {
      tipoVisual = "erro";
      mensagem = RAZOES_REJEICAO[d.reason]
        || d.mensagem
        || d.message
        || `Ordem rejeitada: ${d.reason || status}`;
      mostrarAlerta(mensagem, "erro");
    }

    log(mensagem, tipoVisual);

    // aplica saldo/carteira somente quando o servidor envia explicitamente
    if (d.saldo != null || d.balance != null || d.saldoReservado != null || d.reservedBalance != null || d.carteira || d.portfolio || d.positions) {
      aplicarSnapshotCarteira(d, false);
    }
  } catch (e) {
    console.error("Erro ao processar retorno da ordem", e);
  }
}

function conectarStomp(token) {
  tokenAtual = token;
  saindoVoluntariamente = false;
  estado.conexao.jaConectou = false;
  estado.conexao.tentativas = 0;

  stomp = new StompJs.Client({
    brokerURL: WS_URL,
    connectHeaders: {
      Authorization: `Bearer ${token}`,
    },
    reconnectDelay: RECONNECT_DELAY_MS,

    onConnect: () => {
      const ehReconexao = estado.conexao.jaConectou;
      estado.conexao.jaConectou = true;
      estado.conexao.tentativas = 0;
      estado.conexao.status = "conectado";
      desenharStatusConexao();

      if (!ehReconexao) {
        log("Conectado ao servidor do mercado.");
      }

      if (timerPrecos && typeof clearInterval !== "undefined") {
        clearInterval(timerPrecos);
        timerPrecos = null;
      }

      stomp.subscribe("/topic/market", tratarEventoMercado);
      stomp.subscribe("/topic/orderbook", (msg) => {
        try {
          const ev = JSON.parse(msg.body);
          const d = ev.data || ev;
          aplicarLivro(d.symbol || d.ticker || d.codigo, d);
        } catch (e) {
          console.error("Erro ao ler livro de ofertas", e);
        }
      });
      stomp.subscribe("/topic/trades", (msg) => {
        try {
          const ev = JSON.parse(msg.body);
          tratarEventoMercado({ body: JSON.stringify({ type: "TRADE_EXECUTED", data: ev.data || ev }) });
        } catch (e) {
          console.error("Erro ao ler negocio do mercado", e);
        }
      });
      stomp.subscribe("/user/queue/replies", tratarRespostaPrivada);
      stomp.subscribe("/user/queue/orders", tratarEventoOrdem);

      // carrega ou recarrega snapshot completo pois eventos perdidos nao sao reenviados
      recarregarEstadoCompleto(ehReconexao, false);
    },

    onWebSocketClose: () => {
      if (saindoVoluntariamente) return;
      const estavaConectado = estado.conexao.status === "conectado";
      estado.conexao.tentativas += 1;
      estado.conexao.status = "reconectando";
      desenharStatusConexao();

      if (estavaConectado || estado.conexao.tentativas === 1) {
        log("Conexão perdida com o servidor. Tentando reconectar...", "erro");
      }
    },

    onDisconnect: () => {
      if (saindoVoluntariamente) return;
      estado.conexao.status = "desconectado";
      desenharStatusConexao();
      log("Desconectado do servidor.", "erro");
    },

    onStompError: (frame) => {
      const msg = frame.headers?.message || "";
      if (
        msg.toLowerCase().includes("token") ||
        msg.toLowerCase().includes("unauthorized") ||
        msg.includes("401")
      ) {
        log("Sessão expirada. Faça login novamente.", "erro");
        storageRemove("jwtToken");
        sair();
      } else {
        const erroMsg = "Erro STOMP: " + (msg || "falha na conexão");
        log(erroMsg, "erro");
        mostrarAlerta(erroMsg, "erro");
      }
    },
  });

  stomp.activate();
}

function forcarReconexao() {
  if (!tokenAtual) return;
  if (stomp) {
    saindoVoluntariamente = true;
    stomp.deactivate();
  }
  conectarStomp(tokenAtual);
}

// --- operacoes do investidor ---

function enviarOrdem(lado) {
  const codigo = ($("ativo") && $("ativo").value) || estado.ativoSelecionado;
  const qtd = parseInt($("qtd").value, 10);
  const preco = parseFloat(String($("preco").value).replace(",", "."));

  if (!(qtd > 0) || !(preco > 0)) {
    const msg = "Informe uma quantidade e um preço válidos.";
    mostrarAlerta(msg, "erro");
    return log(msg, "erro");
  }

  if (stomp && stomp.connected) {
    stomp.publish({
      destination: "/app/orders/create",
      body: JSON.stringify({
        clientOrderId: gerarUuid(),
        symbol: codigo,
        side: lado === "compra" ? "BUY" : "SELL",
        quantity: qtd,
        price: preco.toFixed(2),
      }),
    });
    log(`Enviando ordem de ${lado} de ${qtd} ${codigo} ao servidor...`);
  } else {
    const msg = "Sem conexão com o servidor. A ordem não foi enviada.";
    mostrarAlerta(msg, "erro");
    log(msg, "erro");
  }
}

function cancelarOrdem(orderId) {
  if (!orderId) return;
  if (stomp && stomp.connected) {
    stomp.publish({
      destination: "/app/orders/cancel",
      body: JSON.stringify({ orderId }),
    });
    log(`Solicitando cancelamento da ordem ${orderId.slice(0, 8)}...`, "info");
  } else {
    const msg = "Sem conexão com o servidor. Não foi possível cancelar a ordem.";
    mostrarAlerta(msg, "erro");
    log(msg, "erro");
  }
}

function consultarStatusOrdem(orderId) {
  const id = String(orderId || "").trim();
  if (!id) {
    mostrarAlerta("Informe o ID da ordem para consultar.", "aviso");
    return;
  }
  if (stomp && stomp.connected) {
    stomp.publish({
      destination: "/app/orders/status",
      body: JSON.stringify({ orderId: id }),
    });
    log(`Consultando status da ordem ${id.slice(0, 8)}...`, "info");
  } else {
    const msg = "Sem conexão com o servidor. Não foi possível consultar a ordem.";
    mostrarAlerta(msg, "erro");
    log(msg, "erro");
  }
}

// --- navegacao entre telas ---

async function entrar(nome, senha) {
  if (!nome) return setErroAuth("Informe um nome de usuário.");

  let token = null;

  try {
    const data = await autenticar("/auth/login", nome, senha);
    token = data.token;
    if (token) storageSet("jwtToken", token);
    estado.usuario = data.username || nome;

    aplicarSnapshotCarteira(
      {
        balance: data.balance != null ? Number(data.balance) : 10000,
        reservedBalance: data.reservedBalance != null ? Number(data.reservedBalance) : 0,
        portfolio: data.portfolio || { PETR4: 0, VALE3: 0, ITUB4: 0, MGLU3: 0 },
      },
      true
    );
  } catch (e) {
    setErroAuth(e.message || "Erro ao conectar ao servidor.");
    return;
  }

  setErroAuth("");
  limparAlerta();
  estado.logs = [];
  estado.ordens = [];
  estado.negocios = [];
  estado.livros = {};
  estado.consultaOrdem = null;

  setHidden("tela-login", true);
  setHidden("tela-mercado", false);
  setTexto("ola", `Olá, ${estado.usuario}!`);

  desenharResumoFinanceiro();
  desenharTabela();
  desenharLivro();
  desenharCarteira();
  desenharBoleta();
  desenharOrdens();
  desenharNegocios();
  desenharConsultaOrdem();
  selecionarAba("ordens");

  if (token) {
    conectarStomp(token);
  } else {
    estado.conexao.status = "demo";
    desenharStatusConexao();
    log("Servidor indisponível — modo demonstração local.", "erro");

    // simulacao local roda apenas sem token (modo demonstracao); quando autenticado, queda congela o estado real
    if (typeof setInterval !== "undefined") {
      timerPrecos = setInterval(() => {
        const a = estado.ativos[Math.floor(Math.random() * estado.ativos.length)];
        const novo = Math.round(a.preco * (1 + (Math.random() - 0.5) * 0.03) * 100) / 100;
        aplicarPreco(a.codigo, Math.max(0.01, novo));
      }, 3000);
    }
  }
}

async function cadastrar(nome, senha) {
  if (!nome || !senha) return setErroAuth("Informe usuário e senha para criar conta.");

  try {
    await autenticar("/auth/register", nome, senha);
    await entrar(nome, senha);
  } catch (e) {
    setErroAuth(e.message || "Erro ao criar conta.");
  }
}

function sair() {
  saindoVoluntariamente = true;
  tokenAtual = null;
  if (timerPrecos && typeof clearInterval !== "undefined") {
    clearInterval(timerPrecos);
    timerPrecos = null;
  }
  if (stomp) {
    stomp.deactivate();
    stomp = null;
  }
  storageRemove("jwtToken");
  estado.usuario = null;
  estado.conexao.status = "desconectado";
  estado.conexao.jaConectou = false;
  setHidden("tela-mercado", true);
  setHidden("tela-login", false);
}

// --- ligacao de eventos da interface ---

function ligarEvento(id, evento, fn) {
  const el = $(id);
  if (el && typeof el.addEventListener === "function") {
    el.addEventListener(evento, fn);
  }
}

atualizarSelectAtivos();

ligarEvento("ativo", "change", () => {
  const sel = $("ativo");
  if (sel) selecionarAtivo(sel.value, true);
});

ligarEvento("qtd", "input", desenharBoleta);
ligarEvento("preco", "input", desenharBoleta);

ligarEvento("tabela", "click", (e) => {
  const linha = e.target && typeof e.target.closest === "function" ? e.target.closest("[data-codigo]") : null;
  if (linha) selecionarAtivo(linha.getAttribute("data-codigo"), true);
});

function clicarNivelLivro(e) {
  const nivel = e.target && typeof e.target.closest === "function" ? e.target.closest("[data-preco]") : null;
  if (!nivel) return;
  const preco = Number(nivel.getAttribute("data-preco"));
  if (Number.isFinite(preco) && $("preco")) {
    $("preco").value = fmt(preco);
    desenharBoleta();
  }
}

ligarEvento("livro-compras", "click", clicarNivelLivro);
ligarEvento("livro-vendas", "click", clicarNivelLivro);

ligarEvento("tabela-ordens", "click", (e) => {
  const btn = e.target && typeof e.target.closest === "function" ? e.target.closest("[data-acao]") : null;
  if (!btn) return;
  const acao = btn.getAttribute("data-acao");
  const orderId = btn.getAttribute("data-order-id");
  if (acao === "cancelar") cancelarOrdem(orderId);
  if (acao === "status") consultarStatusOrdem(orderId);
});

ligarEvento("filtro-ordens", "change", () => {
  const filtro = $("filtro-ordens");
  if (filtro) {
    estado.filtroOrdens = filtro.value;
    desenharOrdens();
  }
});

ligarEvento("btn-consultar-ordem", "click", () => {
  const input = $("input-consulta-ordem");
  if (input) consultarStatusOrdem(input.value);
});

ligarEvento("tab-ordens", "click", () => selecionarAba("ordens"));
ligarEvento("tab-negocios", "click", () => selecionarAba("negocios"));
ligarEvento("tab-feed", "click", () => selecionarAba("feed"));

ligarEvento("btn-sincronizar", "click", () => {
  if (recarregarEstadoCompleto(false, true)) {
    log("Sincronização manual solicitada ao servidor.", "info");
  }
});
ligarEvento("btn-reconectar", "click", forcarReconexao);
ligarEvento("btn-fechar-alerta", "click", limparAlerta);

ligarEvento("form-login", "submit", (e) => {
  e.preventDefault();
  const nome = $("usuario").value.trim();
  const senha = $("senha").value;
  if (estado.modoAuth === "register") {
    cadastrar(nome, senha);
  } else {
    entrar(nome, senha);
  }
});

ligarEvento("btn-toggle-auth", "click", toggleModoAuth);
ligarEvento("btn-sair", "click", sair);
ligarEvento("btn-comprar", "click", () => enviarOrdem("compra"));
ligarEvento("btn-vender", "click", () => enviarOrdem("venda"));
```

### `frontend/app.test.cjs`

```javascript
const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

// harness original da parte 1 (preservado para garantir retrocompatibilidade)
function conectarFrontend() {
  const elementos = new Map();
  const inscricoes = new Map();
  const contexto = vm.createContext({
    console,
    location: { protocol: "http:", port: "8081", host: "localhost:8081", hostname: "localhost" },
    document: {
      getElementById(id) {
        if (!elementos.has(id)) {
          elementos.set(id, { value: "PETR4", innerHTML: "", textContent: "", addEventListener() {} });
        }
        return elementos.get(id);
      },
    },
    StompJs: {
      Client: class {
        constructor(config) { this.config = config; }
        activate() { this.config.onConnect(); }
        subscribe(destino, handler) { inscricoes.set(destino, handler); }
        publish() {}
      },
    },
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, "app.js"), "utf8"), contexto);
  vm.runInContext('conectarStomp("jwt-de-teste")', contexto);
  return {
    receber(type, data) {
      inscricoes.get("/user/queue/orders")({ body: JSON.stringify({ type, data }) });
    },
    estado() {
      return JSON.parse(vm.runInContext("JSON.stringify(estado)", contexto));
    },
    enviarSemConexao(lado) {
      vm.runInContext('document.getElementById("qtd").value = "1"; document.getElementById("preco").value = "35,00";', contexto);
      vm.runInContext(`enviarOrdem(${JSON.stringify(lado)})`, contexto);
    },
  };
}

// harness completo da parte 2 (simula conexao ativa, quedas, reconexoes e publicacoes)
function criarAmbienteCompleto() {
  const elementos = new Map();
  const inscricoes = new Map();
  const publicados = [];
  let clienteStomp = null;

  const contexto = vm.createContext({
    console,
    location: { protocol: "http:", port: "8081", host: "localhost:8081", hostname: "localhost" },
    document: {
      getElementById(id) {
        if (!elementos.has(id)) {
          elementos.set(id, {
            value: id === "ativo" ? "PETR4" : "",
            innerHTML: "",
            textContent: "",
            className: "",
            hidden: false,
            disabled: false,
            addEventListener() {},
          });
        }
        return elementos.get(id);
      },
    },
    StompJs: {
      Client: class {
        constructor(config) {
          this.config = config;
          this.connected = false;
          clienteStomp = this;
        }
        activate() {
          this.connected = true;
          this.config.onConnect();
        }
        deactivate() {
          this.connected = false;
        }
        subscribe(destino, handler) {
          inscricoes.set(destino, handler);
        }
        publish(frame) {
          publicados.push({
            destination: frame.destination,
            body: JSON.parse(frame.body || "{}"),
          });
        }
      },
    },
  });

  vm.runInContext(fs.readFileSync(path.join(__dirname, "app.js"), "utf8"), contexto);
  vm.runInContext('conectarStomp("jwt-parte-2")', contexto);

  return {
    contexto,
    elementos,
    publicados,
    receberEm(destino, evento) {
      const handler = inscricoes.get(destino);
      assert.ok(handler, `Nenhuma inscrição encontrada para ${destino}`);
      handler({ body: JSON.stringify(evento) });
    },
    simularQueda() {
      clienteStomp.connected = false;
      clienteStomp.config.onWebSocketClose();
    },
    simularReconexao() {
      clienteStomp.connected = true;
      clienteStomp.config.onConnect();
    },
    estado() {
      return JSON.parse(vm.runInContext("JSON.stringify(estado)", contexto));
    },
    resumoCarteira() {
      return JSON.parse(vm.runInContext("JSON.stringify(calcularResumoCarteira())", contexto));
    },
  };
}

// --- testes originais da parte 1 ---

test("RECEIVED informa recebimento sem simular uma negociação", () => {
  const frontend = conectarFrontend();
  frontend.receber("ORDER_ACK", { status: "RECEIVED", side: "BUY", symbol: "PETR4", quantity: 1, price: "35.00" });
  const estado = frontend.estado();
  assert.match(estado.logs[0].msg, /recebida.*Aguardando processamento/);
  assert.equal(estado.logs[0].tipo, "info");
  assert.equal(estado.saldo, 10000);
  assert.equal(estado.carteira.PETR4, 0);
});

test("ACCEPTED de compra ou venda, inclusive repetido, não altera saldo ou posições", () => {
  const frontend = conectarFrontend();
  for (const side of ["BUY", "SELL", "BUY"]) {
    frontend.receber("ORDER_UPDATED", { status: "ACCEPTED", side, symbol: "PETR4", quantity: 1, price: "35.00" });
    const estado = frontend.estado();
    assert.match(estado.logs[0].msg, /aceita.*Aguardando execução/);
    assert.equal(estado.logs[0].tipo, "ok");
    assert.equal(estado.saldo, 10000);
    assert.equal(estado.carteira.PETR4, 0);
  }
});

test("saldo e carteira explícitos do servidor são aplicados sem cálculos locais", () => {
  const frontend = conectarFrontend();
  const ordem = { status: "ACCEPTED", side: "BUY", symbol: "PETR4", quantity: 1, price: "35.00" };
  for (let i = 0; i < 2; i++) {
    frontend.receber("ORDER_UPDATED", { ...ordem, balance: 9900, portfolio: { PETR4: 0 } });
    assert.equal(frontend.estado().saldo, 9900);
    assert.equal(frontend.estado().carteira.PETR4, 0);
  }
  frontend.receber("ORDER_UPDATED", { ...ordem, saldo: 9800, carteira: { PETR4: 2 } });
  assert.equal(frontend.estado().saldo, 9800);
  assert.equal(frontend.estado().carteira.PETR4, 2);
});

test("sem STOMP conectado não há negociação local nem confirmação falsa", () => {
  const frontend = conectarFrontend();
  for (const lado of ["compra", "venda"]) {
    frontend.enviarSemConexao(lado);
    const estado = frontend.estado();
    assert.equal(estado.saldo, 10000);
    assert.equal(estado.carteira.PETR4, 0);
    assert.equal(estado.logs[0].tipo, "erro");
    assert.match(estado.logs[0].msg, /A ordem não foi enviada/);
  }
});

// --- testes novos da parte 2 ---

test("calcula patrimônio total, preço médio e lucro/prejuízo a partir do snapshot de carteira", () => {
  const env = criarAmbienteCompleto();

  env.receberEm("/user/queue/replies", {
    type: "WALLET_SNAPSHOT",
    data: {
      balance: 6000,
      reservedBalance: 500,
      positions: [
        { symbol: "PETR4", quantity: 100, reservedQuantity: 20, averagePrice: 30.0 },
        { symbol: "VALE3", quantity: 50, reservedQuantity: 0, averagePrice: 70.0 },
      ],
    },
  });

  // PETR4 esta a 35.00 (+500) e VALE3 esta a 68.00 (-100) => lucro total +400
  // valor em ativos = 3500 + 3400 = 6900; patrimonio = 6000 + 500 + 6900 = 13400
  const resumo = env.resumoCarteira();
  assert.equal(resumo.saldoDisponivel, 6000);
  assert.equal(resumo.saldoReservado, 500);
  assert.equal(resumo.valorEmAtivos, 6900);
  assert.equal(resumo.patrimonioTotal, 13400);
  assert.equal(resumo.lucroPrejuizoTotal, 400);
  assert.equal(env.estado().carteira.PETR4, 100);
  assert.equal(env.estado().posicoes.PETR4.precoMedio, 30);
});

test("atualiza livro de ofertas ordenado, melhores ofertas, spread e volume", () => {
  const env = criarAmbienteCompleto();

  env.receberEm("/topic/market", {
    type: "PRICE_UPDATE",
    data: { symbol: "PETR4", currentPrice: 36.2, volume: 1500 },
  });

  env.receberEm("/topic/orderbook", {
    type: "ORDER_BOOK_UPDATED",
    data: {
      symbol: "PETR4",
      bids: [
        { price: 35.8, quantity: 100 },
        { price: 36.1, quantity: 250 },
      ],
      asks: [
        { price: 36.5, quantity: 300 },
        { price: 36.3, quantity: 120 },
      ],
    },
  });

  const est = env.estado();
  const petr = est.ativos.find((a) => a.codigo === "PETR4");
  const livro = est.livros.PETR4;

  assert.equal(petr.preco, 36.2);
  assert.equal(petr.volume, 1500);
  assert.equal(petr.melhorCompra, 36.1);
  assert.equal(petr.melhorVenda, 36.3);
  assert.equal(livro.bids[0].preco, 36.1);
  assert.equal(livro.asks[0].preco, 36.3);
  assert.ok(Math.abs(livro.spread - 0.2) < 0.0001);
});

test("permite cancelar ordem, consultar status e exibe aviso em autonegociação parcial", () => {
  const env = criarAmbienteCompleto();
  env.publicados.length = 0;

  env.receberEm("/user/queue/orders", {
    type: "ORDER_UPDATED",
    data: {
      orderId: "ord-12345678",
      clientOrderId: "cli-1",
      symbol: "PETR4",
      side: "BUY",
      quantity: 100,
      filledQuantity: 40,
      price: "35.00",
      status: "PARTIALLY_FILLED",
    },
  });

  assert.equal(env.estado().ordens[0].filledQuantity, 40);
  assert.equal(env.estado().ordens[0].remainingQuantity, 60);

  vm.runInContext('cancelarOrdem("ord-12345678")', env.contexto);
  vm.runInContext('consultarStatusOrdem("ord-12345678")', env.contexto);

  assert.deepEqual(env.publicados[0], {
    destination: "/app/orders/cancel",
    body: { orderId: "ord-12345678" },
  });
  assert.deepEqual(env.publicados[1], {
    destination: "/app/orders/status",
    body: { orderId: "ord-12345678" },
  });

  env.receberEm("/user/queue/orders", {
    type: "ORDER_UPDATED",
    data: {
      orderId: "ord-999",
      symbol: "PETR4",
      side: "BUY",
      quantity: 100,
      filledQuantity: 40,
      price: "35.00",
      status: "CANCELLED",
      reason: "SELF_TRADE",
    },
  });

  assert.equal(env.estado().alerta.tipo, "aviso");
  assert.match(env.estado().alerta.msg, /Autonegociação/);
  assert.match(env.estado().alerta.msg, /40\/100/);
  assert.equal(env.elementos.get("banner-alerta").hidden, false);
});

test("detecta desconexão, bloqueia operações offline e recarrega snapshot completo ao reconectar", () => {
  const env = criarAmbienteCompleto();

  // simula queda do backend
  env.simularQueda();
  assert.equal(env.estado().conexao.status, "reconectando");
  assert.equal(env.elementos.get("banner-conexao").hidden, false);

  // tentativa de cancelamento offline nao deve publicar
  env.publicados.length = 0;
  vm.runInContext('cancelarOrdem("ord-offline")', env.contexto);
  assert.equal(env.publicados.length, 0);

  // reconexao deve disparar os 5 snapshots para repor eventos perdidos
  env.simularReconexao();
  assert.equal(env.estado().conexao.status, "conectado");
  assert.equal(env.elementos.get("banner-conexao").hidden, true);

  const destinos = env.publicados.map((p) => p.destination);
  assert.deepEqual(destinos, [
    "/app/assets/list",
    "/app/wallet/me",
    "/app/orders/mine",
    "/app/trades/mine",
    "/app/orderbook/snapshot",
  ]);

  // snapshot recebido substitui historico antigo de ordens e negocios
  env.receberEm("/user/queue/replies", {
    type: "ORDER_HISTORY",
    data: [
      { orderId: "ord-rec-1", symbol: "VALE3", side: "SELL", quantity: 10, price: "68.50", status: "ACCEPTED" },
    ],
  });
  env.receberEm("/user/queue/replies", {
    type: "TRADE_HISTORY",
    data: [
      { tradeId: "tr-1", symbol: "PETR4", side: "BUY", quantity: 50, price: 34.8, executedAt: "2026-11-25T18:00:00Z" },
    ],
  });

  assert.equal(env.estado().ordens.length, 1);
  assert.equal(env.estado().ordens[0].orderId, "ord-rec-1");
  assert.equal(env.estado().negocios.length, 1);
  assert.equal(env.estado().negocios[0].total, 1740);
});
```

## 5. Como rodar, testar e resumo da entrega

### Como validar isoladamente

1. **Testes automatizados do frontend (sem precisar do backend):**

   ```bash
   node --test frontend/app.test.cjs
   ```

   Os 8 testes (os 4 da Parte 1 + 4 novos da Parte 2) devem passar sem dependências externas.

2. **Rodando com o sistema em Docker:**

   ```bash
   docker compose up --build
   ```

   Acesse `http://localhost` (ou a porta mapeada no `docker-compose.yaml`).

3. **Teste rápido de tolerância a falhas (para validar com o roteiro do Sidnei):**

   * Faça login no Home Broker e verifique o status `Conectado`.

   * Execute `docker compose stop backend` → o topo muda para `Reconectando...`, o banner vermelho aparece e os botões de envio/cancelamento ficam bloqueados.

   * Execute `docker compose start backend` → o STOMP reconecta automaticamente, o banner some e o frontend envia as 5 requisições de recarga (`/app/assets/list`, `/app/wallet/me`, `/app/orders/mine`, `/app/trades/mine`, `/app/orderbook/snapshot`).

### Resumo curto

* **O que foi feito:** evolução completa do Home Broker com patrimônio/preço médio/P&L, volume/melhores ofertas/livro de ofertas, cancelamento e consulta de status de ordens, execução parcial, histórico de negociações, alertas visuais de rejeição/autonegociação/desconexão e reconexão com recarga de snapshot.

* **Arquivos alterados (somente na sua pasta):** `frontend/index.html`, `frontend/style.css`, `frontend/app.js`, `frontend/app.test.cjs`.

* **Pendências/dependências externas:** contrato do motor fechado com o Danilo (Seção 3); falta apenas confirmar os contratos de carteira e histórico de negócios com o Bruno.