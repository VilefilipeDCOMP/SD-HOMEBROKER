package br.ufs.simulador_bolsa.market;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AssetRepository extends JpaRepository<AssetEntity, Long> {
    Optional<AssetEntity> findByTicker(String ticker);
}
