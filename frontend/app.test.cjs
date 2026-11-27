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

  // recusa de cancelamento nao corrompe o estado da ordem existente e exibe alerta
  env.receberEm("/user/queue/orders", {
    type: "ORDER_CANCEL_REJECTED",
    data: {
      orderId: "ord-12345678",
      reason: "FORBIDDEN",
    },
  });
  assert.equal(env.estado().ordens[0].status, "PARTIALLY_FILLED");
  assert.equal(env.estado().ordens[0].symbol, "PETR4");
  assert.equal(env.estado().alerta.tipo, "erro");
  assert.match(env.estado().alerta.msg, /permissão/);

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

test("confirma sincronização na reconexão somente após aplicar todos os 5 snapshots", () => {
  const env = criarAmbienteCompleto();

  // na conexao inicial, os pedidos foram enviados, mas a sincronizacao ainda aguarda respostas
  assert.equal(env.estado().conexao.sincronizando, true);
  assert.equal(env.estado().conexao.ultimaSincronizacao, null);

  env.simularQueda();
  assert.equal(env.estado().conexao.sincronizando, false);

  env.simularReconexao();
  assert.equal(env.estado().conexao.sincronizando, true);
  assert.equal(env.estado().conexao.ultimaSincronizacao, null);
  assert.equal(env.estado().alerta, null);
  assert.ok(!env.estado().logs.some((l) => /Estado completo recarregado/.test(l.msg)));

  // aplica 4 dos 5 snapshots: ainda nao deve confirmar a sincronizacao completa
  env.receberEm("/user/queue/replies", {
    type: "ASSET_LIST",
    data: [{ symbol: "PETR4", name: "Petrobras PN", currentPrice: 35.4, volume: 120 }],
  });
  env.receberEm("/user/queue/replies", {
    type: "WALLET_SNAPSHOT",
    data: { balance: 9500, reservedBalance: 0, positions: [] },
  });
  env.receberEm("/user/queue/replies", {
    type: "ORDER_BOOK_SNAPSHOT",
    data: { symbol: "PETR4", bids: [{ price: 35.3, quantity: 10 }], asks: [] },
  });
  env.receberEm("/user/queue/replies", {
    type: "ORDER_HISTORY",
    data: [],
  });

  assert.equal(env.estado().conexao.sincronizando, true);
  assert.deepEqual(env.estado().conexao.pendentesSincronizacao, ["TRADE_HISTORY"]);
  assert.equal(env.estado().conexao.ultimaSincronizacao, null);
  assert.equal(env.estado().alerta, null);

  // aplica o 5o snapshot restante: agora deve confirmar a sincronizacao
  env.receberEm("/user/queue/replies", {
    type: "TRADE_HISTORY",
    data: [],
  });

  const estFinal = env.estado();
  assert.equal(estFinal.conexao.sincronizando, false);
  assert.deepEqual(estFinal.conexao.pendentesSincronizacao, []);
  assert.notEqual(estFinal.conexao.ultimaSincronizacao, null);
  assert.equal(estFinal.logs[0].tipo, "ok");
  assert.match(estFinal.logs[0].msg, /Estado completo recarregado/);
  assert.equal(estFinal.alerta.tipo, "ok");
  assert.match(estFinal.alerta.msg, /Ativos, carteira, livro, ordens e negociações sincronizados/);
});

test("terceiro cliente desconectado exibe volume negociado uma única vez ao reconectar", () => {
  const cliente1 = criarAmbienteCompleto();
  const cliente2 = criarAmbienteCompleto();
  const cliente3 = criarAmbienteCompleto();

  // cliente 3 desconecta antes da negociacao entre cliente 1 e cliente 2
  cliente3.simularQueda();

  const eventoNegocio = {
    type: "TRADE_EXECUTED",
    data: {
      tradeId: "tr-vol-1",
      symbol: "PETR4",
      quantity: 30,
      price: 35.75,
      volume: 30,
      buyerUsername: "cliente1",
      sellerUsername: "cliente2",
    },
  };

  // clientes conectados recebem o evento (inclusive se chegar em ambos os topicos) sem duplicar volume
  cliente1.receberEm("/topic/market", eventoNegocio);
  cliente1.receberEm("/topic/trades", eventoNegocio);
  cliente2.receberEm("/topic/market", eventoNegocio);

  assert.equal(cliente1.estado().ativos.find((a) => a.codigo === "PETR4").volume, 30);
  assert.equal(cliente2.estado().ativos.find((a) => a.codigo === "PETR4").volume, 30);
  assert.equal(cliente3.estado().ativos.find((a) => a.codigo === "PETR4").volume, 0);

  // cliente 3 reconecta e recebe o ASSET_LIST com o volume acumulado vindo do backend
  cliente3.simularReconexao();
  cliente3.receberEm("/user/queue/replies", {
    type: "ASSET_LIST",
    data: [
      { symbol: "ITUB4", name: "Itaú Unibanco PN", currentPrice: 30.5, volume: 0 },
      { symbol: "MGLU3", name: "Magazine Luiza ON", currentPrice: 2.1, volume: 0 },
      { symbol: "PETR4", name: "Petrobras PN", currentPrice: 35.75, volume: 30 },
      { symbol: "VALE3", name: "Vale ON", currentPrice: 68.0, volume: 0 },
    ],
  });
  cliente3.receberEm("/user/queue/replies", {
    type: "TRADE_HISTORY",
    data: [
      { tradeId: "tr-vol-1", symbol: "PETR4", side: "BUY", quantity: 30, price: 35.75 },
    ],
  });

  // mesmo que um frame atrasado sem campo volume chegue para o mesmo tradeId, nao soma duas vezes
  cliente3.receberEm("/topic/market", {
    type: "TRADE_EXECUTED",
    data: {
      tradeId: "tr-vol-1",
      symbol: "PETR4",
      quantity: 30,
      price: 35.75,
    },
  });

  const petrCliente3 = cliente3.estado().ativos.find((a) => a.codigo === "PETR4");
  assert.equal(petrCliente3.preco, 35.75);
  assert.equal(petrCliente3.volume, 30);
});
