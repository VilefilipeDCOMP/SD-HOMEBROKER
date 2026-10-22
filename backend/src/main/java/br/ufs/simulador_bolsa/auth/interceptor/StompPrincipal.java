package br.ufs.simulador_bolsa.auth.interceptor;

import java.security.Principal;

import br.ufs.simulador_bolsa.auth.entity.User;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class StompPrincipal implements Principal {

    private final User user;

    @Override
    public String getName() {
        return user.getUsername();
    }

    public User getUser() {
        return user;
    }
}
