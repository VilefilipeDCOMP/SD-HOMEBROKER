package br.ufs.simulador_bolsa.orders;

import java.security.Principal;

import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;

//pega o nome do usuario já autenticado
@Component
public class CurrentUserResolver {

    public String resolve(SimpMessageHeaderAccessor headers) {
        Principal principal = headers.getUser();
        if (principal == null) {
            throw new IllegalStateException("Usuário nao autenticado");
        }
        return principal.getName();
    }
}
