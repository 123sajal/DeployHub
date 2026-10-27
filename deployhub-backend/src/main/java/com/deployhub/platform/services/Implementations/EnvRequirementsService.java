package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.exceptions.ResourceNotFoundException;
import com.deployhub.platform.models.DTO.EnvRequirementsResponseDTO;
import com.deployhub.platform.models.DTO.EnvRequirementsResponseDTO.Item;
import com.deployhub.platform.models.entities.ProjectEnvVar;
import com.deployhub.platform.models.entities.ProjectMaster;
import com.deployhub.platform.models.enums.EnvVarKind;
import com.deployhub.platform.repositories.ProjectDatabaseRepository;
import com.deployhub.platform.repositories.ProjectEnvVarRepository;
import com.deployhub.platform.repositories.ProjectRepository;
import com.deployhub.platform.security.SecretCipher;
import com.deployhub.platform.services.Interface.ICurrentUserService;
import com.deployhub.platform.services.Interface.IEnvRequirementsService;
import com.deployhub.platform.services.Interface.IProjectEnvService;
import com.deployhub.platform.services.Interface.IRepositoryFiles;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Works out which environment variables a project's app needs, so users don't have to know: from the repository's
 * deployhub.json (if the author wrote one), .env.example and the code itself (process.env.X, ${X} in Spring
 * config, os.environ["X"]). Each variable gets a kind that decides how DeployHub helps (generate a secret, use the
 * example value, take it from the database, or ask the user). The repository part is cached for a few minutes;
 * what is set is always read fresh.
 */
