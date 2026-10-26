package com.deployhub.platform.controllers;

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
        HealthController controller = new HealthController(dataSource, new MockEnvironment(), Clock.systemUTC());

        ResponseEntity<HealthResponseDTO> response = controller.health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().database()).isEqualTo("DOWN");
        assertThat(response.getBody().status()).isEqualTo("DOWN");
    }
}
