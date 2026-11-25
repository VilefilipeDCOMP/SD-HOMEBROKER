package br.ufs.simulador_bolsa.orders;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    @Query("SELECT o FROM OrderEntity o JOIN FETCH o.ativo JOIN FETCH o.usuario WHERE o.status = :status ORDER BY o.criadoEm ASC")
    List<OrderEntity> findByStatusWithAssetAndUser(@Param("status") String status);

    @Query("SELECT o FROM OrderEntity o JOIN FETCH o.ativo WHERE o.usuario.id = :usuarioId ORDER BY o.criadoEm DESC")
    List<OrderEntity> findByUsuarioIdOrderByCriadoEmDesc(@Param("usuarioId") Long usuarioId);
}