@Service
public class EnvRequirementsService implements IEnvRequirementsService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration CACHE_FOR = Duration.ofMinutes(5);
    private static final int MAX_CODE_FILES = 300;
    private static final SecureRandom RANDOM = new SecureRandom();

    static final List<String> ENV_EXAMPLES = List.of(".env.example", ".env.sample", ".env.template", ".env.dist",
            "example.env", "env.example");
    static final Set<String> PLATFORM = Set.of("PORT", "SERVER_PORT");
    /** What DeployHub's one-click database sets (see ProjectDatabaseService.VARIABLES). */
    static final Set<String> DATABASE = Set.of("DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD", "DATABASE_URL");
    /** The same values under other common names. */
    static final Map<String, String> DATABASE_ALIASES = Map.ofEntries(
            Map.entry("DB_USERNAME", "DB_USER"), Map.entry("DB_PASS", "DB_PASSWORD"), Map.entry("DB_DATABASE", "DB_NAME"),
            Map.entry("DATABASE_HOST", "DB_HOST"), Map.entry("DATABASE_PORT", "DB_PORT"),
            Map.entry("DATABASE_NAME", "DB_NAME"), Map.entry("DATABASE_USER", "DB_USER"),
            Map.entry("DATABASE_USERNAME", "DB_USER"), Map.entry("DATABASE_PASSWORD", "DB_PASSWORD"),
            Map.entry("MYSQL_HOST", "DB_HOST"), Map.entry("MYSQL_PORT", "DB_PORT"), Map.entry("MYSQL_DATABASE", "DB_NAME"),
            Map.entry("MYSQL_USER", "DB_USER"), Map.entry("MYSQL_PASSWORD", "DB_PASSWORD"), Map.entry("MYSQL_URL", "DATABASE_URL"));
    /** Keys that come from another service's account: DeployHub can't make them up. */
    private static final Pattern EXTERNAL = Pattern.compile("^(STRIPE|RAZORPAY|PAYPAL|AWS|S3|GOOGLE|GCP|FIREBASE|GITHUB|"
            + "GITLAB|SMTP|MAIL|EMAIL|GMAIL|SENDGRID|MAILGUN|TWILIO|OPENAI|ANTHROPIC|CLOUDINARY|SENTRY|SLACK|DISCORD|"
            + "AZURE|SUPABASE|MONGO|MONGODB|REDIS|AUTH0|OKTA|ALGOLIA|MAPBOX|PUSHER|NEXT_PUBLIC)(_.*)?$|"
            + "^.*_(API_KEY|ACCESS_KEY|ACCESS_KEY_ID|SECRET_ACCESS_KEY|CLIENT_ID|CLIENT_SECRET|WEBHOOK_SECRET)$");
    /** The app's own secrets: any strong random value works. */
    private static final Pattern GENERATABLE = Pattern.compile("^(.*_)?(SECRET|SECRET_KEY|SECRETS_KEY|ENCRYPTION_KEY|"
            + "APP_KEY|SIGNING_KEY|SALT|PEPPER)$");
    private static final Pattern PLACEHOLDER = Pattern.compile("(?i)^(|<.*>|your[_ -].*|change[_ -]?me.*|xxx+|\\*+|"
            + "todo|replace[_ -].*|.*your_.*_here.*|secret|password|example)$");
    private static final Pattern LOCAL_ADDRESS = Pattern.compile("(?i)(localhost|127\\.0\\.0\\.1)");
    private static final Pattern ENV_LINE = Pattern.compile("^\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*?)\\s*$");
    private static final Pattern NODE_ENV_USE = Pattern.compile(
            "process\\.env(?:\\.([A-Z][A-Z0-9_]*)|\\[\\s*['\"]([A-Z][A-Z0-9_]*)['\"]\\s*\\])(\\s*(\\|\\||\\?\\?))?");
    private static final Pattern SPRING_USE = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)(:[^}]*)?}");
    private static final Pattern PYTHON_USE = Pattern.compile(
            "os\\.environ\\[\\s*['\"]([A-Z][A-Z0-9_]*)['\"]\\s*]|os\\.(?:environ\\.get|getenv)\\(\\s*['\"]([A-Z][A-Z0-9_]*)['\"]\\s*(,)?");

    private final ProjectRepository projects;
    private final ProjectEnvVarRepository variables;
    private final ProjectDatabaseRepository databases;
    private final IProjectEnvService envService;
    private final IRepositoryFiles files;
    private final SecretCipher cipher;
    private final ICurrentUserService currentUserService;
    private final Clock clock;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    public EnvRequirementsService(ProjectRepository projects, ProjectEnvVarRepository variables,
                                  ProjectDatabaseRepository databases, IProjectEnvService envService,
                                  IRepositoryFiles files, SecretCipher cipher, ICurrentUserService currentUserService,
                                  Clock clock) {
        this.projects = projects;
        this.variables = variables;
        this.databases = databases;
        this.envService = envService;
        this.files = files;
        this.cipher = cipher;
        this.currentUserService = currentUserService;
        this.clock = clock;
    }

    /** What the repository says the app needs (before looking at what is set). */
    record Needed(String name, EnvVarKind kind, boolean required, String suggestedValue, String source, String help) {
    }

    record Analysis(List<Needed> needed, List<String> notes, boolean databaseWanted) {
    }

    private record Cached(String key, Instant until, Analysis analysis) {
    }

    @Override
    public EnvRequirementsResponseDTO requirements(Long projectId) {
        ProjectMaster project = owned(projectId);
        return withStatus(projectId, analysis(project));
    }

    @Override
    public EnvRequirementsResponseDTO fillIn(Long projectId, List<String> names) {
        ProjectMaster project = owned(projectId);
        Analysis analysis = analysis(project);
        Map<String, String> current = currentValues(projectId);
        for (Needed n : analysis.needed()) {
            if (!names.contains(n.name()) || current.containsKey(n.name())) {
                continue;
            }
            String value = switch (n.kind()) {
                case SECRET -> generateSecret();
                case SETTING -> n.suggestedValue();
                case DATABASE_ALIAS -> current.get(DATABASE_ALIASES.get(n.name()));
                default -> null;
            };
            if (value != null) {
                envService.set(projectId, n.name(), value); // encrypted, validated and audited there
            }
        }
        return withStatus(projectId, analysis);
    }

    private EnvRequirementsResponseDTO withStatus(Long projectId, Analysis analysis) {
        Map<String, String> current = currentValues(projectId);
        boolean hasDatabase = databases.findByProjectId(projectId).isPresent();
        List<Item> items = new ArrayList<>();
        List<String> autoFillable = new ArrayList<>();
        int missingRequired = 0;
        for (Needed n : analysis.needed()) {
            String status = current.containsKey(n.name()) ? "SET" : n.kind() == EnvVarKind.PLATFORM ? "PROVIDED" : "MISSING";
            if ("MISSING".equals(status)) {
                if (n.required()) {
                    missingRequired++;
                }
                boolean fillable = n.kind() == EnvVarKind.SECRET || n.kind() == EnvVarKind.SETTING
                        || n.kind() == EnvVarKind.DATABASE_ALIAS && current.containsKey(DATABASE_ALIASES.get(n.name()));
                if (fillable) {
                    autoFillable.add(n.name());
                }
            }
            items.add(new Item(n.name(), n.kind(), status, n.required(), n.suggestedValue(), n.source(), n.help()));
        }
        return new EnvRequirementsResponseDTO(items, analysis.databaseWanted() && !hasDatabase, hasDatabase,
                missingRequired, autoFillable, analysis.notes());
    }

    /** Names that have a value, with the value (decrypted only when a database alias needs to copy it). */
    private Map<String, String> currentValues(Long projectId) {
        Map<String, String> values = new LinkedHashMap<>();
        for (ProjectEnvVar v : variables.findByProjectIdOrderByNameAsc(projectId)) {
            values.put(v.getName(), DATABASE.contains(v.getName()) && cipher.isConfigured()
                    ? cipher.decrypt(v.getValueEncrypted()) : "");
        }
        return values;
    }

    private Analysis analysis(ProjectMaster project) {
        String key = project.getRepositoryUrl() + "|" + project.getBranch() + "|" + project.getRootDirectory();
        Cached cached = cache.get(project.getId());
        Instant now = clock.instant();
        if (cached != null && cached.key().equals(key) && cached.until().isAfter(now)) {
            return cached.analysis();
        }
        Analysis fresh;
        try (IRepositoryFiles.Snapshot snapshot = files.open(project.getRepositoryUrl(), project.getBranch(), true)) {
            fresh = analyse(snapshot, project.getRootDirectory() == null ? "" : project.getRootDirectory());
        } catch (IRepositoryFiles.RepositoryNotReadable e) {
            fresh = new Analysis(List.of(), List.of("Couldn't read the repository: " + e.getMessage()), false);
        }
        cache.put(project.getId(), new Cached(key, now.plus(CACHE_FOR), fresh));
        return fresh;
    }

    /** Reads the app folder's files; package-visible for tests. */
    Analysis analyse(IRepositoryFiles.Snapshot repo, String folder) {
        String prefix = folder.isEmpty() ? "" : folder + "/";
        Map<String, Needed> found = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        boolean databaseWanted = false;

        // 1. deployhub.json: the author's own description wins.
        Optional<String> manifest = repo.read(prefix + "deployhub.json");
        if (manifest.isPresent()) {
            try {
                JsonNode root = JSON.readTree(manifest.get());
                for (var entry : root.path("env").properties()) {
                    String name = entry.getKey();
                    JsonNode spec = entry.getValue();
                    boolean required = spec.path("required").asBoolean(true);
                    String help = spec.path("description").asString("");
                    if ("secret".equals(spec.path("generate").asString(""))) {
                        found.put(name, new Needed(name, EnvVarKind.SECRET, required, null, "deployhub.json",
                                join(help, "DeployHub generates a strong random value.")));
                    } else if (spec.has("value")) {
                        found.put(name, new Needed(name, EnvVarKind.SETTING, required, spec.path("value").asString(),
                                "deployhub.json", join(help, "Value from deployhub.json: " + spec.path("value").asString())));
                    } else {
                        found.put(name, classify(name, null, required, "deployhub.json", help));
                    }
                }
                databaseWanted = !root.path("database").asString("").isBlank();
                notes.add("Read from deployhub.json in the repository.");
            } catch (RuntimeException e) {
                notes.add("deployhub.json isn't valid JSON, so it was skipped.");
            }
        }

        // 2. .env.example (in the app folder, else in the repository's main folder).
        Optional<String> example = Optional.empty();
        String exampleName = null;
        for (String base : folder.isEmpty() ? List.of("") : List.of(prefix, "")) {
            for (String name : ENV_EXAMPLES) {
                example = repo.read(base + name);
                if (example.isPresent()) {
                    exampleName = base + name;
                    break;
                }
            }
            if (example.isPresent()) {
                break;
            }
        }
        if (example.isPresent()) {
            String comment = "";
            for (String line : example.get().split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#")) {
                    comment = trimmed.replaceFirst("^#+\\s*", "");
                    continue;
                }
                Matcher m = ENV_LINE.matcher(line);
                if (m.matches() && !found.containsKey(m.group(1))) {
                    String value = unquote(m.group(2).replaceFirst("\\s+#.*$", ""));
                    found.put(m.group(1), classify(m.group(1), value, true, exampleName,
                            comment.length() > 120 ? "" : comment));
                }
                comment = trimmed.isEmpty() ? "" : comment;
            }
            notes.add("Found " + exampleName + ".");
        }

        // 3. The code itself: variables the app reads.
        int scanned = 0;
        for (String path : repo.paths()) {
            if (!path.startsWith(prefix) || scanned >= MAX_CODE_FILES || skipped(path)) {
                continue;
            }
            Pattern pattern = path.matches(".*\\.(js|mjs|cjs|ts|jsx|tsx)$") ? NODE_ENV_USE
                    : path.matches(".*/application[^/]*\\.(properties|ya?ml)$|^application[^/]*\\.(properties|ya?ml)$") ? SPRING_USE
                    : path.endsWith(".py") ? PYTHON_USE : null;
            if (pattern == null) {
                continue;
            }
            Optional<String> text = repo.read(path);
            if (text.isEmpty()) {
                continue;
            }
            scanned++;
            Matcher m = pattern.matcher(text.get());
            while (m.find()) {
                String name;
                boolean hasDefault;
                if (pattern == NODE_ENV_USE) {
                    name = m.group(1) != null ? m.group(1) : m.group(2);
                    hasDefault = m.group(3) != null;
                } else if (pattern == SPRING_USE) {
                    name = m.group(1);
                    hasDefault = m.group(2) != null;
                } else {
                    name = m.group(1) != null ? m.group(1) : m.group(2);
                    hasDefault = m.group(1) == null; // os.environ["X"] crashes without it; os.getenv("X") doesn't
                }
                if (name.startsWith("NODE_") && !name.equals("NODE_ENV") || name.startsWith("npm_")) {
                    continue;
                }
                Needed existing = found.get(name);
                if (existing == null) {
                    found.put(name, classify(name, null, !hasDefault, path, ""));
                } else if (!existing.required() && !hasDefault && !existing.source().equals("deployhub.json")) {
                    found.put(name, new Needed(name, existing.kind(), true, existing.suggestedValue(), existing.source(),
                            existing.help()));
                }
            }
        }
        if (found.isEmpty()) {
            notes.add("No environment variables found: the app doesn't seem to need any.");
        }
        // deployhub.json decides; otherwise a database variable the app can't start without means it wants one.
        if (manifest.isEmpty() && found.values().stream().anyMatch(n -> n.required()
                && (n.kind() == EnvVarKind.DATABASE || n.kind() == EnvVarKind.DATABASE_ALIAS))) {
            databaseWanted = true;
        }
        List<Needed> ordered = found.values().stream()
                .sorted((a, b) -> Boolean.compare(b.required(), a.required()))
                .collect(Collectors.toList());
        return new Analysis(ordered, notes, databaseWanted);
    }

    /** Decides the kind (and so how DeployHub can help) from the name and the example value. */
    static Needed classify(String name, String example, boolean required, String source, String comment) {
        String upper = name.toUpperCase(Locale.ROOT);
        String value = example == null ? "" : example;
        boolean placeholder = PLACEHOLDER.matcher(value.trim()).matches();
        if (PLATFORM.contains(upper)) {
            return new Needed(name, EnvVarKind.PLATFORM, false, null, source, "DeployHub sets this itself.");
        }
        if (DATABASE.contains(upper)) {
            return new Needed(name, EnvVarKind.DATABASE, required, null, source,
                    join(comment, "Set by Add MySQL database (on the project page)."));
        }
        if (DATABASE_ALIASES.containsKey(upper)) {
            return new Needed(name, EnvVarKind.DATABASE_ALIAS, required, null, source,
                    join(comment, "Same as the database's " + DATABASE_ALIASES.get(upper) + ": filled in from it once "
                            + "the project has a database."));
        }
        if (EXTERNAL.matcher(upper).matches()) {
            return new Needed(name, EnvVarKind.EXTERNAL, required, null, source, join(comment,
                    "A key from another service's account (e.g. your email, payment or cloud provider). Only you can get "
                            + "it; DeployHub can't make it up."));
        }
        if (GENERATABLE.matcher(upper).matches()) {
            return new Needed(name, EnvVarKind.SECRET, required, null, source,
                    join(comment, "Any strong random value works: DeployHub generates one. You never need to know it."));
        }
        if (upper.equals("NODE_ENV")) {
            return new Needed(name, EnvVarKind.SETTING, false, "production", source, "Fill in uses production.");
        }
        if (!placeholder && LOCAL_ADDRESS.matcher(value).find()) {
            return new Needed(name, EnvVarKind.LOCAL_ONLY, false, null, source, join(comment, "The example ("
                    + value + ") points at the developer's computer. Use your project's address or public link, or leave "
                    + "it out if the frontend reaches the backend through an /api route."));
        }
        if (!placeholder && !value.isBlank() && value.length() <= 200) {
            return new Needed(name, EnvVarKind.SETTING, required, value, source,
                    join(comment, "Fill in uses the example value: " + value));
        }
        return new Needed(name, EnvVarKind.UNKNOWN, required, null, source,
                join(comment, "Needed by the app; check its README for what to put here."));
    }

    private static boolean skipped(String path) {
        return path.matches("(^|.*/)(node_modules|dist|build|target|out|coverage|vendor|\\.git|\\.next|__pycache__|venv|"
                + "\\.venv|test|tests|__tests__|e2e|cypress)/.*") || path.matches(".*\\.(spec|test|min)\\.[a-z]+$")
                || path.endsWith(".d.ts");
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String join(String first, String second) {
        return first == null || first.isBlank() ? second : first + ". " + second;
    }

    private static String generateSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes); // also what DEPLOYHUB_SECRETS_KEY-style keys expect
    }

    private ProjectMaster owned(Long projectId) {
        return projects.findByIdAndOwnerId(projectId, currentUserService.currentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
    }
}
