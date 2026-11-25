package br.ufs.simulador_bolsa.wallet;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PortfolioRepository extends JpaRepository<PortfolioEntity, PortfolioId> {

    @Query("SELECT p FROM PortfolioEntity p JOIN FETCH p.ativo WHERE p.usuario.id = :usuarioId")
    List<PortfolioEntity> findByUsuarioIdWithAsset(@Param("usuarioId") Long usuarioId);

    @Query("SELECT p FROM PortfolioEntity p JOIN FETCH p.ativo WHERE p.usuario.id = :usuarioId AND p.ativo.ticker = :ticker")
    Optional<PortfolioEntity> findByUsuarioIdAndTicker(@Param("usuarioId") Long usuarioId, @Param("ticker") String ticker);
}
