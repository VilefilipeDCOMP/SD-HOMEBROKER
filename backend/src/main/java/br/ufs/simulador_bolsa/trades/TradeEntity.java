package br.ufs.simulador_bolsa.trades;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import br.ufs.simulador_bolsa.market.AssetEntity;
import br.ufs.simulador_bolsa.orders.OrderEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "historico_negociacoes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ordem_compra_id", nullable = false)
    private OrderEntity ordemCompra;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ordem_venda_id", nullable = false)
    private OrderEntity ordemVenda;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ativo_id", nullable = false)
    private AssetEntity ativo;

    @Column(name = "quantidade_executada", nullable = false)
    private Integer quantidadeExecutada;

    @Column(name = "preco_executado", nullable = false, precision = 15, scale = 2)
    private BigDecimal precoExecutado;

    @Column(name = "executado_em", nullable = false, updatable = false)
    private LocalDateTime executadoEm;

    @PrePersist
    public void prePersist() {
        if (this.executadoEm == null) {
            this.executadoEm = LocalDateTime.now();
        }
    }
}
