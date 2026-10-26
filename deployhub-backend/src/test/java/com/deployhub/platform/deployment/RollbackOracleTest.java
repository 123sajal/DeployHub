package com.deployhub.platform.deployment;

import com.deployhub.platform.support.OracleTestcontainersConfig;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** Every RollbackIntegrationTest case on real Oracle (incl. deleting a project whose deployments reference each other). */
@Tag("oracle")
@Import(OracleTestcontainersConfig.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class RollbackOracleTest extends RollbackIntegrationTest {
}
