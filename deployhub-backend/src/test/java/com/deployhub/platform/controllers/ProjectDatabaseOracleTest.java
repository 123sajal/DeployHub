package com.deployhub.platform.controllers;

import com.deployhub.platform.support.OracleTestcontainersConfig;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** One-click database bookkeeping on real Oracle (V7 tables, unique constraints, cascades). */
@Tag("oracle")
@Import(OracleTestcontainersConfig.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class ProjectDatabaseOracleTest extends ProjectDatabaseIntegrationTest {
}
