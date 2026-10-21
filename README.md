# SD-HOMEBROKER

Simulador multiplayer de bolsa de valores desenvolvido para a disciplina de Sistemas Distribuídos da Universidade Federal de Sergipe (UFS).

## Como executar

### Pré-requisitos

- Docker
- Docker Compose

### 1. Clonar o repositório

```bash
git clone https://github.com/VilefilipeDCOMP/SD-HOMEBROKER.git
cd SD-HOMEBROKER
```

### 2. Configurar o ambiente

```bash
cp .env.example .env
```

Edite o arquivo `.env` e configure as credenciais do PostgreSQL.

### 3. Iniciar a aplicação

```bash
docker compose up --build -d
```

### 4. Acessar o sistema

Acesse http://localhost:8081 no navegador.

### 5. Encerrar a aplicação

```bash
docker compose down
```
