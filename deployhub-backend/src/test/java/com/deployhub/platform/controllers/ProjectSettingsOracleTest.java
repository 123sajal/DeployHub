package com.deployhub.platform.controllers;

import com.deployhub.platform.support.OracleTestcontainersConfig;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** Environment variables and routes on real Oracle (V6 tables, cascades, unique constraints). */
@Tag("oracle")
@Import(OracleTestcontainersConfig.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class ProjectSettingsOracleTest extends ProjectSettingsIntegrationTest {
}
