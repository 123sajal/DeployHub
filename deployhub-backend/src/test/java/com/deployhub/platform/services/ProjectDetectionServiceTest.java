package com.deployhub.platform.services;

import com.deployhub.platform.exceptions.TooManyRequestsException;
import com.deployhub.platform.models.DTO.DetectRequestDTO;
import com.deployhub.platform.models.DTO.DetectResponseDTO;
import com.deployhub.platform.models.entities.UserMaster;
import com.deployhub.platform.models.enums.ApplicationType;
import com.deployhub.platform.services.Implementations.ProjectDetectionService;
import com.deployhub.platform.services.Interface.ICurrentUserService;
import com.deployhub.platform.services.Interface.IRepositoryFiles;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Detection from repository files, with an in-memory repository (no network). */
class ProjectDetectionServiceTest {

    private static final String REPO = "https://github.com/acme/app";

    private final Map<String, String> files = new HashMap<>();
    private final ProjectDetectionService service = new ProjectDetectionService(
            (url, branch, smallFiles) -> {
                if (branch.equals("missing")) {
                    throw new IRepositoryFiles.RepositoryNotReadable("The branch 'missing' doesn't exist in this repository.");
                }
                return new IRepositoryFiles.Snapshot() {
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
            },
            new ICurrentUserService() {
                @Override
                public Long currentUserId() {
                    return 1L;
                }

                @Override
                public UserMaster currentUserReference() {
                    return null;
                }
            },
            Clock.fixed(Instant.parse("2026-10-27T10:00:00Z"), ZoneOffset.UTC));

    private DetectResponseDTO detect(String folder) {
        return service.detect(new DetectRequestDTO(REPO, "main", folder));
    }

    /** The Employee Management repository: Express backend in backend/, Angular frontend in frontend/. */
    private void employeeManagement() {
        files.put("backend/package.json", """
                {"name":"ems-backend","main":"src/server.js",
                 "scripts":{"start":"node src/server.js","dev":"nodemon src/server.js","db:setup":"node src/database/setup.js"},
                 "dependencies":{"express":"^4.19.2","mysql2":"^3.9.7","bcryptjs":"^2.4.3"}}""");
        files.put("backend/package-lock.json", "{}");
        files.put("frontend/package.json", """
                {"name":"ems-frontend","scripts":{"ng":"ng","start":"ng serve --port 4200","build":"ng build"},
                 "dependencies":{"@angular/core":"^17.3.0"},"devDependencies":{"@angular/cli":"^17.3.0"}}""");
        files.put("frontend/package-lock.json", "{}");
        files.put("frontend/angular.json", """
                {"projects":{"ems-frontend":{"architect":{"build":{
                  "builder":"@angular-devkit/build-angular:application","options":{"outputPath":"dist/ems-frontend"}}}}}}""");
    }

    @Test
    void theRepositoryRootPointsToTheAppFolders() {
        employeeManagement();

        DetectResponseDTO result = detect("");

        assertThat(result.appType()).isNull();
        assertThat(result.appFolders()).containsExactly("backend", "frontend");
        assertThat(result.notes()).anyMatch(n -> n.contains("backend, frontend") && n.contains("App folder"));
    }

    @Test
    void anExpressBackendWithADatabaseSetupScript() {
        employeeManagement();

        DetectResponseDTO result = detect("backend");

        assertThat(result.appType()).isEqualTo(ApplicationType.NODE_JS);
        assertThat(result.buildCommand()).as("lock file, no build script").isEqualTo("npm ci");
        assertThat(result.startCommand()).isEqualTo("npm run db:setup && npm start");
        assertThat(result.notes()).anyMatch(n -> n.contains("db:setup"));
    }

    @Test
    void anAngularFrontendIsServedFromItsRealOutputFolder() {
        employeeManagement();

        DetectResponseDTO result = detect("./frontend/");

        assertThat(result.appType()).isEqualTo(ApplicationType.STATIC_SITE);
        assertThat(result.buildCommand()).isEqualTo("npm ci && npm run build");
        assertThat(result.startCommand()).isEqualTo("npx --yes serve -s dist/ems-frontend/browser -l $PORT");
    }

    @Test
    void olderAngularBuildsGoStraightIntoTheOutputPath() {
        files.put("package.json", "{\"scripts\":{\"build\":\"ng build\"},\"dependencies\":{\"@angular/core\":\"15\"}}");
        files.put("angular.json", """
                {"defaultProject":"shop","projects":{"shop":{"architect":{"build":{
                  "builder":"@angular-devkit/build-angular:browser","options":{"outputPath":"dist/shop"}}}}}}""");

        assertThat(detect(null).startCommand()).isEqualTo("npx --yes serve -s dist/shop -l $PORT");
    }

    @Test
    void viteAndCreateReactAppAreStaticSitesWithTheirOwnFolders() {
        files.put("package.json", "{\"scripts\":{\"build\":\"vite build\"},\"devDependencies\":{\"vite\":\"5\"}}");
        DetectResponseDTO vite = detect(null);
        assertThat(vite.appType()).isEqualTo(ApplicationType.STATIC_SITE);
        assertThat(vite.buildCommand()).isEqualTo("npm install && npm run build");
        assertThat(vite.startCommand()).isEqualTo("npx --yes serve -s dist -l $PORT");

        files.put("package.json", "{\"scripts\":{\"build\":\"react-scripts build\"},\"dependencies\":{\"react-scripts\":\"5\"}}");
        files.put("yarn.lock", "");
        DetectResponseDTO cra = detect(null);
        assertThat(cra.buildCommand()).isEqualTo("yarn install --frozen-lockfile && yarn build");
        assertThat(cra.startCommand()).isEqualTo("npx --yes serve -s build -l $PORT");
    }

    @Test
    void aTypeScriptBackendIsBuiltAndAServerWithViteIsStillABackend() {
        files.put("package.json", """
                {"scripts":{"build":"tsc","start":"node dist/index.js"},"dependencies":{"express":"4","vite":"5"}}""");
        files.put("package-lock.json", "{}");

        DetectResponseDTO result = detect(null);

        assertThat(result.appType()).isEqualTo(ApplicationType.NODE_JS);
        assertThat(result.buildCommand()).isEqualTo("npm ci && npm run build");
        assertThat(result.startCommand()).isEqualTo("npm start");
    }

    @Test
    void withoutAStartScriptTheMainFileIsStarted() {
        files.put("package.json", "{\"main\":\"server.js\",\"dependencies\":{\"express\":\"4\"}}");

        assertThat(detect(null).startCommand()).isEqualTo("node server.js");
    }

    @Test
    void mavenNeedsItsWrapper() {
        files.put("pom.xml", "<project/>");
        DetectResponseDTO noWrapper = detect(null);
        assertThat(noWrapper.appType()).isEqualTo(ApplicationType.JAVA_SPRING_BOOT);
        assertThat(noWrapper.notes()).anyMatch(n -> n.contains("no Maven wrapper"));

        files.put("mvnw", "#!/bin/sh");
        DetectResponseDTO withWrapper = detect(null);
        assertThat(withWrapper.buildCommand()).isEqualTo("./mvnw -B -DskipTests package");
        assertThat(withWrapper.startCommand()).isEqualTo("java -jar target/*.jar");
    }

    @Test
    void pythonFrameworksGetTheirServers() {
        files.put("requirements.txt", "Django==5.0\ngunicorn\n");
        files.put("manage.py", "os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'mysite.settings')");
        assertThat(detect(null).startCommand()).isEqualTo("python manage.py migrate && gunicorn -b 0.0.0.0:$PORT mysite.wsgi");

        files.remove("manage.py");
        files.put("requirements.txt", "fastapi\n");
        files.put("main.py", "app = FastAPI()");
        DetectResponseDTO fastapi = detect(null);
        assertThat(fastapi.startCommand()).isEqualTo("uvicorn main:app --host 0.0.0.0 --port $PORT");
        assertThat(fastapi.notes()).anyMatch(n -> n.contains("Add uvicorn"));

        files.remove("main.py");
        files.put("requirements.txt", "flask\n");
        files.put("app.py", "app = Flask(__name__)");
        DetectResponseDTO flask = detect(null);
        assertThat(flask.startCommand()).isEqualTo("gunicorn -b 0.0.0.0:$PORT app:app");
        assertThat(flask.notes()).anyMatch(n -> n.contains("Add gunicorn"));
    }

    @Test
    void aDockerfileAloneMeansDockerfileAndNextToAnotherTypeItIsMentioned() {
        files.put("Dockerfile", "FROM nginx");
        assertThat(detect(null).appType()).isEqualTo(ApplicationType.DOCKERFILE);

        files.put("package.json", "{\"scripts\":{\"start\":\"node a.js\"}}");
        assertThat(detect(null).notes()).anyMatch(n -> n.contains("also a Dockerfile"));
    }

    @Test
    void nothingFoundExplainsWhatToCheck() {
        DetectResponseDTO result = detect("backend");

        assertThat(result.appType()).isNull();
        assertThat(result.notes()).singleElement().asString().contains("the folder 'backend'").contains("App folder");
    }

    @Test
    void checksAreLimitedPerUser() {
        files.put("package.json", "{}");
        for (int i = 0; i < 20; i++) {
            detect(null);
        }
        assertThatThrownBy(() -> detect(null)).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void appFoldersWithOtherNamesAreFoundToo() {
        files.put("services/payments/package.json", "{}");   // too deep: not offered
        files.put("dashboard/package.json", "{}");
        files.put("backend/pom.xml", "<project/>");

        assertThat(detect("").appFolders()).containsExactly("backend", "dashboard");
    }

    @Test
    void aMissingBranchIsExplained() {
        DetectResponseDTO result = service.detect(new DetectRequestDTO(REPO, "missing", null));

        assertThat(result.appType()).isNull();
        assertThat(result.notes()).containsExactly("The branch 'missing' doesn't exist in this repository.");
    }
}
