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
    quantidade INT NOT NULL CHECK (quantidade >= 0),
    quantidade_executada INT NOT NULL DEFAULT 0 CHECK (quantidade_executada >= 0),
    preco_alvo NUMERIC(15, 2) NOT NULL CHECK (preco_alvo > 0),
    status VARCHAR(20) NOT NULL DEFAULT 'ABERTA' CHECK (status IN ('ABERTA', 'EXECUTADA', 'CANCELADA')),
    client_order_id VARCHAR(64),
    order_id VARCHAR(64),
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

ALTER TABLE ordens ADD COLUMN IF NOT EXISTS client_order_id VARCHAR(64);
ALTER TABLE ordens ADD COLUMN IF NOT EXISTS order_id VARCHAR(64);
ALTER TABLE ordens ADD COLUMN IF NOT EXISTS quantidade_executada INT DEFAULT 0;


UPDATE ordens
   SET quantidade_executada = COALESCE((
           SELECT SUM(h.quantidade_executada)
             FROM historico_negociacoes h
            WHERE h.ordem_compra_id = ordens.id
               OR h.ordem_venda_id = ordens.id
       ), 0)
 WHERE client_order_id IS NULL;


UPDATE ordens
   SET client_order_id = '__legacy__' || CAST(id AS VARCHAR)
 WHERE client_order_id IS NULL;

UPDATE ordens
   SET order_id = '__legacy-db__' || CAST(id AS VARCHAR)
 WHERE order_id IS NULL;

ALTER TABLE ordens ALTER COLUMN client_order_id SET NOT NULL;
ALTER TABLE ordens ALTER COLUMN order_id SET NOT NULL;
ALTER TABLE ordens ALTER COLUMN quantidade_executada SET NOT NULL;


CREATE UNIQUE INDEX IF NOT EXISTS ux_ordens_usuario_client_order_id
    ON ordens (usuario_id, client_order_id);

CREATE INDEX IF NOT EXISTS ix_ordens_status_criado_em
    ON ordens (status, criado_em);
CREATE INDEX IF NOT EXISTS ix_ordens_usuario_criado_em
    ON ordens (usuario_id, criado_em);