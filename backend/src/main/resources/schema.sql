CREATE TABLE IF NOT EXISTS usuarios (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,      
    nome VARCHAR(100),                         
    email VARCHAR(100) UNIQUE,                 
    senha_hash VARCHAR(255) NOT NULL,          
    saldo NUMERIC(15, 2) NOT NULL DEFAULT 10000.00,
    criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sessoes_usuario (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(255) UNIQUE NOT NULL,
    usuario_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    conectado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    desconectado_em TIMESTAMP
);

CREATE TABLE IF NOT EXISTS participantes_mercado (
    id BIGSERIAL PRIMARY KEY,
    usuario_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    entrou_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    saiu_em TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ativos (
    id BIGSERIAL PRIMARY KEY,
    ticker VARCHAR(10) UNIQUE NOT NULL,
    nome_empresa VARCHAR(100) NOT NULL,
    preco_atual NUMERIC(15, 2) NOT NULL
);

CREATE TABLE IF NOT EXISTS carteiras (
    usuario_id BIGINT REFERENCES usuarios(id) ON DELETE CASCADE,
    ativo_id BIGINT REFERENCES ativos(id) ON DELETE CASCADE,
    quantidade INT NOT NULL DEFAULT 0,
    preco_medio NUMERIC(15, 2) NOT NULL DEFAULT 0.00,
    PRIMARY KEY (usuario_id, ativo_id)
);

CREATE TABLE IF NOT EXISTS ordens (
    id BIGSERIAL PRIMARY KEY,
    usuario_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    ativo_id BIGINT NOT NULL REFERENCES ativos(id) ON DELETE RESTRICT,
    tipo VARCHAR(10) NOT NULL CHECK (tipo IN ('COMPRA', 'VENDA')),
    quantidade INT NOT NULL CHECK (quantidade > 0),
    preco_alvo NUMERIC(15, 2) NOT NULL CHECK (preco_alvo > 0),
    status VARCHAR(20) NOT NULL DEFAULT 'ABERTA' CHECK (status IN ('ABERTA', 'EXECUTADA', 'CANCELADA')),
    criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS historico_negociacoes (
    id BIGSERIAL PRIMARY KEY,
    ordem_compra_id BIGINT NOT NULL REFERENCES ordens(id),
    ordem_venda_id BIGINT NOT NULL REFERENCES ordens(id),
    ativo_id BIGINT NOT NULL REFERENCES ativos(id),
    quantidade_executada INT NOT NULL CHECK (quantidade_executada > 0),
    preco_executado NUMERIC(15, 2) NOT NULL CHECK (preco_executado > 0),
    executado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);