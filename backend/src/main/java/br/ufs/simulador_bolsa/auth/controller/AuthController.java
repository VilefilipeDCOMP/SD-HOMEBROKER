package br.ufs.simulador_bolsa.auth.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.ufs.simulador_bolsa.auth.dto.AuthRequest;
import br.ufs.simulador_bolsa.auth.dto.AuthResponse;
import br.ufs.simulador_bolsa.auth.dto.ErrorResponse;
import br.ufs.simulador_bolsa.auth.service.AuthService;


@RequiredArgsConstructor
@RestController
@RequestMapping("/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody AuthRequest request) {
        AuthResponse response = authService.register(request.username(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request) {
        AuthResponse response = authService.login(request.username(), request.password());
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        String message = ex.getMessage();
        HttpStatus status = HttpStatus.BAD_REQUEST;
        String code = "BAD_REQUEST";

        if ("Usuário ou senha inválidos".equals(message)) {
            status = HttpStatus.UNAUTHORIZED;
            code = "INVALID_CREDENTIALS";
        } else if ("Username já está em uso".equals(message)) {
            status = HttpStatus.CONFLICT;
            code = "USER_ALREADY_EXISTS";
        }

        ErrorResponse error = new ErrorResponse("AUTH_ERROR", code, message);
        return ResponseEntity.status(status).body(error);
    }
}
