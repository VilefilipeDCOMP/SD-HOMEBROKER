const MAX_LOGS = 25;

// servidores locais sem proxy continuam acessando o backend diretamente
const WS_LOCAL = location.protocol === "file:" || ["5173", "5500"].includes(location.port);
const WS_PROTOCOL = location.protocol === "https:" ? "wss:" : "ws:";
const WS_HOST = WS_LOCAL ? `${location.hostname || "localhost"}:8086` : location.host;
const WS_URL = `${WS_PROTOCOL}//${WS_HOST}/ws`;
const AUTH_BASE = WS_LOCAL ? `${WS_PROTOCOL === "wss:" ? "https:" : "http:"}//${WS_HOST}` : "";

const estado = {
  saldo: 10000,
  saldoReservado: 0,
  ativos: [
    { codigo: "PETR4", nome: "Petrobras", preco: 35.0, base: 35.0 },
    { codigo: "VALE3", nome: "Vale", preco: 68.0, base: 68.0 },
    { codigo: "ITUB4", nome: "Itaú Unibanco", preco: 30.5, base: 30.5 },
    { codigo: "MGLU3", nome: "Magazine Luiza", preco: 2.1, base: 2.1 },
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
  abaAtiva: "ordens",
  ativoSelecionado: "PETR4",
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

function arredondarMoeda(valor) {
  return Math.round(Number(valor || 0) * 100) / 100;
}

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
  const valorAtual = arredondarMoeda(quantidade * precoAtual);
  const custoTotal = arredondarMoeda(quantidade * precoMedio);
  const lucroPrejuizo = quantidade > 0 && precoMedio > 0 ? arredondarMoeda(valorAtual - custoTotal) : 0;
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
      valorEmAtivos = arredondarMoeda(valorEmAtivos + item.valorAtual);
      custoTotal = arredondarMoeda(custoTotal + item.custoTotal);
      lucroPrejuizoTotal = arredondarMoeda(lucroPrejuizoTotal + item.lucroPrejuizo);
      posicoesAtivas.push(item);
    }
  }

  posicoesAtivas.sort((a, b) => a.codigo.localeCompare(b.codigo));

  const saldoDisponivel = arredondarMoeda(estado.saldo);
  const saldoReservado = arredondarMoeda(estado.saldoReservado);
  const patrimonioTotal = arredondarMoeda(saldoDisponivel + saldoReservado + valorEmAtivos);
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

function aplicarPreco(codigo, novoPreco, nome) {
  let ativo = achar(codigo);
  if (!ativo) {
    ativo = { codigo, nome: nome || "", preco: novoPreco, base: novoPreco };
    estado.ativos.push(ativo);
    garantirPosicao(codigo);
    atualizarSelectAtivos();
  } else if (nome && !ativo.nome) {
    ativo.nome = nome;
  }
  if (novoPreco === ativo.preco) return;
  ativo.preco = novoPreco;
  desenharTabela();
  desenharBoleta();
  desenharCarteira();
  desenharResumoFinanceiro();
  log(`Preço da ${codigo} atualizou para R$ ${fmt(novoPreco)}.`);
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
      return `
        <div class="linha linha-mercado">
          <span class="codigo">${esc(a.codigo)}<small class="nome-empresa">${a.nome ? ` · ${esc(a.nome)}` : ""}</small></span>
          <span class="mono dir">R$ ${fmt(a.preco)}</span>
          <span class="mono dir ${tipo}">${sinal}${fmt(Math.abs(v))}%</span>
          <span class="mono dir">0</span>
          <span class="mono dir alta">—</span>
          <span class="mono dir baixa">—</span>
        </div>`;
    })
    .join("");
  setHtml("tabela", html);
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
  setTexto("total-ordem", qtd > 0 && preco > 0 ? `Total estimado: R$ ${fmt(qtd * preco)}` : "");
}

