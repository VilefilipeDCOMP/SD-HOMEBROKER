package br.ufs.simulador_bolsa.config;

import java.util.Map;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import br.ufs.simulador_bolsa.communication.MarketEventPublisher;

@Configuration
@Profile("demo")
@EnableScheduling
public class MarketDemoConfig {
    private final MarketEventPublisher publisher;

    public MarketDemoConfig(MarketEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(initialDelay = 5000, fixedDelay = 5000)
    public void publishDemoEvent() {
        publisher.publish(
                "MARKET_DEMO",
                Map.of("symbol", "TEST3", "price", 10.0)
        );
    }



}