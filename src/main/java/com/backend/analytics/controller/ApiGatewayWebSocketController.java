package com.backend.analytics.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.*;

import com.backend.analytics.service.AdminAuthorizationService;
import com.backend.analytics.websocket.ApiGatewayConnectionService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/ws/gateway")
@ConditionalOnProperty(
        name = "analytics.websocket.api-gateway.enabled",
        havingValue = "true"
)
public class ApiGatewayWebSocketController {

    private final JwtDecoder jwtDecoder;
    private final AdminAuthorizationService adminAuth;
    private final ApiGatewayConnectionService conexiones;
    private final String gatewaySecret;
    private final String tenantId;

    private static final String CLIENT_ID =
            "5afd63dd-9376-4b61-a4f7-688b2cb81fd6";

    public ApiGatewayWebSocketController(
            JwtDecoder jwtDecoder,
            AdminAuthorizationService adminAuth,
            ApiGatewayConnectionService conexiones,
            @Value("${analytics.websocket.api-gateway.secret:}")
            String gatewaySecret,
            @Value("${spring.security.oauth2.resourceserver.jwt.tenant-id}")
            String tenantId) {

        if (gatewaySecret == null || gatewaySecret.isBlank()) {
            throw new IllegalStateException(
                    "La clave interna de API Gateway es obligatoria."
            );
        }

        this.jwtDecoder = jwtDecoder;
        this.adminAuth = adminAuth;
        this.conexiones = conexiones;
        this.gatewaySecret = gatewaySecret;
        this.tenantId = tenantId;
    }

    // ==========================================
    // CONEXIÓN: $connect
    // ==========================================

    @PostMapping("/connect")
    public ResponseEntity<?> conectar(
            @RequestHeader(
                    value = "X-Gateway-Secret",
                    required = false
            ) String secret,
            @RequestHeader(
                    value = "X-Connection-Id",
                    required = false
            ) String connectionId,
            @RequestHeader(
                    value = "X-WebSocket-Token",
                    required = false
            ) String token) {

        if (!esIntegracionValida(secret)) {
            return error(
                    HttpStatus.FORBIDDEN,
                    "Integración no autorizada."
            );
        }

        if (connectionId == null || connectionId.isBlank()) {
            return error(
                    HttpStatus.BAD_REQUEST,
                    "Connection ID obligatorio."
            );
        }

        if (token == null || token.isBlank()) {
            return error(
                    HttpStatus.UNAUTHORIZED,
                    "Token obligatorio."
            );
        }

        Jwt jwt;

        try {
            jwt = jwtDecoder.decode(token);
        } catch (JwtException e) {
            log.warn("Conexión WebSocket rechazada: JWT inválido.");

            return error(
                    HttpStatus.UNAUTHORIZED,
                    "Token inválido o expirado."
            );
        }

        if (!esJwtDeLaAplicacion(jwt)) {
            return error(
                    HttpStatus.UNAUTHORIZED,
                    "Token no válido para esta aplicación."
            );
        }

        Instant expiracion = jwt.getExpiresAt();

        if (expiracion == null ||
                !expiracion.isAfter(Instant.now())) {

            return error(
                    HttpStatus.UNAUTHORIZED,
                    "El token ha expirado."
            );
        }

        if (!adminAuth.esAdmin(token)) {
            return error(
                    HttpStatus.FORBIDDEN,
                    "Se requiere rol administrador."
            );
        }

        if (!expiracion.isAfter(Instant.now())) {
            return error(
                    HttpStatus.UNAUTHORIZED,
                    "El token ha expirado."
            );
        }

        conexiones.registrar(connectionId, expiracion, token);

        return ResponseEntity.ok(Map.of(
                "mensaje", "Conexión autorizada."
        ));
    }

    // ==========================================
    // DESCONEXIÓN: $disconnect
    // ==========================================

    @PostMapping("/disconnect")
    public ResponseEntity<?> desconectar(
            @RequestHeader(
                    value = "X-Gateway-Secret",
                    required = false
            ) String secret,
            @RequestHeader(
                    value = "X-Connection-Id",
                    required = false
            ) String connectionId) {

        if (!esIntegracionValida(secret)) {
            return error(
                    HttpStatus.FORBIDDEN,
                    "Integración no autorizada."
            );
        }

        if (connectionId == null || connectionId.isBlank()) {
            return error(
                    HttpStatus.BAD_REQUEST,
                    "Connection ID obligatorio."
            );
        }

        conexiones.eliminar(connectionId);

        return ResponseEntity.ok(Map.of(
                "mensaje", "Conexión eliminada."
        ));
    }

    // ==========================================
    // VALIDACIONES INTERNAS
    // ==========================================

    private boolean esIntegracionValida(String secret) {

        if (secret == null || secret.isBlank()) {
            return false;
        }

        return MessageDigest.isEqual(
                gatewaySecret.getBytes(StandardCharsets.UTF_8),
                secret.getBytes(StandardCharsets.UTF_8)
        );
    }

    private boolean esJwtDeLaAplicacion(Jwt jwt) {

        String issuer = jwt.getClaimAsString("iss");

        boolean issuerValido =
                ("https://login.microsoftonline.com/"
                        + tenantId + "/v2.0").equals(issuer)
                ||
                ("https://sts.windows.net/"
                        + tenantId + "/").equals(issuer);

        List<String> audiences = jwt.getAudience();

        boolean audienceValida = audiences != null &&
                (audiences.contains(CLIENT_ID) ||
                 audiences.contains("api://" + CLIENT_ID));

        String scopes = jwt.getClaimAsString("scp");

        boolean scopeValido = scopes != null &&
                List.of(scopes.split("\\s+"))
                        .contains("read-write");

        return issuerValido && audienceValida && scopeValido;
    }

    private ResponseEntity<?> error(
            HttpStatus status,
            String mensaje) {

        return ResponseEntity.status(status).body(Map.of(
                "error", status.value(),
                "mensaje", mensaje
        ));
    }
}