package br.ufs.simulador_bolsa.orders;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.ufs.simulador_bolsa.auth.entity.User;
import br.ufs.simulador_bolsa.auth.repository.UserRepository;
import br.ufs.simulador_bolsa.market.Asset;
import br.ufs.simulador_bolsa.market.AssetCatalog;
import br.ufs.simulador_bolsa.market.AssetEntity;
import br.ufs.simulador_bolsa.market.AssetRepository;
import br.ufs.simulador_bolsa.wallet.PortfolioEntity;
import br.ufs.simulador_bolsa.wallet.PortfolioId;
import br.ufs.simulador_bolsa.wallet.PortfolioRepository;
import lombok.RequiredArgsConstructor;

/** Seed do vendedor usado somente no perfil demo. */
@Service
@Profile("demo")
@RequiredArgsConstructor
public class DemoSellerSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoSellerSeeder.class);

    private static final String SELLER_USERNAME = "vendedor_demo";
    private static final int INITIAL_SHARES = 100;

    private final UserRepository userRepository;
    private final AssetRepository assetRepository;
    private final AssetCatalog assetCatalog;
    private final PortfolioRepository portfolioRepository;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Transactional
    public void seedDemoSellerIfNeeded() {
        User seller = userRepository.findByUsername(SELLER_USERNAME).orElseGet(() -> {
            User created = User.builder()
                    .username(SELLER_USERNAME)
                    .nome("Vendedor Demo")
                    .email("vendedor_demo@simulador.local")
                    .senhaHash(encoder.encode("senha123"))
                    .saldo(new BigDecimal("10000.00"))
                    .build();
            return userRepository.save(created);
        });

        for (Asset asset : assetCatalog.listAll()) {
            AssetEntity assetEntity = assetRepository.findByTicker(asset.symbol()).orElseGet(() ->
                    assetRepository.save(AssetEntity.builder()
                            .ticker(asset.symbol())
                            .nomeEmpresa(asset.name())
                            .precoAtual(asset.currentPrice())
                            .build()));

            PortfolioId id = new PortfolioId(seller.getId(), assetEntity.getId());
            if (!portfolioRepository.existsById(id)) {
                portfolioRepository.save(PortfolioEntity.builder()
                        .id(id)
                        .usuario(seller)
                        .ativo(assetEntity)
                        .quantidade(INITIAL_SHARES)
                        .precoMedio(asset.currentPrice())
                        .build());
                log.info("SEEDED_DEMO_SELLER_SHARES user={} asset={} qty={}",
                        SELLER_USERNAME, asset.symbol(), INITIAL_SHARES);
            }
        }
    }
}
