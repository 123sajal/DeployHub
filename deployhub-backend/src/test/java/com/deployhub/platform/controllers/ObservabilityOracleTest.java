package com.deployhub.platform.controllers;

import com.deployhub.platform.support.OracleTestcontainersConfig;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** Probes (incl. the real Oracle "db" check in readiness) and the metrics queries on real Oracle. */
@Tag("oracle")
@Import(OracleTestcontainersConfig.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class ObservabilityOracleTest extends ObservabilityIntegrationTest {
}
