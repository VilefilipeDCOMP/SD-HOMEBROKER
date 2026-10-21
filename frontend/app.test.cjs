const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

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
