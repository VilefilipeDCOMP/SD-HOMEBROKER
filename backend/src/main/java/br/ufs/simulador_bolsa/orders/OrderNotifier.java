package br.ufs.simulador_bolsa.orders;


//notifica o dono da ação quando o processamento termina(ACCEPTED ou REJECTED).
@FunctionalInterface
public interface OrderNotifier {
    void orderUpdated(Order order);
}
