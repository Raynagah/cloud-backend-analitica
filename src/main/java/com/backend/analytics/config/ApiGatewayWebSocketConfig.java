package com.backend.analytics.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;

@Configuration
@ConditionalOnProperty(name = "analytics.websocket.api-gateway.enabled", havingValue = "true")
public class ApiGatewayWebSocketConfig {

    @Bean
    public ApiGatewayManagementApiClient apiGatewayManagementApiClient(
            @Value("${analytics.websocket.api-gateway.endpoint}") String endpoint,
            @Value("${analytics.websocket.api-gateway.region}") String region) {

        return ApiGatewayManagementApiClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(config -> config
                        .apiCallAttemptTimeout(Duration.ofSeconds(3))
                        .apiCallTimeout(Duration.ofSeconds(5)))
                .build();
    }
}