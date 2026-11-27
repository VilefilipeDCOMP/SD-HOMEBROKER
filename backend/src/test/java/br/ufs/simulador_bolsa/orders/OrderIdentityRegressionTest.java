package br.ufs.simulador_bolsa.orders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import br.ufs.simulador_bolsa.auth.entity.User;
import br.ufs.simulador_bolsa.auth.repository.UserRepository;
import br.ufs.simulador_bolsa.market.AssetCatalog;
import br.ufs.simulador_bolsa.market.AssetEntity;
import br.ufs.simulador_bolsa.market.AssetRepository;

/** Regressão: username numérico não pode ser tratado como ID do banco. */
class OrderIdentityRegressionTest {

    private static User user(long id, String username) {
        return User.builder().id(id).username(username)
                .saldo(new BigDecimal("1000.00")).build();
    }

    @Test
    void resolveUserPrefersUsernameEvenWhenItLooksNumeric() {
        UserRepository users = mock(UserRepository.class);
        User numericNamed = user(7L, "42");
        User id42 = user(42L, "outro");
        when(users.findByUsername("42")).thenReturn(Optional.of(numericNamed));
        when(users.findById(42L)).thenReturn(Optional.of(id42));

        OrderPersistenceService service = new OrderPersistenceService(
                mock(OrderRepository.class), users, mock(AssetRepository.class), new AssetCatalog());

        assertSame(numericNamed, service.resolveUser("42").orElseThrow());
        verify(users, never()).findById(anyLong());
    }

    @Test
    void persistedOrderUsesTheAuthenticatedUsernameOwner() {
        OrderRepository orders = mock(OrderRepository.class);
        UserRepository users = mock(UserRepository.class);
        AssetRepository assets = mock(AssetRepository.class);
        User numericNamed = user(7L, "42");
        when(users.findByUsername("42")).thenReturn(Optional.of(numericNamed));
        when(users.findById(42L)).thenReturn(Optional.of(user(42L, "outro")));
        AssetEntity petr4 = AssetEntity.builder().id(3L).ticker("PETR4")
                .nomeEmpresa("Petrobras").precoAtual(new BigDecimal("35.00")).build();
        when(assets.findByTicker("PETR4")).thenReturn(Optional.of(petr4));
        when(orders.save(any())).thenAnswer(inv -> {
            OrderEntity saved = inv.getArgument(0);
            saved.setId(99L);
            return saved;
        });

        OrderPersistenceService service = new OrderPersistenceService(
                orders, users, assets, new AssetCatalog());
        Order order = new Order(new OrderRequest("42", "cid-1", "PETR4",
                OrderSide.BUY, 1L, new BigDecimal("35.00")), 1L);

        assertEquals(99L, service.persistOpenOrder(order));

        ArgumentCaptor<OrderEntity> captor = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orders).save(captor.capture());
        assertSame(numericNamed, captor.getValue().getUsuario());
        verify(users, never()).findById(anyLong());
    }
}
