package com.deployhub.platform.persistence;

import com.deployhub.platform.support.OracleTestContainer;
import org.junit.jupiter.api.Tag;
import org.testcontainers.oracle.OracleContainer;

import java.util.Map;

@Tag("oracle")
class OraclePersistenceAcrossRestartTest extends PersistenceAcrossRestartTestBase {

    @Override
    protected Map<String, String> databaseProperties() {
        OracleContainer oracle = OracleTestContainer.get();
        return Map.of(
                "spring.datasource.url", oracle.getJdbcUrl(),
                "spring.datasource.username", oracle.getUsername(),
                "spring.datasource.password", oracle.getPassword(),
                "spring.jpa.hibernate.ddl-auto", "validate");
    }
}
