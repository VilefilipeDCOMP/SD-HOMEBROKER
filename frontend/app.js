
const MAX_LOGS = 8;
const WS_URL   = "ws://localhost:8080/ws";
const AUTH_BASE = "http://localhost:8080";

const estado = {
  saldo: 10000,
  ativos: [
    { codigo: "PETR4", nome: "Petrobras",     preco: 35.0,  base: 35.0  },
    { codigo: "VALE3", nome: "Vale",           preco: 68.0,  base: 68.0  },
    { codigo: "ITUB4", nome: "Itaú Unibanco",  preco: 30.5,  base: 30.5  },
    { codigo: "MGLU3", nome: "Magazine Luiza", preco: 2.1,   base: 2.1   },
  ],
  carteira: { PETR4: 0, VALE3: 0, ITUB4: 0, MGLU3: 0 },
  logs: [],
  usuario: null,
  modoAuth: "login", // "login" ou "register"
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
          <span class="codigo">${a.codigo}<small class="nome-empresa">${a.nome ? ` · ${a.nome}` : ""}</small></span>
          <span class="preco">R$ ${fmt(a.preco)}</span>
          <span class="variacao ${tipo}">${sinal}${fmt(Math.abs(v))}%</span>
        </div>`;
    })
    .join("");
}

function desenharBoleta() {
  const codigo = $("ativo").value;
  const pos = estado.carteira[codigo] ?? 0;
  const ativo = achar(codigo);
  $("posicao").textContent = `Você possui: ${pos} ${codigo}`;
  $("preco-mercado").textContent = ativo
    ? `Preço de mercado agora: R$ ${fmt(ativo.preco)}`
    : "";
}

function desenharFeed() {
  $("feed").innerHTML = estado.logs
    .map((l) => `<div class="feed-item"><time>${l.hora}</time><span class="${l.tipo}">${l.msg}</span></div>`)
    .join("");
}

function atualizarSelectAtivos() {
  const sel = $("ativo");
  const atual = sel.value;
  sel.innerHTML = estado.ativos
    .map((a) => `<option value="${a.codigo}">${a.codigo}${a.nome ? ` — ${a.nome}` : ""}</option>`)
    .join("");
  if (atual && achar(atual)) sel.value = atual;
}

// atualizacoes do feed
function log(msg, tipo = "info") {
  estado.logs.unshift({ hora: agora(), msg, tipo });
  estado.logs.length = Math.min(estado.logs.length, MAX_LOGS);
  desenharFeed();
}

// ponto unico de entrada de preco
function aplicarPreco(codigo, novoPreco, nome) {
  let ativo = achar(codigo);
  if (!ativo) {
    ativo = { codigo, nome: nome || "", preco: novoPreco, base: novoPreco };
    estado.ativos.push(ativo);
    if (estado.carteira[codigo] === undefined) estado.carteira[codigo] = 0;
    atualizarSelectAtivos();
  } else if (nome && !ativo.nome) {
    ativo.nome = nome;
  }
  if (novoPreco === ativo.preco) return;
  ativo.preco = novoPreco;
  desenharTabela();
  desenharBoleta();
  log(`Preço da ${codigo} atualizou para R$ ${fmt(novoPreco)}.`);
}

// --- autenticacao REST ---

// faz login ou cadastro no backend e retorna { token, username, ... }
async function authRest(endpoint, username, password) {
  const res = await fetch(`${AUTH_BASE}${endpoint}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  const data = await res.json();
  if (!res.ok) {
    throw new Error(data.error || `Erro ${res.status}`);
  }
  return data;
}

// mostra ou limpa mensagem de erro no formulário de login
function setErroAuth(msg) {
  let el = $("erro-auth");
  if (!el) {
    el = document.createElement("p");
    el.id = "erro-auth";
    el.className = "nota erro";
    $("form-login").insertBefore(el, $("btn-submit-auth"));
  }
  el.textContent = msg || "";
  el.hidden = !msg;
}

// alterna entre modo "entrar" e "criar conta" na tela de login
function toggleModoAuth() {
  estado.modoAuth = estado.modoAuth === "login" ? "register" : "login";
  const isRegister = estado.modoAuth === "register";
  $("btn-submit-auth").textContent   = isRegister ? "Criar conta"    : "Entrar no mercado";
  $("btn-toggle-auth").textContent   = isRegister ? "Já tenho conta" : "Criar conta";
  $("label-toggle-auth").textContent = isRegister ? "Já tem conta?"  : "Não tem conta?";
  setErroAuth("");
}

