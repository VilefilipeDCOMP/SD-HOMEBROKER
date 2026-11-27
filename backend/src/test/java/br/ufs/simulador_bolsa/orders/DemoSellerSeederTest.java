package br.ufs.simulador_bolsa.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Profile;
import org.springframework.test.util.ReflectionTestUtils;

import br.ufs.simulador_bolsa.auth.entity.User;
import br.ufs.simulador_bolsa.auth.repository.UserRepository;
import br.ufs.simulador_bolsa.market.AssetCatalog;
import br.ufs.simulador_bolsa.market.AssetEntity;
import br.ufs.simulador_bolsa.market.AssetRepository;
import br.ufs.simulador_bolsa.wallet.PortfolioEntity;
import br.ufs.simulador_bolsa.wallet.PortfolioRepository;

class DemoSellerSeederTest {

    @Test
    void seederCreatesDemoUserWithEncodedPasswordAndGrantsShares() {
        UserRepository users = mock(UserRepository.class);
        AssetRepository assets = mock(AssetRepository.class);
        PortfolioRepository portfolios = mock(PortfolioRepository.class);
        AssetCatalog catalog = new AssetCatalog();

        when(users.findByUsername("vendedor_demo")).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(invocation -> {
            User created = invocation.getArgument(0);
            created.setId(9L);
            return created;
        });
        when(assets.findByTicker(anyString())).thenAnswer(invocation -> Optional.of(AssetEntity.builder()
                .id(1L)
                .ticker(invocation.getArgument(0))
                .build()));

        DemoSellerSeeder seeder = new DemoSellerSeeder(users, assets, catalog, portfolios);
        seeder.seedDemoSellerIfNeeded();

        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        verify(users).save(user.capture());
        assertEquals("vendedor_demo", user.getValue().getUsername());
        assertNotEquals("senha123", user.getValue().getSenhaHash());

        ArgumentCaptor<PortfolioEntity> shares = ArgumentCaptor.forClass(PortfolioEntity.class);
        verify(portfolios, org.mockito.Mockito.times(catalog.listAll().size())).save(shares.capture());
        assertEquals(100, shares.getAllValues().getFirst().getQuantidade());
    }

    @Test
    void seederBeanIsRestrictedToDemoProfile() {
        Profile profile = DemoSellerSeeder.class.getAnnotation(Profile.class);
        assertTrue(profile != null && Arrays.asList(profile.value()).contains("demo"));
    }

    @Test
    void defaultSeedingNeverCreatesTheDemoSellerByItself() {
        UserRepository users = mock(UserRepository.class);
        OrderPersistenceService service = new OrderPersistenceService(
                mock(OrderRepository.class), users, mock(AssetRepository.class), new AssetCatalog());

        service.seedDefaultAssetsIfNeeded();
        verify(users, never()).save(any(User.class));

        DemoSellerSeeder seeder = mock(DemoSellerSeeder.class);
        ReflectionTestUtils.setField(service, "demoSellerSeeder", seeder);

        service.seedDefaultAssetsIfNeeded();
        verify(seeder).seedDemoSellerIfNeeded();
    }
}
