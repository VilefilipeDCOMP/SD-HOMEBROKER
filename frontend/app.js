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
const ETAPAS_SINCRONIZACAO = [
  "ASSET_LIST",
  "WALLET_SNAPSHOT",
  "ORDER_BOOK_SNAPSHOT",
  "ORDER_HISTORY",
  "TRADE_HISTORY",
];

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
    sincronizando: false,
    origemSincronizacao: null,
    pendentesSincronizacao: [],
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
    ? arredondarMoeda(Math.max(0, melhorVenda - melhorCompra))
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
  } else if (nome && nome !== ativo.nome) {
    ativo.nome = nome;
    adicionou = true;
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
  const idBusca = d.orderId || d.id || null;
  const cidBusca = d.clientOrderId || null;
  const idx = estado.ordens.findIndex((o) =>
    (idBusca && o.orderId === idBusca) ||
    (cidBusca && o.clientOrderId === cidBusca)
  );

  const base = idx >= 0 ? estado.ordens[idx] : {};
  const statusUpper = String(d.status || base.status || "RECEIVED").toUpperCase();
  const totalBase = d.quantity ?? d.quantidade ?? base.quantity;
  const ordem = normalizarOrdem({
    ...base,
    ...d,
    orderId: idBusca || base.orderId || null,
    clientOrderId: cidBusca || base.clientOrderId || null,
    symbol: d.symbol || d.ticker || d.codigo || base.symbol || "",
    side: d.side || d.tipo || base.side || "",
    quantity: totalBase,
    filledQuantity: d.filledQuantity ?? d.executedQuantity ?? (statusUpper === "FILLED" ? totalBase : base.filledQuantity),
    price: d.price ?? d.preco_alvo ?? base.price ?? null,
    status: statusUpper,
  });

  if (!ordem.orderId && !ordem.clientOrderId) return ordem;

  if (idx >= 0) {
    estado.ordens[idx] = ordem;
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
    total: d.total != null ? arredondarMoeda(d.total) : arredondarMoeda(quantity * price),
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
const tradesProcessados = new Set();
let volumesDuranteSync = {};

function cancelarSincronizacaoPendente() {
  estado.conexao.sincronizando = false;
  estado.conexao.origemSincronizacao = null;
  estado.conexao.pendentesSincronizacao = [];
  volumesDuranteSync = {};
}

// confirma a sincronizacao somente quando todos os 5 snapshots forem aplicados
function concluirEtapaSincronizacao(etapa) {
  if (!estado.conexao.sincronizando) return;
  estado.conexao.pendentesSincronizacao = estado.conexao.pendentesSincronizacao.filter((item) => item !== etapa);
  if (estado.conexao.pendentesSincronizacao.length > 0) return;

  const origem = estado.conexao.origemSincronizacao;
  estado.conexao.sincronizando = false;
  estado.conexao.origemSincronizacao = null;
  volumesDuranteSync = {};
  estado.conexao.ultimaSincronizacao = agora();

  if (origem === "reconexao") {
    log("Reconectado ao servidor. Estado completo recarregado.", "ok");
    mostrarAlerta("Conexão restabelecida. Ativos, carteira, livro, ordens e negociações sincronizados.", "ok");
  } else if (origem === "manual") {
    log("Estado completo sincronizado com o servidor.", "ok");
    mostrarAlerta("Sincronização concluída: ativos, carteira, livro, ordens e negociações atualizados.", "ok");
  } else {
    log("Estado inicial sincronizado com o servidor.", "ok");
  }
}

// solicita snapshot completo ao backend no login e apos cada reconexao
function recarregarEstadoCompleto(ehReconexao = false, exigirConectado = false) {
  if (!stomp) return false;
  if (exigirConectado && !stomp.connected) {
    mostrarAlerta("Sem conexão com o servidor para sincronizar o estado.", "erro");
    return false;
  }

  const preservarReconexao = estado.conexao.sincronizando && estado.conexao.origemSincronizacao === "reconexao";
  estado.conexao.sincronizando = true;
  estado.conexao.origemSincronizacao = (ehReconexao || preservarReconexao)
    ? "reconexao"
    : (exigirConectado ? "manual" : "inicial");
  estado.conexao.pendentesSincronizacao = [...ETAPAS_SINCRONIZACAO];
  volumesDuranteSync = {};

  if (ehReconexao) {
    log("Conexão restabelecida. Solicitando snapshots ao servidor...", "info");
  }

  stomp.publish({ destination: "/app/assets/list", body: "{}" });
  stomp.publish({ destination: "/app/wallet/me", body: "{}" });
  stomp.publish({ destination: "/app/orders/mine", body: "{}" });
  stomp.publish({ destination: "/app/trades/mine", body: "{}" });
  stomp.publish({
    destination: "/app/orderbook/snapshot",
    body: JSON.stringify({ symbol: estado.ativoSelecionado }),
  });

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
      const idNegocio = d.tradeId != null ? String(d.tradeId) : (d.id != null ? String(d.id) : null);
      const jaContabilizado = Boolean(idNegocio && tradesProcessados.has(idNegocio));
      if (idNegocio) {
        tradesProcessados.add(idNegocio);
      }

      let vol;
      if (d.volume != null && Number.isFinite(Number(d.volume))) {
        vol = Number(d.volume);
        if (estado.conexao.sincronizando && cod) {
          volumesDuranteSync[cod] = Math.max(volumesDuranteSync[cod] || 0, vol);
        }
      } else if (jaContabilizado) {
        vol = ativo ? (ativo.volume || 0) : 0;
      } else {
        const qtd = Number(d.quantity ?? d.quantidade ?? 0);
        vol = ativo ? (ativo.volume || 0) + qtd : qtd;
      }

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
        const volRecebido = a.volume ?? a.tradedVolume;
        const volFinal = volRecebido != null
          ? Math.max(Number(volRecebido), volumesDuranteSync[cod] || 0)
          : undefined;
        if (cod && p != null) {
          aplicarPreco(cod, Number(p), nome, {
            volume: volFinal,
            melhorCompra: a.bestBid !== undefined ? a.bestBid : undefined,
            melhorVenda: a.bestAsk !== undefined ? a.bestAsk : undefined,
            silencioso: true,
          });
        }
      });
      log("Catálogo oficial de ativos sincronizado.", "info");
      concluirEtapaSincronizacao("ASSET_LIST");
      return;
    }

    // 2. snapshot ou atualizacao de carteira
    if (tipo === "WALLET_SNAPSHOT" || tipo === "WALLET_UPDATED" || tipo === "PORTFOLIO_UPDATED") {
      aplicarSnapshotCarteira(d, tipo === "WALLET_SNAPSHOT");
      if (tipo === "WALLET_SNAPSHOT") {
        log("Carteira sincronizada com o servidor.", "info");
        concluirEtapaSincronizacao("WALLET_SNAPSHOT");
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
      if (tipo === "ORDER_BOOK_SNAPSHOT") {
        log(`Livro de ofertas (${estado.ativoSelecionado}) sincronizado.`, "info");
        concluirEtapaSincronizacao("ORDER_BOOK_SNAPSHOT");
      }
      return;
    }

    // 4. historico de ordens do usuario (substitui lista inteira para manter consistencia pos-reconexao)
    if (tipo === "ORDER_HISTORY" && Array.isArray(d)) {
      estado.ordens = d.map(normalizarOrdem).reverse();
      desenharOrdens();
      log(`Histórico sincronizado: ${d.length} ordem(ns) registrada(s).`, "info");
      concluirEtapaSincronizacao("ORDER_HISTORY");
      return;
    }

    // 5. historico de negociacoes do usuario
    if (tipo === "TRADE_HISTORY" && Array.isArray(d)) {
      estado.negocios = d.map(normalizarNegocio).slice(0, MAX_NEGOCIOS);
      estado.negocios.forEach((n) => {
        if (n.tradeId) tradesProcessados.add(String(n.tradeId));
      });
      desenharNegocios();
      log(`Negociações sincronizadas: ${d.length} registro(s).`, "info");
      concluirEtapaSincronizacao("TRADE_HISTORY");
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

    if (tipoEvento === "ORDER_CANCEL_REJECTED") {
      const motivo = d.reason === "INVALID_FIELDS"
        ? "Ordem não encontrada ou indisponível para cancelamento."
        : (String(d.status || "").toUpperCase() === "FILLED"
          ? "Esta ordem já foi executada e não pode mais ser cancelada."
          : (RAZOES_REJEICAO[d.reason] || d.mensagem || d.message || d.reason || "Não foi possível cancelar a ordem."));
      const msgRecusa = `Cancelamento recusado: ${motivo}`;
      log(msgRecusa, "erro");
      mostrarAlerta(msgRecusa, "erro");
      if (d.status && (d.symbol || d.ticker)) {
        registrarOrdem(d);
      }
      return;
    }

    const status = String(
      d.status || (tipoEvento === "ORDER_CANCELLED" ? "CANCELLED" : (d.sucesso ? "ACCEPTED" : "REJECTED"))
    ).toUpperCase();

    const ordem = registrarOrdem({ ...d, status });

    const ladoDesc = ordem.side === "BUY" ? "Compra" : ordem.side === "SELL" ? "Venda" : (ordem.side || "Operação");
    const precoFmt = ordem.price != null ? ` @ R$ ${fmt(ordem.price)}` : "";
    const qtdFmt = ordem.quantity > 0 ? ` de ${ordem.quantity} ${ordem.symbol || ""}` : "";

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
      stomp.subscribe("/topic/events", tratarEventoMercado);
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
      cancelarSincronizacaoPendente();
      estado.conexao.tentativas += 1;
      estado.conexao.status = "reconectando";
      desenharStatusConexao();

      if (estavaConectado || estado.conexao.tentativas === 1) {
        log("Conexão perdida com o servidor. Tentando reconectar...", "erro");
      }
    },

    onDisconnect: () => {
      if (saindoVoluntariamente) return;
      cancelarSincronizacaoPendente();
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
  tradesProcessados.clear();
  cancelarSincronizacaoPendente();
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
  tradesProcessados.clear();
  cancelarSincronizacaoPendente();
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
