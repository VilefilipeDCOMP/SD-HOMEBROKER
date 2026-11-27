package br.ufs.simulador_bolsa.orders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import br.ufs.simulador_bolsa.auth.entity.User;
import br.ufs.simulador_bolsa.market.AssetEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "ordens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private User usuario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ativo_id", nullable = false)
    private AssetEntity ativo;

    @Column(nullable = false, length = 10)
    private String tipo; // 'COMPRA' ou 'VENDA'

    @Column(nullable = false)
    private Integer quantidade;

    @Column(name = "preco_alvo", nullable = false, precision = 15, scale = 2)
    private BigDecimal precoAlvo;

    @Column(nullable = false, length = 20)
    private String status; // 'ABERTA', 'EXECUTADA', 'CANCELADA'

    @Builder.Default
    @Column(name = "quantidade_executada", nullable = false)
    private Integer quantidadeExecutada = 0;

    @Column(name = "client_order_id", nullable = false, length = 64)
    private String clientOrderId;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private LocalDateTime criadoEm;

    @PrePersist
    public void prePersist() {
        if (this.criadoEm == null) {
            this.criadoEm = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = "ABERTA";
        }
        if (this.quantidadeExecutada == null) {
            this.quantidadeExecutada = 0;
        }
        if (this.clientOrderId == null) {
            this.clientOrderId = "__legacy__" + java.util.UUID.randomUUID();
        }
        if (this.orderId == null) {
            this.orderId = "__legacy-db__" + java.util.UUID.randomUUID();
        }
    }
}
