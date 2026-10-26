package com.deployhub.platform.deployment;

import com.deployhub.platform.support.OracleTestcontainersConfig;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** Every LogStreamIntegrationTest case on real Oracle. */
@Tag("oracle")
@Import(OracleTestcontainersConfig.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class LogStreamOracleTest extends LogStreamIntegrationTest {
}
