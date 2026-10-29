package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.models.enums.EnvVarKind;
import com.deployhub.platform.services.Interface.IRepositoryFiles;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Which variables an app needs and what kind each is, from in-memory repository files (no network). */
class EnvRequirementsAnalysisTest {

    private final Map<String, String> files = new HashMap<>();
    private final EnvRequirementsService service = new EnvRequirementsService(null, null, null, null, null, null, null, null);

    private Map<String, EnvRequirementsService.Needed> analyse(String folder) {
        IRepositoryFiles.Snapshot snapshot = new IRepositoryFiles.Snapshot() {
            @Override
            public Set<String> paths() {
                return files.keySet();
            }

            @Override
            public Optional<String> read(String path) {
                return Optional.ofNullable(files.get(path));
            }

            @Override
            public void close() {
            }
        };
        return service.analyse(snapshot, folder).needed().stream()
                .collect(Collectors.toMap(EnvRequirementsService.Needed::name, Function.identity()));
    }

    @Test
    void theEmployeeManagementBackend() {
        files.put("backend/.env.example", """
                # Server Configuration
                PORT=5000
                NODE_ENV=development

                # Database Configuration
                DB_HOST=localhost
                DB_PORT=3306
                DB_USER=root
                DB_PASSWORD=your_password
                DB_NAME=employee_management_db

                # JWT Configuration
                JWT_SECRET=your_super_secret_jwt_key_change_in_production
                JWT_EXPIRES_IN=24h

                # CORS
                CORS_ORIGIN=http://localhost:4200
                """);
        files.put("backend/src/server.js", "const port = process.env.PORT || 5000; const s = process.env.JWT_SECRET;");

        Map<String, EnvRequirementsService.Needed> needed = analyse("backend");

        assertThat(needed.get("PORT").kind()).isEqualTo(EnvVarKind.PLATFORM);
        assertThat(needed.get("NODE_ENV").suggestedValue()).isEqualTo("production");
        assertThat(List.of("DB_HOST", "DB_PORT", "DB_USER", "DB_PASSWORD", "DB_NAME"))
                .allSatisfy(n -> assertThat(needed.get(n).kind()).isEqualTo(EnvVarKind.DATABASE));
        assertThat(needed.get("JWT_SECRET").kind()).isEqualTo(EnvVarKind.SECRET);
        assertThat(needed.get("JWT_SECRET").suggestedValue()).as("never suggest the example secret").isNull();
        assertThat(needed.get("JWT_EXPIRES_IN").kind()).isEqualTo(EnvVarKind.SETTING);
        assertThat(needed.get("JWT_EXPIRES_IN").suggestedValue()).isEqualTo("24h");
        assertThat(needed.get("JWT_EXPIRES_IN").help()).contains("JWT Configuration");
        assertThat(needed.get("CORS_ORIGIN").kind()).isEqualTo(EnvVarKind.LOCAL_ONLY);
        assertThat(needed.get("CORS_ORIGIN").required()).isFalse();
    }

    @Test
    void withoutAnExampleFileTheCodeTellsWhatIsNeeded() {
        files.put("src/app.js", """
                const stripe = require('stripe')(process.env.STRIPE_SECRET_KEY);
                const timeout = process.env.API_TIMEOUT || 30;
                const session = process.env['SESSION_SECRET'];
                const pwd = process.env.DB_USERNAME;
                """);
        files.put("node_modules/lib/index.js", "process.env.SHOULD_BE_IGNORED");
        files.put("src/app.test.js", "process.env.TEST_ONLY_VAR");

        Map<String, EnvRequirementsService.Needed> needed = analyse("");

        assertThat(needed).containsOnlyKeys("STRIPE_SECRET_KEY", "API_TIMEOUT", "SESSION_SECRET", "DB_USERNAME");
        assertThat(needed.get("STRIPE_SECRET_KEY").kind()).isEqualTo(EnvVarKind.EXTERNAL);
        assertThat(needed.get("API_TIMEOUT").required()).as("has a default").isFalse();
        assertThat(needed.get("SESSION_SECRET").kind()).isEqualTo(EnvVarKind.SECRET);
        assertThat(needed.get("SESSION_SECRET").required()).isTrue();
        assertThat(needed.get("DB_USERNAME").kind()).isEqualTo(EnvVarKind.DATABASE_ALIAS);
    }

