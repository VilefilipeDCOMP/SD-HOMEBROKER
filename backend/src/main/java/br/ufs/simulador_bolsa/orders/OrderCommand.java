package br.ufs.simulador_bolsa.orders;

import java.util.concurrent.CompletableFuture;

public sealed interface OrderCommand {

    record Submit(Order order) implements OrderCommand {}

    record Cancel(Order order, CompletableFuture<Boolean> result) implements OrderCommand {}
}