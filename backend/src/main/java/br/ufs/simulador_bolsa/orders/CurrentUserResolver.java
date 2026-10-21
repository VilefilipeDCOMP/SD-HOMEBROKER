package br.ufs.simulador_bolsa.orders;

import java.security.Principal;

import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;

//só pra pegar o id da sessão atual que é fica sendo o user por enquanto
// com a autentificação de bruno, pega o nome realmente do user
@Component
public class CurrentUserResolver {

    public String resolve(SimpMessageHeaderAccessor headers) {
        Principal principal = headers.getUser();
        if (principal != null) {
            return principal.getName();
        }
        return headers.getSessionId();
    }
}
