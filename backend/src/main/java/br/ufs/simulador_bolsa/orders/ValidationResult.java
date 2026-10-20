package br.ufs.simulador_bolsa.orders;

//é o resultado de uma validação, no ok, ele só aceita mesmo
//no reject ele rejeita, obviamente, e fala o motivo
public record ValidationResult(boolean valid, RejectReason reason) {

    public static ValidationResult ok() {
        return new ValidationResult(true, null);
    }

    public static ValidationResult rejected(RejectReason reason) {
        return new ValidationResult(false, reason);
    }
}
