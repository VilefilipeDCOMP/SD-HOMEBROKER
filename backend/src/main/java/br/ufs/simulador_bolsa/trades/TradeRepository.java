package br.ufs.simulador_bolsa.trades;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TradeRepository extends JpaRepository<TradeEntity, Long> {

    interface AssetVolumeProjection {
        String getTicker();
        Long getVolume();
    }

    @Query("SELECT t FROM TradeEntity t " +
           "JOIN FETCH t.ativo " +
           "JOIN FETCH t.ordemCompra oc " +
           "JOIN FETCH t.ordemVenda ov " +
           "WHERE oc.usuario.id = :usuarioId OR ov.usuario.id = :usuarioId " +
           "ORDER BY t.executadoEm DESC")
    List<TradeEntity> findByUsuarioId(@Param("usuarioId") Long usuarioId);

    @Query("SELECT SUM(t.quantidadeExecutada) FROM TradeEntity t WHERE UPPER(t.ativo.ticker) = UPPER(:ticker)")
    Long sumVolumeByTicker(@Param("ticker") String ticker);

    @Query("SELECT t.ativo.ticker AS ticker, SUM(t.quantidadeExecutada) AS volume " +
           "FROM TradeEntity t GROUP BY t.ativo.ticker")
    List<AssetVolumeProjection> sumVolumeGroupedByTicker();
}
