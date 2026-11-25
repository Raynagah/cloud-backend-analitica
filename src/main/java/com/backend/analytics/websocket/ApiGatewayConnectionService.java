package com.backend.analytics.websocket;

import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.backend.analytics.service.AdminAuthorizationService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@ConditionalOnProperty(
        name = "analytics.websocket.api-gateway.enabled",
        havingValue = "true"
)
public class ApiGatewayConnectionService {

    private static final class Conexion {

        private final Instant expiracion;
        private final String token;

        private Conexion(Instant expiracion, String token) {
            this.expiracion = expiracion;
            this.token = token;
        }
    }

    private final Map<String, Conexion> conexiones =
            new ConcurrentHashMap<>();

    private final AdminAuthorizationService adminAuth;

    public ApiGatewayConnectionService(
            AdminAuthorizationService adminAuth) {

        this.adminAuth = adminAuth;
    }

    public void registrar(
            String connectionId,
            Instant expiracion,
            String token) {

        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException(
                    "El identificador de conexión es obligatorio."
            );
        }

        if (expiracion == null ||
                !expiracion.isAfter(Instant.now())) {

            throw new IllegalArgumentException(
                    "La sesión ha expirado."
            );
        }

        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(
                    "El token es obligatorio."
            );
        }

        conexiones.put(
                connectionId,
                new Conexion(expiracion, token)
        );

        log.info(
                "Dashboard AWS conectado. Conexiones registradas: {}",
                conexiones.size()
        );
    }

    public void eliminar(String connectionId) {

        if (connectionId != null &&
                conexiones.remove(connectionId) != null) {

            log.info(
                    "Dashboard AWS desconectado. Conexiones registradas: {}",
                    conexiones.size()
            );
        }
    }

    public Map<String, Instant> listar() {

        Map<String, Instant> resultado = new HashMap<>();

        conexiones.forEach((id, conexion) ->
                resultado.put(id, conexion.expiracion)
        );

        return Map.copyOf(resultado);
    }

    public boolean esVigente(String connectionId) {

        Conexion conexion = conexiones.get(connectionId);

        return conexion != null &&
                conexion.expiracion.isAfter(Instant.now());
    }

    public boolean esAutorizada(String connectionId) {

        Conexion conexion = conexiones.get(connectionId);

        if (conexion == null ||
                !conexion.expiracion.isAfter(Instant.now())) {

            return false;
        }

        if (!adminAuth.esAdmin(conexion.token)) {
            return false;
        }

        return conexiones.get(connectionId) == conexion &&
                conexion.expiracion.isAfter(Instant.now());
    }

    public int cantidad() {
        return conexiones.size();
    }

    public boolean estaVacio() {
        return conexiones.isEmpty();
    }
}