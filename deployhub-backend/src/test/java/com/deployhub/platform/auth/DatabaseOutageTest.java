package com.deployhub.platform.auth;

import com.deployhub.platform.support.OutageProxy;
import org.h2.tools.Server;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * H2 (as a TCP server) behind the outage proxy. H2 closes an in-memory database when a remote
 * session is cut mid-flight, so recovery is verified only against Oracle (DatabaseOutageOracleTest).
 */
class DatabaseOutageTest extends DatabaseOutageTestBase {

    private static OutageProxy proxy;

    @DynamicPropertySource
    static void databaseBehindProxy(DynamicPropertyRegistry registry) throws Exception {
        Server database = Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
        proxy = new OutageProxy("127.0.0.1", database.getPort());
        registry.add("spring.datasource.url", () -> "jdbc:h2:tcp://127.0.0.1:" + proxy.port()
                + "/mem:outage-test;MODE=Oracle;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
    }

    @Override
    protected OutageProxy proxy() {
        return proxy;
    }

    @Override
    protected boolean verifiesRecovery() {
        return false;
    }
}
