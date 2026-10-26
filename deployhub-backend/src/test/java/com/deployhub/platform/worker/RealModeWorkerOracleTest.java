package com.deployhub.platform.worker;

import com.deployhub.platform.support.OracleTestcontainersConfig;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** The REAL-mode worker cases (V5 columns, image/container queries, the per-project lock) on real Oracle. */
@Tag("oracle")
@Import(OracleTestcontainersConfig.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class RealModeWorkerOracleTest extends RealModeWorkerIntegrationTest {
}
