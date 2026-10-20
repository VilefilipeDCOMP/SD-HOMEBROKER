
const MAX_LOGS = 8;

const estado = {
  saldo: 10000,
  ativos: [
    { codigo: "PETR4", preco: 35.0, base: 35.0 },
    { codigo: "VALE3", preco: 68.0, base: 68.0 },
    { codigo: "ITUB4", preco: 30.5, base: 30.5 },
    { codigo: "MGLU3", preco: 2.1, base: 2.1 },
  ],
  carteira: { PETR4: 0, VALE3: 0, ITUB4: 0, MGLU3: 0 },
  logs: [],
};

const $ = (id) => document.getElementById(id);
const fmt = new Intl.NumberFormat("pt-BR", {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
}).format;
const agora = () => new Date().toLocaleTimeString("pt-BR", { hour12: false });
const achar = (codigo) => estado.ativos.find((a) => a.codigo === codigo);

// desenar as telas
function desenharSaldo() {
  $("saldo").textContent = "R$ " + fmt(estado.saldo);
}

function desenharTabela() {
  $("tabela").innerHTML = estado.ativos
    .map((a) => {
      const v = ((a.preco - a.base) / a.base) * 100;
      const tipo = v > 0.005 ? "alta" : v < -0.005 ? "baixa" : "neutro";
      const sinal = tipo === "alta" ? "+" : tipo === "baixa" ? "−" : "";
      return `
        <div class="linha">
          <span class="codigo">${a.codigo}</span>
          <span class="preco">R$ ${fmt(a.preco)}</span>
          <span class="variacao ${tipo}">${sinal}${fmt(Math.abs(v))}%</span>
        </div>`;
    })
    .join("");
}

function desenharBoleta() {
  const codigo = $("ativo").value;
  $("posicao").textContent = `Você possui: ${estado.carteira[codigo]} ${codigo}`;
  $("preco-mercado").textContent = `Preço de mercado agora: R$ ${fmt(achar(codigo).preco)}`;
}

function desenharFeed() {
  $("feed").innerHTML = estado.logs
    .map((l) => `<div class="feed-item"><time>${l.hora}</time><span class="${l.tipo}">${l.msg}</span></div>`)
    .join("");
}

// atualizacoes do feed 
function log(msg, tipo = "info") {
  estado.logs.unshift({ hora: agora(), msg, tipo });
  estado.logs.length = Math.min(estado.logs.length, MAX_LOGS);
  desenharFeed();
}

// Preços vindos do "servidor"
// Ponto único de entrada de preço. Quando houver um servidor de verdade,
// chame esta função a cada mensagem recebida.
function aplicarPreco(codigo, novoPreco) {
  const ativo = achar(codigo);
  if (!ativo || novoPreco === ativo.preco) return;
  ativo.preco = novoPreco;
  desenharTabela();
  desenharBoleta();
  log(`Preço da ${codigo} atualizou para R$ ${fmt(novoPreco)}.`);
}

// Simulação local: sorteia um ativo a cada 3 segundos.
// Para ligar num servidor real, troque este bloco por:
//   const ws = new WebSocket("ws://localhost:8080");
//   ws.onmessage = (e) => { const m = JSON.parse(e.data); aplicarPreco(m.codigo, m.preco); };
let timerPrecos = null;
function iniciarPrecos() {
  timerPrecos = setInterval(() => {
    const a = estado.ativos[Math.floor(Math.random() * estado.ativos.length)];
    const novo = Math.round(a.preco * (1 + (Math.random() - 0.5) * 0.03) * 100) / 100;
    aplicarPreco(a.codigo, Math.max(0.01, novo));
  }, 3000);
}

// envio de ordens
function enviarOrdem(lado) {
  const codigo = $("ativo").value;
  const qtd = parseInt($("qtd").value, 10);
  const preco = parseFloat($("preco").value.replace(",", "."));

  if (!(qtd > 0) || !(preco > 0)) {
    return log("Informe uma quantidade e um preço válidos.", "erro");
  }

  const total = qtd * preco;

  if (lado === "compra") {
    if (total > estado.saldo) {
      return log(`Saldo insuficiente para comprar ${qtd} ${codigo}.`, "erro");
    }
    estado.saldo -= total;
    estado.carteira[codigo] += qtd;
    log(`Ordem de compra de ${qtd} ${codigo} enviada com sucesso!`, "ok");
  } else {
    if (qtd > estado.carteira[codigo]) {
      return log(`Você não possui ${qtd} ${codigo} para vender.`, "erro");
    }
    estado.saldo += total;
    estado.carteira[codigo] -= qtd;
    log(`Ordem de venda de ${qtd} ${codigo} enviada com sucesso!`, "ok");
  }

  desenharSaldo();
  desenharBoleta();
}

// Navegação entre telas 
function entrar(nome) {
  $("ola").textContent = `Olá, ${nome || "Arthur"}!`;
  $("tela-login").hidden = true;
  $("tela-mercado").hidden = false;

  estado.logs = [];
  log("Conectado ao servidor do mercado.");
  desenharSaldo();
  desenharTabela();
  desenharBoleta();
  iniciarPrecos();
}

function sair() {
  clearInterval(timerPrecos);
  $("tela-mercado").hidden = true;
  $("tela-login").hidden = false;
}

// ---------- Ligação dos eventos ----------
$("ativo").innerHTML = estado.ativos
  .map((a) => `<option value="${a.codigo}">${a.codigo}</option>`)
  .join("");

$("ativo").addEventListener("change", () => {
  $("preco").value = fmt(achar($("ativo").value).preco); // sugere o preço atual
  desenharBoleta();
});

$("form-login").addEventListener("submit", (e) => {
  e.preventDefault();
  entrar($("usuario").value.trim());
});

$("btn-sair").addEventListener("click", sair);
$("btn-comprar").addEventListener("click", () => enviarOrdem("compra"));
$("btn-vender").addEventListener("click", () => enviarOrdem("venda"));