// --- conexao websocket/stomp ---

let stomp = null;
let timerPrecos = null;

// recebe o token JWT e abre a conexão STOMP autenticada
function conectarStomp(token) {
  stomp = new StompJs.Client({
    brokerURL: WS_URL,
    connectHeaders: {
      // token JWT no header do CONNECT — backend valida aqui, nao na URL
      Authorization: `Bearer ${token}`,
    },
    reconnectDelay: 5000,

    onConnect: () => {
      log("Conectado ao servidor do mercado.");

      // para a simulacao local se o server conectou
      if (timerPrecos) {
        clearInterval(timerPrecos);
        timerPrecos = null;
      }

      // topico publico de mercado
      stomp.subscribe("/topic/market", (msg) => {
        try {
          const evento = JSON.parse(msg.body);
          const d = evento.data || evento;
          const cod = d.symbol || d.ticker || d.codigo || d.assetCode;
          const preco = d.currentPrice ?? d.preco_atual ?? d.price ?? d.preco;
          const nome = d.name || d.nome || d.nome_empresa || "";
          if (cod && preco != null) {
            aplicarPreco(cod, Number(preco), nome);
          }
        } catch (e) {
          console.error("Erro ao ler evento do mercado", e);
        }
      });

      // respostas individuais (dados de saldo, carteira ou lista de ativos)
      stomp.subscribe("/user/queue/replies", (msg) => {
        try {
          const resp = JSON.parse(msg.body);
          if (resp.saldo != null) {
            estado.saldo = Number(resp.saldo);
            desenharSaldo();
          }
          if (resp.carteira) {
            Object.assign(estado.carteira, resp.carteira);
            desenharBoleta();
          }
          if (Array.isArray(resp.ativos)) {
            resp.ativos.forEach((a) => {
              const cod = a.symbol || a.ticker || a.codigo;
              const p = a.currentPrice ?? a.preco_atual ?? a.preco ?? a.price;
              const nome = a.name || a.nome || a.nome_empresa || "";
              if (cod && p != null) aplicarPreco(cod, Number(p), nome);
            });
          }
        } catch (e) {
          console.error("Erro na resposta do servidor", e);
        }
      });

      // confirmacao ou rejeicao de ordens
      stomp.subscribe("/user/queue/orders", (msg) => {
        try {
          const resp = JSON.parse(msg.body);
          const status = resp.status || (resp.sucesso ? "ACCEPTED" : "REJECTED");
          const tipo   = status === "ACCEPTED" || status === "OK" ? "ok" : "erro";
          // mapeia RejectReason do backend para mensagem amigavel em portugues
          const razoes = {
            INVALID_FIELDS:      "Dados da ordem inválidos.",
            INVALID_QUANTITY:    "Quantidade inválida.",
            INVALID_PRICE:       "Preço inválido.",
            UNKNOWN_ASSET:       "Ativo não encontrado no mercado.",
            INSUFFICIENT_FUNDS:  "Saldo insuficiente para esta compra.",
            INSUFFICIENT_SHARES: "Você não possui ações suficientes para vender.",
            SYSTEM_BUSY:         "Sistema sobrecarregado. Tente novamente.",
            INTERNAL_ERROR:      "Erro interno do servidor.",
          };
          const mensagem = razoes[resp.reason]
            || resp.mensagem
            || resp.message
            || `Ordem: ${status}`;
          log(mensagem, tipo);

          if (resp.saldo != null) {
            estado.saldo = Number(resp.saldo);
            desenharSaldo();
          }
          if (resp.carteira) {
            Object.assign(estado.carteira, resp.carteira);
            desenharBoleta();
          }
        } catch (e) {
          console.error("Erro ao processar retorno da ordem", e);
        }
      });

      // requisicoes iniciais
      stomp.publish({ destination: "/app/assets/list", body: "{}" });
    },

    onDisconnect: () => {
      log("Desconectado do servidor.", "erro");
    },

    onStompError: (frame) => {
      const msg = frame.headers?.message || "";
      // token expirado ou invalido — limpa e volta para o login
      if (
        msg.toLowerCase().includes("token") ||
        msg.toLowerCase().includes("unauthorized") ||
        msg.includes("401")
      ) {
        log("Sessão expirada. Faça login novamente.", "erro");
        localStorage.removeItem("jwtToken");
        sair();
      } else {
        log("Erro STOMP: " + (msg || "falha na conexão"), "erro");
      }
    },
  });

  stomp.activate();
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

  if (stomp && stomp.connected) {
    // envia pro backend processar
    stomp.publish({
      destination: "/app/orders/create",
      body: JSON.stringify({
        clientOrderId: crypto.randomUUID(),   // correlação request ↔ resposta
        symbol: codigo,
        side: lado === "compra" ? "BUY" : "SELL",
        quantity: qtd,
        price: preco,
        // userId NÃO é enviado pelo frontend — o backend injeta via sessão STOMP
      }),
    });
    log(`Ordem de ${lado} de ${qtd} ${codigo} enviada.`);
  } else {
    // se tiver sem backend, roda local
    if (lado === "compra") {
      if (total > estado.saldo) {
        return log(`Saldo insuficiente para comprar ${qtd} ${codigo}.`, "erro");
      }
      estado.saldo -= total;
      estado.carteira[codigo] = (estado.carteira[codigo] ?? 0) + qtd;
      log(`Ordem de compra de ${qtd} ${codigo} enviada com sucesso!`, "ok");
    } else {
      if (qtd > (estado.carteira[codigo] ?? 0)) {
        return log(`Você não possui ${qtd} ${codigo} para vender.`, "erro");
      }
      estado.saldo += total;
      estado.carteira[codigo] -= qtd;
      log(`Ordem de venda de ${qtd} ${codigo} enviada com sucesso!`, "ok");
    }
    desenharSaldo();
    desenharBoleta();
  }
}

