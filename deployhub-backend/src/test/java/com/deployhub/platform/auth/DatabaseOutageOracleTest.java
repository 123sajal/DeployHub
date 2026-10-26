package com.deployhub.platform.auth;

import com.deployhub.platform.support.OracleTestContainer;
import com.deployhub.platform.support.OutageProxy;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.oracle.OracleContainer;

/** Real Oracle behind the outage proxy: checks the 503 behaviour and that the app recovers afterwards. */
@Tag("oracle")
class DatabaseOutageOracleTest extends DatabaseOutageTestBase {

    private static OutageProxy proxy;

    @DynamicPropertySource
    static void oracleBehindProxy(DynamicPropertyRegistry registry) throws Exception {
        OracleContainer oracle = OracleTestContainer.get();
        proxy = new OutageProxy(oracle.getHost(), oracle.getOraclePort());
        registry.add("spring.datasource.url",
                () -> "jdbc:oracle:thin:@//127.0.0.1:" + proxy.port() + "/" + oracle.getDatabaseName());
        registry.add("spring.datasource.username", oracle::getUsername);
        registry.add("spring.datasource.password", oracle::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Override
    protected OutageProxy proxy() {
        return proxy;
    }

    @Override
    protected boolean verifiesRecovery() {
        return true;
    }
}
