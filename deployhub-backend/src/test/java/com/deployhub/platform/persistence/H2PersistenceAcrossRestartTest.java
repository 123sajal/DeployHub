package com.deployhub.platform.persistence;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

class H2PersistenceAcrossRestartTest extends PersistenceAcrossRestartTestBase {

    @TempDir
    static Path dataDir;

    @Override
    protected Map<String, String> databaseProperties() {
        return Map.of("spring.datasource.url",
                "jdbc:h2:file:" + dataDir.resolve("deployhub") + ";MODE=Oracle;DEFAULT_NULL_ORDERING=HIGH");
    }
}