    @Test
    void springPlaceholdersWithoutDefaultsAreRequired() {
        files.put("api/src/main/resources/application.properties", """
                spring.datasource.url=${DB_URL}
                deployhub.secrets.key=${DEPLOYHUB_SECRETS_KEY:}
                spring.mail.host=${MAIL_HOST:smtp.gmail.com}
                """);

        Map<String, EnvRequirementsService.Needed> needed = analyse("api");

        assertThat(needed.get("DB_URL").required()).isTrue();
        assertThat(needed.get("DEPLOYHUB_SECRETS_KEY").kind()).isEqualTo(EnvVarKind.SECRET);
        assertThat(needed.get("DEPLOYHUB_SECRETS_KEY").required()).isFalse();
        assertThat(needed.get("MAIL_HOST").kind()).isEqualTo(EnvVarKind.EXTERNAL);
    }

    @Test
    void pythonEnvironReadsAreRequiredAndGetenvIsOptional() {
        files.put("app.py", "import os\nKEY = os.environ['SECRET_KEY']\nDEBUG = os.getenv('DEBUG_MODE', '0')\n"
                + "DB = os.getenv('DATABASE_URI')\n");

        Map<String, EnvRequirementsService.Needed> needed = analyse("");

        assertThat(needed.get("SECRET_KEY").kind()).isEqualTo(EnvVarKind.SECRET);
        assertThat(needed.get("SECRET_KEY").required()).isTrue();
        assertThat(needed.get("DEBUG_MODE").required()).as("explicit default").isFalse();
        assertThat(needed.get("DATABASE_URI").required()).as("os.getenv without a default returns None").isTrue();
    }

    @Test
    void deployhubJsonFromTheAuthorWins() {
        files.put("deployhub-backend/deployhub.json", """
                {"env": {
                   "SPRING_PROFILES_ACTIVE": {"value": "local", "description": "Built-in H2 database"},
                   "DEPLOYHUB_EXECUTION_MODE": {"value": "SIMULATED"},
                   "DEPLOYHUB_SECRETS_KEY": {"generate": "secret"},
                   "DB_URL": {"required": false}
                 }}""");
        files.put("deployhub-backend/src/main/resources/application.properties", "a=${DB_URL}\nb=${OTHER_THING}");

        Map<String, EnvRequirementsService.Needed> needed = analyse("deployhub-backend");

        assertThat(needed.get("SPRING_PROFILES_ACTIVE").suggestedValue()).isEqualTo("local");
        assertThat(needed.get("SPRING_PROFILES_ACTIVE").help()).contains("Built-in H2 database");
        assertThat(needed.get("DEPLOYHUB_SECRETS_KEY").kind()).isEqualTo(EnvVarKind.SECRET);
        assertThat(needed.get("DB_URL").required()).as("deployhub.json said optional").isFalse();
        assertThat(needed.get("OTHER_THING").required()).isTrue();
    }

    @Test
    void placeholdersAreNotUsedAsValues() {
        assertThat(EnvRequirementsService.classify("API_URL", "<your api url>", true, "x", "").kind())
                .isEqualTo(EnvVarKind.UNKNOWN);
        assertThat(EnvRequirementsService.classify("ADMIN_EMAIL", "your_email@example.com", true, "x", "").kind())
                .isEqualTo(EnvVarKind.UNKNOWN);
        assertThat(EnvRequirementsService.classify("UPLOAD_LIMIT", "10mb", true, "x", "").suggestedValue())
                .isEqualTo("10mb");
        assertThat(EnvRequirementsService.classify("GOOGLE_CLIENT_SECRET", "", true, "x", "").kind())
                .as("an external secret is never generated").isEqualTo(EnvVarKind.EXTERNAL);
    }

