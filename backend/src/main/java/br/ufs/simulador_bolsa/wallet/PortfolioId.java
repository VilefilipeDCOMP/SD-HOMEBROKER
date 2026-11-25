package br.ufs.simulador_bolsa.wallet;

import java.io.Serializable;
import java.util.Objects;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioId implements Serializable {

    @Column(name = "usuario_id")
    private Long usuarioId;

    @Column(name = "ativo_id")
    private Long ativoId;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PortfolioId that = (PortfolioId) o;
        return Objects.equals(usuarioId, that.usuarioId) && Objects.equals(ativoId, that.ativoId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(usuarioId, ativoId);
    }
}