function desenharFeed() {
  const html = estado.logs
    .map((l) => `<div class="feed-item"><time>${esc(l.hora)}</time><span class="${esc(l.tipo)}">${esc(l.msg)}</span></div>`)
    .join("");
  setHtml("feed", html);
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

// --- conexao websocket/stomp ---

let stomp = null;
let timerPrecos = null;

function conectarStomp(token) {
  stomp = new StompJs.Client({
    brokerURL: WS_URL,
    connectHeaders: {
      Authorization: `Bearer ${token}`,
    },
    reconnectDelay: 5000,

    onConnect: () => {
      log("Conectado ao servidor do mercado.");

      if (timerPrecos && typeof clearInterval !== "undefined") {
        clearInterval(timerPrecos);
        timerPrecos = null;
      }

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

      stomp.subscribe("/user/queue/replies", (msg) => {
        try {
          const resp = JSON.parse(msg.body);
          const d = resp.data !== undefined ? resp.data : resp;
          const tipo = resp.type || "";

          const listaAtivos = Array.isArray(d) ? d : (Array.isArray(resp.ativos) ? resp.ativos : null);
          if (listaAtivos && (tipo === "ASSET_LIST" || !tipo)) {
            listaAtivos.forEach((a) => {
              const cod = a.symbol || a.ticker || a.codigo;
              const p = a.price ?? a.currentPrice ?? a.preco_atual ?? a.preco;
              const nome = a.name || a.nome || a.nome_empresa || "";
              if (cod && p != null) aplicarPreco(cod, Number(p), nome);
            });
            log("Catálogo oficial de ativos sincronizado.", "info");
            return;
          }

          if (tipo === "WALLET_SNAPSHOT" || tipo === "WALLET_UPDATED" || tipo === "PORTFOLIO_UPDATED") {
            aplicarSnapshotCarteira(d, tipo === "WALLET_SNAPSHOT");
            if (tipo === "WALLET_SNAPSHOT") {
              log("Carteira sincronizada com o servidor.", "info");
            }
            return;
          }

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
      });

      stomp.subscribe("/user/queue/orders", (msg) => {
        try {
          const resp = JSON.parse(msg.body);
          const d = resp.data || resp;
          const tipoEvento = resp.type || "";

          if (tipoEvento === "WALLET_UPDATED" || tipoEvento === "PORTFOLIO_UPDATED") {
            aplicarSnapshotCarteira(d, false);
            return;
          }

          const status = d.status || (d.sucesso ? "ACCEPTED" : (d.status === "OK" ? "ACCEPTED" : "REJECTED"));
          const ehSucesso = status === "ACCEPTED" || status === "OK";
          const tipoVisual = ehSucesso ? "ok" : "erro";

          const razoes = {
            INVALID_FIELDS: "Dados da ordem inválidos.",
            INVALID_QUANTITY: "Quantidade inválida.",
            INVALID_PRICE: "Preço inválido.",
            UNKNOWN_ASSET: "Ativo não encontrado no mercado.",
            INSUFFICIENT_FUNDS: "Saldo insuficiente para esta compra.",
            INSUFFICIENT_SHARES: "Você não possui ações suficientes para vender.",
            SYSTEM_BUSY: "Sistema sobrecarregado. Fila cheia.",
            INTERNAL_ERROR: "Erro interno do servidor.",
          };

          const ladoDesc = d.side === "BUY" ? "Compra" : d.side === "SELL" ? "Venda" : (d.side || "Operação");
          const precoFmt = d.price != null ? ` @ R$ ${fmt(d.price)}` : "";
          const qtdFmt = d.quantity != null ? ` de ${d.quantity} ${d.symbol || ""}` : "";

          if (status === "RECEIVED") {
            log(`Ordem de ${ladoDesc}${qtdFmt}${precoFmt} recebida pelo backend. Aguardando processamento.`, "info");
            return;
          }

          let mensagem;
          if (ehSucesso) {
            if (tipoEvento === "ORDER_ACK") {
              mensagem = `Ordem de ${ladoDesc}${qtdFmt}${precoFmt} aceita pelo backend!`;
            } else if (tipoEvento === "ORDER_UPDATED") {
              mensagem = `Ordem de ${ladoDesc}${qtdFmt}${precoFmt} aceita pelo processador. Aguardando execução.`;
            } else {
              mensagem = `Ordem de ${ladoDesc}${qtdFmt}${precoFmt} aceita com sucesso!`;
            }
          } else {
            mensagem = razoes[d.reason]
              || d.mensagem
              || d.message
              || `Ordem rejeitada: ${d.reason || status}`;
          }

          log(mensagem, tipoVisual);

          if (d.saldo != null || d.balance != null || d.saldoReservado != null || d.reservedBalance != null || d.carteira || d.portfolio || d.positions) {
            aplicarSnapshotCarteira(d, false);
          }
        } catch (e) {
          console.error("Erro ao processar retorno da ordem", e);
        }
      });

      stomp.publish({ destination: "/app/assets/list", body: "{}" });
      stomp.publish({ destination: "/app/wallet/me", body: "{}" });
      stomp.publish({ destination: "/app/orders/mine", body: "{}" });
    },

    onDisconnect: () => {
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
        log("Erro STOMP: " + (msg || "falha na conexão"), "erro");
      }
    },
  });

  stomp.activate();
}

function enviarOrdem(lado) {
  const codigo = ($("ativo") && $("ativo").value) || estado.ativoSelecionado;
  const qtd = parseInt($("qtd").value, 10);
  const preco = parseFloat(String($("preco").value).replace(",", "."));

  if (!(qtd > 0) || !(preco > 0)) {
    return log("Informe uma quantidade e um preço válidos.", "erro");
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
    log("Sem conexão com o servidor. A ordem não foi enviada.", "erro");
  }
}

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
  estado.logs = [];
  setHidden("tela-login", true);
  setHidden("tela-mercado", false);
  setTexto("ola", `Olá, ${estado.usuario}!`);

  desenharResumoFinanceiro();
  desenharTabela();
  desenharCarteira();
  desenharBoleta();
  selecionarAba("ordens");

  if (token) {
    conectarStomp(token);
  } else {
    log("Servidor indisponível — modo demonstração local.", "erro");
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
  if (timerPrecos && typeof clearInterval !== "undefined") {
    clearInterval(timerPrecos);
    timerPrecos = null;
  }
  if (stomp) {
    stomp.deactivate();
    stomp = null;
  }
  storageRemove("jwtToken");
  setHidden("tela-mercado", true);
  setHidden("tela-login", false);
}

function ligarEvento(id, evento, fn) {
  const el = $(id);
  if (el && typeof el.addEventListener === "function") {
    el.addEventListener(evento, fn);
  }
}

atualizarSelectAtivos();

ligarEvento("ativo", "change", () => {
  const sel = $("ativo");
  if (sel) {
    estado.ativoSelecionado = sel.value;
    const ativo = achar(sel.value);
    if (ativo && $("preco")) $("preco").value = fmt(ativo.preco);
    desenharBoleta();
  }
});

ligarEvento("qtd", "input", desenharBoleta);
ligarEvento("preco", "input", desenharBoleta);

ligarEvento("tab-ordens", "click", () => selecionarAba("ordens"));
ligarEvento("tab-negocios", "click", () => selecionarAba("negocios"));
ligarEvento("tab-feed", "click", () => selecionarAba("feed"));

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