    @Test
    void theRealDeployhubJsonOfThisRepositoryMakesEverythingFillable() throws Exception {
        files.put("deployhub-backend/deployhub.json", java.nio.file.Files.readString(java.nio.file.Path.of("deployhub.json")));
        files.put("deployhub-backend/.env.example", "DB_URL=jdbc:oracle:thin:@//localhost:1521/FREEPDB1\nDB_USERNAME=deployhub_app\n"
                + "DB_PASSWORD=<choose a password>\nDEPLOYHUB_SECRETS_KEY=<paste>\n");

        IRepositoryFiles.Snapshot snapshot = new IRepositoryFiles.Snapshot() {
            public Set<String> paths() { return files.keySet(); }
            public Optional<String> read(String path) { return Optional.ofNullable(files.get(path)); }
            public void close() { }
        };
        var analysis = service.analyse(snapshot, "deployhub-backend");

        assertThat(analysis.databaseWanted()).as("deployhub.json says no database").isFalse();
        assertThat(analysis.needed()).filteredOn(EnvRequirementsService.Needed::required)
                .extracting(EnvRequirementsService.Needed::name)
                .containsExactlyInAnyOrder("SPRING_PROFILES_ACTIVE", "DEPLOYHUB_EXECUTION_MODE", "DEPLOYHUB_SECRETS_KEY");
    }

    @Test
    void anExampleVariableTheCodeReadsWithADefaultIsOptionalAndCommentBlocksStayWhole() {
        files.put("deployhub-backend/.env.example", """
                # Paid deploys: Razorpay API keys (Dashboard > Account & Settings > API Keys). rzp_test_... = test mode, no real
                # money. The secret stays on the server; never put it in the frontend or commit it.
                RAZORPAY_KEY_ID=rzp_test_<your key id>
                RAZORPAY_KEY_SECRET=<your key secret>

                # Encrypts project environment variables.
                DEPLOYHUB_SECRETS_KEY=<paste>
                """);
        files.put("deployhub-backend/src/main/resources/application.properties", """
                deployhub.billing.razorpay.key-id=${RAZORPAY_KEY_ID:}
                deployhub.billing.razorpay.key-secret=${RAZORPAY_KEY_SECRET:}
                deployhub.secrets.key=${DEPLOYHUB_SECRETS_KEY}
                """);

        Map<String, EnvRequirementsService.Needed> needed = analyse("deployhub-backend");

        assertThat(needed.get("RAZORPAY_KEY_ID").required()).as("the code has a default").isFalse();
        assertThat(needed.get("RAZORPAY_KEY_SECRET").required()).isFalse();
        assertThat(needed.get("RAZORPAY_KEY_SECRET").help())
                .startsWith("Paid deploys: Razorpay API keys").contains("no real money. The secret stays")
                .doesNotContain("commit it..");
        assertThat(needed.get("DEPLOYHUB_SECRETS_KEY").required()).as("no default in the code").isTrue();
        assertThat(needed.get("DEPLOYHUB_SECRETS_KEY").help()).startsWith("Encrypts project environment variables. Any");
    }

    /** Review #3: a variable from .env.example read with os.getenv("X") (no default) must stay required. */
    @Test
    void anExampleVariableReadWithGetenvWithoutADefaultStaysRequired() {
        files.put(".env.example", "SECRET_KEY=change-me\nFLASK_DEBUG=0\n");
        files.put("app.py", "import os\nSECRET = os.getenv('SECRET_KEY')\nDEBUG = os.getenv('FLASK_DEBUG', '0')\n");

        Map<String, EnvRequirementsService.Needed> needed = analyse("");

        assertThat(needed.get("SECRET_KEY").required()).isTrue();
        assertThat(needed.get("FLASK_DEBUG").required()).isFalse();
    }
}
