package com.backend.analytics.websocket;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.backend.analytics.service.MetricasService;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.DeleteConnectionRequest;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.PostToConnectionRequest;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.GoneException;

@Slf4j
@Component
@ConditionalOnProperty(name = "analytics.websocket.api-gateway.enabled", havingValue = "true")
public class ApiGatewayMetricasPublisher {

    private final ApiGatewayManagementApiClient gateway;
    private final ApiGatewayConnectionService conexiones;
    private final MetricasService metricas;

    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, Instant> conexionesGone = new HashMap<>();

    public ApiGatewayMetricasPublisher(
            ApiGatewayManagementApiClient gateway,
            ApiGatewayConnectionService conexiones,
            MetricasService metricas) {

        this.gateway = gateway;
        this.conexiones = conexiones;
        this.metricas = metricas;
    }

    /**
     * Publica las métricas periódicamente a los dashboards
     * conectados mediante API Gateway WebSocket.
     */
    @Scheduled(fixedDelayString = "${analytics.websocket.interval-ms:2000}")
    public void publicarMetricas() {

        Map<String, Instant> activas = conexiones.listar();

        conexionesGone.keySet().retainAll(activas.keySet());

        if (activas.isEmpty()) {
            return;
        }

        SdkBytes mensaje;

        try {
            mensaje = SdkBytes.fromUtf8String(
                    mapper.writeValueAsString(metricas.snapshot()));
        } catch (Exception e) {

            log.error("No se pudieron serializar las métricas.");

            return;
        }

        for (String connectionId : activas.keySet()) {

            if (!conexiones.esAutorizada(connectionId)) {

                cerrarConexion(connectionId);

                continue;
            }

            enviarMetricas(connectionId, mensaje);
        }
    }

    /**
     * Envía el snapshot de métricas a una conexión.
     */
    private void enviarMetricas(
            String connectionId,
            SdkBytes mensaje) {

        try {

            gateway.postToConnection(
                    PostToConnectionRequest.builder()
                            .connectionId(connectionId)
                            .data(mensaje)
                            .build());

            conexionesGone.remove(connectionId);

        } catch (GoneException e) {

            // API Gateway puede devolver 410 mientras
            // el handshake todavía está finalizando.

            Instant primerFallo = conexionesGone.computeIfAbsent(
                    connectionId,
                    id -> Instant.now());

            if (primerFallo.plusSeconds(30)
                    .isBefore(Instant.now())) {

                conexiones.eliminar(connectionId);
                conexionesGone.remove(connectionId);

                log.info("Conexión WebSocket descartada.");
            }

        } catch (SdkException e) {

            log.warn(
                    "Error enviando métricas a API Gateway: {}",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * Elimina y cierra una conexión cuyo JWT ha expirado.
     */
    private void cerrarConexion(String connectionId) {

        conexiones.eliminar(connectionId);
        conexionesGone.remove(connectionId);

        try {

            gateway.deleteConnection(
                    DeleteConnectionRequest.builder()
                            .connectionId(connectionId)
                            .build());

            log.info("Conexión WebSocket cerrada por pérdida de autorización.");

        } catch (GoneException e) {

            // El cliente ya se encuentra desconectado.

        } catch (SdkException e) {

            log.warn(
                    "No se pudo cerrar la conexión en AWS: {}",
                    e.getClass().getSimpleName());
        }
    }
}