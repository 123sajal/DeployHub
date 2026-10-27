package com.deployhub.platform.billing;

import com.deployhub.platform.support.OracleTestcontainersConfig;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** Every BillingIntegrationTest case on real Oracle, with Hibernate validating the V10 schema. */
@Tag("oracle")
@Import(OracleTestcontainersConfig.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class BillingOracleTest extends BillingIntegrationTest {
}
