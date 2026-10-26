package com.deployhub.platform.controllers;

import com.deployhub.platform.config.DeployHubProperties;
import com.deployhub.platform.models.enums.ExecutionMode;
import com.deployhub.platform.models.DTO.HealthResponseDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    @Test
    void reportsDownWith503WhenDatabaseIsUnreachable() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));
        DeployHubProperties properties = new DeployHubProperties(null, null, null, null, null,
                new DeployHubProperties.Execution(ExecutionMode.REAL, "docker", "git", "/tmp", null, null, null,
                        20000, 20999, "512m", "1.0", 256, 1000, 3), null, null, null);
        HealthController controller = new HealthController(dataSource, new MockEnvironment(), properties,
                Clock.systemUTC());

        ResponseEntity<HealthResponseDTO> response = controller.health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().database()).isEqualTo("DOWN");
        assertThat(response.getBody().status()).isEqualTo("DOWN");
    }
}
