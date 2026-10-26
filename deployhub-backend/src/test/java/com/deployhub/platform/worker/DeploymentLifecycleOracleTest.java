package com.deployhub.platform.worker;

import com.deployhub.platform.support.OracleTestcontainersConfig;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** Runs every DeploymentLifecycleIntegrationTest case (incl. the optimistic-locking race) against real Oracle. */
@Tag("oracle")
@Import(OracleTestcontainersConfig.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class DeploymentLifecycleOracleTest extends DeploymentLifecycleIntegrationTest {
}
