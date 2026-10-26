package com.deployhub.platform.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.oracle.OracleContainer;

/** Points the Spring context's DataSource at the shared Oracle container instead of H2. */
@TestConfiguration(proxyBeanMethods = false)
public class OracleTestcontainersConfig {

    @Bean
    @ServiceConnection
    OracleContainer oracleContainer() {
        return OracleTestContainer.get();
    }
}
