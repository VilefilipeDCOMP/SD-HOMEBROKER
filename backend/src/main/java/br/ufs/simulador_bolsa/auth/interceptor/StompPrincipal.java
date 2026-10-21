package br.ufs.simulador_bolsa.auth.interceptor;

import java.security.Principal;

import br.ufs.simulador_bolsa.auth.entity.User;

public class StompPrincipal implements Principal {

    private final User user;

    public StompPrincipal(User user) {
        this.user = user;
    }

    @Override
    public String getName() {
        return user.getUsername();
    }

    public User getUser() {
        return user;
    }
}
