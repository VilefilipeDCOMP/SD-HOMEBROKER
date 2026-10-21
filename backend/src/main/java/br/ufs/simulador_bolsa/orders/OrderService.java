package br.ufs.simulador_bolsa.orders;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


@RequiredArgsConstructor
@Service
//é o service das ações, pode ser chamado por várias threads ao mesmo tempo
//então vamos ter que mexer com synchorized e lock
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderValidator validator;
    private final OrderProcessor processor;

    private final Object enqueueLock = new Object();
    private long lastSequence;
    //usar currenthashmap pq pode ser lida ou escrita lockar nada
    private final Map<String, Order> ordersById = new ConcurrentHashMap<>();
    private final Map<String, Order> ordersByClientKey = new ConcurrentHashMap<>();

    //
    public OrderResult submit(OrderRequest request) {
        //só para saber se o validation retorna REJECT, se sim, entra nesse if, se não, continua normal
        ValidationResult validation = validator.validate(request);
        if (!validation.valid()) {
            log.info("ORDER_REJECTED userId={} reason={}", request == null ? null : request.userId(), validation.reason());
            return OrderResult.rejected(request, validation.reason());
        }

        //só pra "linkar" quem pediu a o que pediu
        String key = request.userId() + "|" + request.clientOrderId();


        //só pra garantir que o primeiro que for gerado, entre e lock, para deixar a fila certinha
        synchronized (enqueueLock) {
            //só para nao deixar o usuario dar ordem 2 vezes seguidas, assim evita a duplicate
            Order existing = ordersByClientKey.get(key);
            if (existing != null) {
                log.info("ORDER_DUPLICATE orderId={} userId={}", existing.orderId(), existing.userId());
                return OrderResult.of(existing);
            }

            //gera o número da fila para cada acao
            Order order = new Order(request, ++lastSequence);
            ordersByClientKey.put(key, order);
            ordersById.put(order.orderId(), order);
            log.info("ORDER_RECEIVED orderId={} userId={} seq={}", order.orderId(), order.userId(), order.sequenceNumber());

            //se entrar no if, quer dizer que a fila ta cheia ai rejeita a ordem
            if (!processor.enqueue(order)) {
                order.reject(RejectReason.SYSTEM_BUSY);
                ordersByClientKey.remove(key);
                log.warn("ORDER_REJECTED orderId={} reason={}", order.orderId(), RejectReason.SYSTEM_BUSY);
            }
            //se não entrar, para aqui
            return OrderResult.of(order);
        }
    }

    //so busca mesmo
    public Optional<Order> findById(String orderId) {
        return Optional.ofNullable(ordersById.get(orderId));
    }

    //vai devolver a ordem só se pertencer ao user
    public Optional<Order> findByIdForUser(String orderId, String userId) {
        if (orderId == null) {//obrigatorio, se não o ConcurrentHashMap.get(null) lançaria NullPointerException
            return Optional.empty();
        }
        return findById(orderId).filter(order -> order.userId().equals(userId));
    }

    //busca também, mas ordena da ordem mais antiga pra mais atual de cada user buscado
    public List<Order> findByUser(String userId) {
        return ordersById.values().stream()
                .filter(order -> order.userId().equals(userId))
                .sorted((a, b) -> Long.compare(a.sequenceNumber(), b.sequenceNumber()))
                .toList();
    }
}
