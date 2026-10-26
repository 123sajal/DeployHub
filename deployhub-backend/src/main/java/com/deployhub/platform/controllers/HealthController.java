package com.deployhub.platform.controllers;

import com.deployhub.platform.models.DTO.HealthResponseDTO;
import com.deployhub.platform.config.DeployHubProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;

@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final DataSource dataSource;
    private final Environment environment;
    private final DeployHubProperties properties;
    private final Clock clock;

    /** Returns 503 when the database is unreachable, so a load balancer stops routing here. */
    @GetMapping
    public ResponseEntity<HealthResponseDTO> health() {
        String database = "DOWN";
        String product = null;
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(2)) {
                database = "UP";
                product = connection.getMetaData().getDatabaseProductName();
            }
        } catch (Exception ignored) {
            // reported as DOWN below
        }
        boolean up = "UP".equals(database);
        HealthResponseDTO body = new HealthResponseDTO(up ? "UP" : "DOWN", database, product,
                List.of(environment.getActiveProfiles()), properties.execution().mode(), clock.instant());
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
