# Infraestrutura de comunicação

## Execução

Na raiz do repositório:

docker compose up --build

O arquivo .env deve definir POSTGRES_PASSWORD e não deve ser versionado.

## Destinos STOMP

| Destino | Uso |
|---|---|
| /ws | Endpoint WebSocket |
| /app/... | Comandos enviados pelos clientes |
| /topic/market | Inscrição em eventos públicos |
| /user/queue/... | Destinos individuais previstos para integração |

O endpoint local é ws://localhost:8080/ws.
Utilizamos WebSocket nativo, sem SockJS.

Clientes não podem publicar diretamente nos destinos do broker.
Os handlers dos comandos /app/... serão implementados pelos módulos responsáveis.

## Publicação de eventos

Os serviços do mercado devem receber MarketEventPublisher pelo construtor
e chamar publish(tipo, dados).

O publicador cria um envelope com:
type, requestId, eventId, timestamp e data.

Em broadcasts espontâneos, requestId é null.
Não publique dados privados de investidores em /topic/market.

## Limitações

A conexão ainda não autentica o investidor.
Autenticação e autorização de assinaturas privadas estão pendentes.
O broker mantém as inscrições em memória em uma única instância.
Eventos não são persistidos nem recuperados após uma desconexão.
O PostgreSQL está disponível, mas a persistência da aplicação ainda será integrada.