// --- navegacao entre telas ---

// tenta login REST; se o servidor nao responder, permanece na tela de login
async function entrar(nome, senha) {
  if (!nome) return setErroAuth("Informe um nome de usuário.");

  let token = null;

  try {
    const data = await authRest("/auth/login", nome, senha);
    token = data.token;
    localStorage.setItem("jwtToken", token);
    estado.usuario = data.username || nome;
  } catch (e) {
    // credenciais erradas ou servidor fora — permanece na tela de login
    setErroAuth(e.message || "Erro ao conectar ao servidor.");
    return;
  }

  // transicao para a tela de mercado
  setErroAuth("");
  $("tela-login").hidden  = true;
  $("tela-mercado").hidden = false;
  $("ola").textContent    = `Olá, ${estado.usuario}!`;
  estado.logs = [];
  desenharSaldo();
  desenharTabela();
  desenharBoleta();

  // conecta STOMP com o token JWT
  conectarStomp(token);

  // simulacao local de reserva caso o STOMP nao conecte
  timerPrecos = setInterval(() => {
    if (stomp && stomp.connected) return;
    const a   = estado.ativos[Math.floor(Math.random() * estado.ativos.length)];
    const novo = Math.round(a.preco * (1 + (Math.random() - 0.5) * 0.03) * 100) / 100;
    aplicarPreco(a.codigo, Math.max(0.01, novo));
  }, 3000);
}

// cadastra novo usuario e, em seguida, faz login automatico
async function cadastrar(nome, senha) {
  if (!nome || !senha) return setErroAuth("Informe usuário e senha para criar conta.");

  try {
    await authRest("/auth/register", nome, senha);
    // cadastro ok — loga automaticamente
    await entrar(nome, senha);
  } catch (e) {
    setErroAuth(e.message || "Erro ao criar conta.");
  }
}

function sair() {
  if (timerPrecos) {
    clearInterval(timerPrecos);
    timerPrecos = null;
  }
  if (stomp) {
    stomp.deactivate();
    stomp = null;
  }
  localStorage.removeItem("jwtToken");
  $("tela-mercado").hidden = true;
  $("tela-login").hidden   = false;
}

// ligacao entre os eventos
atualizarSelectAtivos();

$("ativo").addEventListener("change", () => {
  const ativo = achar($("ativo").value);
  if (ativo) $("preco").value = fmt(ativo.preco);
  desenharBoleta();
});

$("form-login").addEventListener("submit", (e) => {
  e.preventDefault();
  const nome  = $("usuario").value.trim();
  const senha = $("senha").value;
  if (estado.modoAuth === "register") {
    cadastrar(nome, senha);
  } else {
    entrar(nome, senha);
  }
});

$("btn-toggle-auth").addEventListener("click", toggleModoAuth);
$("btn-sair").addEventListener("click", sair);
$("btn-comprar").addEventListener("click", () => enviarOrdem("compra"));
$("btn-vender").addEventListener("click", () => enviarOrdem("venda"));
