package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.exceptions.TooManyRequestsException;
import com.deployhub.platform.models.DTO.DetectRequestDTO;
import com.deployhub.platform.models.DTO.DetectResponseDTO;
import com.deployhub.platform.models.enums.ApplicationType;
import com.deployhub.platform.services.Interface.ICurrentUserService;
import com.deployhub.platform.services.Interface.IProjectDetectionService;
import com.deployhub.platform.services.Interface.IRepositoryFiles;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks at a repository's files (package.json, angular.json, lock files, pom.xml, requirements.txt, Dockerfile, ...)
 * in the App folder and suggests the application type and the commands that fit that project, so users don't
 * have to know them. Only reads public files; the user still sees and can change everything before saving.
 */
@Service
public class ProjectDetectionService implements IProjectDetectionService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Folders checked when the chosen folder has no app, e.g. a repository with backend/ and frontend/. */
    static final List<String> COMMON_APP_FOLDERS = List.of("backend", "frontend", "server", "client", "api", "web", "app");
    /** Files that mark a folder as an app. */
    private static final List<String> APP_MARKERS = List.of("package.json", "pom.xml", "build.gradle",
            "build.gradle.kts", "requirements.txt", "pyproject.toml", "Dockerfile");
    /** Scripts that prepare the database; run before starting the app. */
    private static final List<String> SETUP_SCRIPTS = List.of("db:setup", "setup:db", "db:migrate", "migrate", "prisma:migrate");
    private static final Set<String> SERVER_FRAMEWORKS = Set.of("express", "fastify", "koa", "@nestjs/core", "@hapi/hapi",
            "hapi", "restify", "socket.io");
    private static final String SERVE_FIRST_BUILD_FOLDER =
            "npx --yes serve -s \"$(for d in dist/*/browser dist build out; do [ -d \"$d\" ] && echo \"$d\" && break; done)\" -l $PORT";
    private static final Pattern DJANGO_SETTINGS = Pattern.compile("DJANGO_SETTINGS_MODULE['\"]?\\s*,\\s*['\"]([\\w.]+)\\.settings['\"]");
    private static final int MAX_PER_MINUTE = 20;

    private final IRepositoryFiles files;
    private final ICurrentUserService currentUserService;
    private final Clock clock;
    private final Map<Long, Deque<Instant>> recentRequests = new ConcurrentHashMap<>();

    public ProjectDetectionService(IRepositoryFiles files, ICurrentUserService currentUserService, Clock clock) {
        this.files = files;
        this.currentUserService = currentUserService;
        this.clock = clock;
    }

    @Override
    public DetectResponseDTO detect(DetectRequestDTO request) {
        throttle(currentUserService.currentUserId());
        String folder = folder(request.rootDirectory());
        List<String> notes = new ArrayList<>();
        try (IRepositoryFiles.Snapshot snapshot = files.open(request.repositoryUrl(), request.branch())) {
            return detect(new Repo(snapshot, folder), notes);
        } catch (IRepositoryFiles.RepositoryNotReadable e) {
            notes.add(e.getMessage());
            return new DetectResponseDTO(null, null, null, List.of(), notes);
        }
    }

    private DetectResponseDTO detect(Repo repo, List<String> notes) {
        String folder = repo.folder;
        Suggestion found = suggest(repo, notes);
        if (found == null) {
            List<String> appFolders = folder.isEmpty() ? findAppFolders(repo) : List.of();
            String where = folder.isEmpty() ? "the repository's main folder" : "the folder '" + folder + "'";
            if (!appFolders.isEmpty()) {
                notes.add("No app in " + where + ", but these folders look like apps: " + String.join(", ", appFolders)
                        + ". Type one of them in App folder.");
            } else {
                notes.add("No package.json, pom.xml, build.gradle, requirements.txt or Dockerfile in " + where
                        + ". Check the App folder and the branch.");
            }
            return new DetectResponseDTO(null, null, null, appFolders, notes);
        }
        if (found.type() != ApplicationType.DOCKERFILE && repo.exists("Dockerfile")) {
            notes.add("There is also a Dockerfile. To build with it instead, choose the type Dockerfile.");
        }
        return new DetectResponseDTO(found.type(), found.build(), found.start(), List.of(), notes);
    }

    private record Suggestion(ApplicationType type, String build, String start) {
    }

    private Suggestion suggest(Repo repo, List<String> notes) {
        Optional<String> packageJson = repo.read("package.json");
        if (packageJson.isPresent()) {
            return node(repo, packageJson.get(), notes);
        }
        if (repo.exists("pom.xml")) {
            return maven(repo, notes);
        }
        if (repo.exists("build.gradle") || repo.exists("build.gradle.kts")) {
            return gradle(repo, notes);
        }
        Optional<String> requirements = repo.read("requirements.txt");
        if (requirements.isPresent() || repo.exists("pyproject.toml")) {
            return python(repo, requirements.orElse(""), notes);
        }
        if (repo.exists("Dockerfile")) {
            notes.add("Found a Dockerfile: DeployHub builds and starts the app with it.");
            return new Suggestion(ApplicationType.DOCKERFILE, null, null);
        }
        return null;
    }

    // ---------------------------------------------------------------- Node.js

    private Suggestion node(Repo repo, String packageJson, List<String> notes) {
        JsonNode pkg;
        try {
            pkg = JSON.readTree(packageJson);
        } catch (RuntimeException e) {
            notes.add("package.json isn't valid JSON, so the commands are only a guess.");
            return new Suggestion(ApplicationType.NODE_JS, "npm install", "npm start");
        }
        Set<String> deps = new LinkedHashSet<>();
        pkg.path("dependencies").propertyNames().forEach(deps::add);
        pkg.path("devDependencies").propertyNames().forEach(deps::add);
        Map<String, String> scripts = new HashMap<>();
        pkg.path("scripts").properties().forEach(e -> scripts.put(e.getKey(), e.getValue().asString("")));

        String install;
        String run;
        if (repo.exists("pnpm-lock.yaml")) {
            install = "corepack enable && pnpm install --frozen-lockfile";
            run = "pnpm ";
        } else if (repo.exists("yarn.lock")) {
            install = "yarn install --frozen-lockfile";
            run = "yarn ";
        } else if (repo.exists("package-lock.json")) {
            install = "npm ci";
            run = "npm run ";
        } else {
            install = "npm install";
            run = "npm run ";
        }
        boolean hasBuild = scripts.containsKey("build");
        boolean server = deps.stream().anyMatch(SERVER_FRAMEWORKS::contains);

        if (deps.contains("@angular/core")) {
            String output = angularOutput(repo).orElse(null);
            String build = install + " && " + (hasBuild ? run + "build" : "npx ng build");
            notes.add("Angular app: it is built, then its files are served"
                    + (output != null ? " from " + output + " (from angular.json)." : "."));
            return new Suggestion(ApplicationType.STATIC_SITE, build,
                    output != null ? "npx --yes serve -s " + output + " -l $PORT" : SERVE_FIRST_BUILD_FOLDER);
        }
        if (deps.contains("next")) {
            notes.add("Next.js app: built with \"build\", started with \"start\".");
            return new Suggestion(ApplicationType.NODE_JS, install + (hasBuild ? " && " + run + "build" : ""),
                    scripts.containsKey("start") ? startCommand(run) : "npx next start -p $PORT");
        }
        if (!server && (deps.contains("vite") || deps.contains("react-scripts") || deps.contains("@vue/cli-service"))) {
            String output = deps.contains("react-scripts") ? "build" : "dist";
            notes.add("Frontend app (" + (deps.contains("react-scripts") ? "Create React App" : deps.contains("vite") ? "Vite" : "Vue CLI")
                    + "): it is built, then the files in " + output + " are served.");
            return new Suggestion(ApplicationType.STATIC_SITE, install + (hasBuild ? " && " + run + "build" : ""),
                    "npx --yes serve -s " + output + " -l $PORT");
        }

        String build = install + (hasBuild ? " && " + run + "build" : "");
        String start;
        if (scripts.containsKey("start")) {
            start = startCommand(run);
        } else if (!pkg.path("main").asString("").isBlank()) {
            start = "node " + pkg.path("main").asString();
            notes.add("package.json has no \"start\" script, so the file in \"main\" is started.");
        } else {
            start = "node index.js";
            notes.add("package.json has no \"start\" script or \"main\" file: check the start command.");
        }
        Optional<String> setup = SETUP_SCRIPTS.stream().filter(scripts::containsKey).findFirst();
        if (setup.isPresent()) {
            start = run + setup.get() + " && " + start;
            notes.add("Runs \"" + setup.get() + "\" before starting, so the database tables exist.");
        }
        notes.add(hasBuild ? "Node.js app with a \"build\" script." : "Node.js app (no \"build\" script, so nothing to build).");
        return new Suggestion(ApplicationType.NODE_JS, build, start);
    }

    private static String startCommand(String run) {
        return run.startsWith("npm") ? "npm start" : run + "start";
    }

    /** Where `ng build` puts the site, from angular.json: e.g. dist/ems-frontend/browser. */
    private Optional<String> angularOutput(Repo repo) {
        Optional<String> text = repo.read("angular.json");
        if (text.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode angular = JSON.readTree(text.get());
            JsonNode projects = angular.path("projects");
            String name = angular.path("defaultProject").asString("");
            if (name.isEmpty() || projects.path(name).isMissingNode()) {
                var names = projects.propertyNames().iterator();
                if (!names.hasNext()) {
                    return Optional.empty();
                }
                name = names.next();
            }
            JsonNode build = projects.path(name).path("architect").path("build");
            String builder = build.path("builder").asString("");
            JsonNode outputPath = build.path("options").path("outputPath");
            boolean application = builder.endsWith(":application"); // Angular 17+: files go into <outputPath>/browser
            String base;
            String browser = application ? "browser" : "";
            if (outputPath.isObject()) {
                base = outputPath.path("base").asString("dist/" + name);
                if (outputPath.has("browser")) {
                    browser = outputPath.path("browser").asString("");
                }
            } else {
                base = outputPath.asString("dist/" + name);
            }
            base = base.replaceAll("/+$", "");
            if (!base.matches("[A-Za-z0-9._/-]+") || base.contains("..") || !browser.matches("[A-Za-z0-9._-]*")) {
                return Optional.empty(); // only plain paths go into a command
            }
            return Optional.of(browser.isEmpty() ? base : base + "/" + browser);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- Java

    private Suggestion maven(Repo repo, List<String> notes) {
        String build = "./mvnw -B -DskipTests package";
        if (!repo.exists("mvnw")) {
            build = "mvn -B -DskipTests package";
            notes.add("There is no Maven wrapper (mvnw), and the Java build image has no Maven: add the wrapper to the "
                    + "repository (run mvn wrapper:wrapper and commit it), or use a Dockerfile.");
        }
        notes.add("Java app built with Maven; the jar in target/ is started.");
        return new Suggestion(ApplicationType.JAVA_SPRING_BOOT, build, "java -jar target/*.jar");
    }

    private Suggestion gradle(Repo repo, List<String> notes) {
        String build = "./gradlew -x test build";
        if (!repo.exists("gradlew")) {
            build = "gradle -x test build";
            notes.add("There is no Gradle wrapper (gradlew), and the Java build image has no Gradle: add the wrapper "
                    + "(gradle wrapper) or use a Dockerfile.");
        }
        notes.add("Java app built with Gradle; the jar in build/libs is started.");
        return new Suggestion(ApplicationType.JAVA_SPRING_BOOT, build,
                "java -jar \"$(ls build/libs/*.jar | grep -v plain | head -n 1)\"");
    }

    // ---------------------------------------------------------------- Python

    private Suggestion python(Repo repo, String requirements, List<String> notes) {
        String reqs = requirements.toLowerCase(Locale.ROOT);
        String build = requirements.isEmpty() ? "pip install --no-cache-dir ." : "pip install --no-cache-dir -r requirements.txt";
        Optional<String> manage = repo.read("manage.py");
        if (manage.isPresent()) {
            Matcher settings = DJANGO_SETTINGS.matcher(manage.get());
            String project = settings.find() ? settings.group(1) : "config";
            if (!reqs.contains("gunicorn")) {
                notes.add("Add gunicorn to requirements.txt: it runs the Django app.");
            }
            notes.add("Django app: database migrations run before starting.");
            return new Suggestion(ApplicationType.PYTHON, build,
                    "python manage.py migrate && gunicorn -b 0.0.0.0:$PORT " + project + ".wsgi");
        }
        String module = repo.exists("main.py") ? "main" : repo.exists("app.py") ? "app" : repo.exists("wsgi.py") ? "wsgi" : "app";
        if (reqs.contains("fastapi")) {
            if (!reqs.contains("uvicorn")) {
                notes.add("Add uvicorn to requirements.txt: it runs the FastAPI app.");
            }
            notes.add("FastAPI app (" + module + ".py).");
            return new Suggestion(ApplicationType.PYTHON, build, "uvicorn " + module + ":app --host 0.0.0.0 --port $PORT");
        }
        if (!reqs.contains("gunicorn")) {
            notes.add("Add gunicorn to requirements.txt: it runs the app.");
        }
        notes.add((reqs.contains("flask") ? "Flask" : "Python") + " app (" + module + ".py, object \"app\").");
        return new Suggestion(ApplicationType.PYTHON, build, "gunicorn -b 0.0.0.0:$PORT " + module + ":app");
    }

    // ---------------------------------------------------------------- helpers

    private List<String> findAppFolders(Repo root) {
        List<String> found = new ArrayList<>();
        for (String candidate : COMMON_APP_FOLDERS) {
            if (APP_MARKERS.stream().anyMatch(marker -> root.snapshot.paths().contains(candidate + "/" + marker))) {
                found.add(candidate);
            }
        }
        // Any other top-level folder with an app in it, after the usual names.
        root.snapshot.paths().stream()
                .filter(path -> path.indexOf('/') > 0 && path.indexOf('/') == path.lastIndexOf('/'))
                .filter(path -> APP_MARKERS.contains(path.substring(path.indexOf('/') + 1)))
                .map(path -> path.substring(0, path.indexOf('/')))
                .filter(dir -> !found.contains(dir))
                .sorted()
                .distinct()
                .forEach(found::add);
        return found.size() > 6 ? found.subList(0, 6) : found;
    }

    private static String folder(String rootDirectory) {
        if (rootDirectory == null) {
            return "";
        }
        String folder = rootDirectory.trim();
        if (folder.startsWith("./")) {
            folder = folder.substring(2);
        }
        return folder.replaceAll("/+$", "");
    }

    private void throttle(Long userId) {
        Instant now = clock.instant();
        Deque<Instant> times = recentRequests.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(Duration.ofMinutes(1)))) {
                times.pollFirst();
            }
            if (times.size() >= MAX_PER_MINUTE) {
                throw new TooManyRequestsException("Too many repository checks. Wait a minute.", Duration.ofMinutes(1));
            }
            times.addLast(now);
        }
    }

    /** One folder of the fetched branch; existence comes from the file list, file contents are read once each. */
    private static final class Repo {
        private final IRepositoryFiles.Snapshot snapshot;
        private final String folder;
        private final Map<String, Optional<String>> cache = new HashMap<>();

        Repo(IRepositoryFiles.Snapshot snapshot, String folder) {
            this.snapshot = snapshot;
            this.folder = folder;
        }

        private String path(String name) {
            return folder.isEmpty() ? name : folder + "/" + name;
        }

        Optional<String> read(String name) {
            return cache.computeIfAbsent(name, n -> snapshot.read(path(n)));
        }

        boolean exists(String name) {
            return snapshot.paths().contains(path(name));
        }
    }
}
