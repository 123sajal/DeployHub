package com.deployhub.platform.deployment;

import com.deployhub.platform.support.OracleTestcontainersConfig;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** Every DeploymentDeleteIntegrationTest case on real Oracle (cascades and ON DELETE SET NULL included). */
@Tag("oracle")
@Import(OracleTestcontainersConfig.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class DeploymentDeleteOracleTest extends DeploymentDeleteIntegrationTest {
}
