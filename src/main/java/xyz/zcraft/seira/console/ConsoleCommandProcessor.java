package xyz.zcraft.seira.console;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import xyz.zcraft.seira.binding.UserDataStore;
import xyz.zcraft.seira.bot.ProactiveMessenger;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.route.Router;
import xyz.zcraft.seira.config.AppConfig;
import xyz.zcraft.seira.config.RuntimeConfig;
import xyz.zcraft.seira.security.AdminRegistry;
import xyz.zcraft.seira.services.BotStat;
import xyz.zcraft.seira.watch.WatchView;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class ConsoleCommandProcessor {
    private static final Logger LOG = LogManager.getLogger(ConsoleCommandProcessor.class);
    private static final int QUERY_ROW_LIMIT = 50;
    private static final int MAX_CELL_LENGTH = 120;
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final List<String> ROOT_COMMANDS = List.of(
            "help", "status", "metrics", "system", "config", "admin", "data", "send",
            "watch", "cache", "gateway", "log", "inspect", "stop"
    );
    private static final Map<String, List<String>> SUBCOMMANDS = Map.of(
            "config", List.of("show", "check", "reload"),
            "admin", List.of("list", "check", "add", "remove"),
            "data", List.of("stats", "tables", "describe", "query"),
            "send", List.of("group", "private"),
            "watch", List.of("status", "list", "poll", "remove", "clear"),
            "cache", List.of("query", "delete", "get", "fetch"),
            "gateway", List.of("status", "reconnect"),
            "log", List.of("show", "level")
    );

    private final RuntimeConfig runtimeConfig;
    private final AdminRegistry admins;
    private final ConsoleDataAccess dataAccess;
    private final ProactiveMessenger messenger;
    private final ConsoleRuntimeControl runtimeControl;

    public ConsoleCommandProcessor(
            RuntimeConfig runtimeConfig,
            AdminRegistry admins,
            ConsoleDataAccess dataAccess,
            ProactiveMessenger messenger,
            ConsoleRuntimeControl runtimeControl
    ) {
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig);
        this.admins = Objects.requireNonNull(admins);
        this.dataAccess = Objects.requireNonNull(dataAccess);
        this.messenger = Objects.requireNonNull(messenger);
        this.runtimeControl = Objects.requireNonNull(runtimeControl);
    }

    public ConsoleResult execute(String line) {
        try {
            ConsoleInputParser.ParsedInput input = ConsoleInputParser.parse(line);
            if (input.size() == 0) {
                return ConsoleResult.success("");
            }
            return switch (input.value(0).toLowerCase(Locale.ROOT)) {
                case "help", "?" -> help(input);
                case "status", "stat" -> exact(input, 1, this::status, "Usage: status");
                case "metrics" -> exact(input, 1, this::metrics, "Usage: metrics");
                case "system" -> exact(input, 1, this::system, "Usage: system");
                case "config" -> config(input);
                case "admin" -> admin(input);
                case "data" -> data(input);
                case "send" -> send(input);
                case "watch" -> watch(input);
                case "cache" -> cache(input);
                case "gateway" -> gateway(input);
                case "log" -> log(input);
                case "inspect" -> exact(input, 1, this::inspect, "Usage: inspect");
                case "stop", "shutdown", "exit", "quit" -> stop(input);
                default -> ConsoleResult.failure(
                        "Unknown console command: " + input.value(0) + ". Run 'help' to list commands."
                );
            };
        } catch (IllegalArgumentException e) {
            return ConsoleResult.failure(e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("Console command failed", e);
            return ConsoleResult.failure("Command failed: " + rootMessage(e));
        }
    }

    static List<String> rootCommands() {
        return ROOT_COMMANDS;
    }

    static List<String> subcommands(String command) {
        return SUBCOMMANDS.getOrDefault(command.toLowerCase(Locale.ROOT), List.of());
    }

    private ConsoleResult help(ConsoleInputParser.ParsedInput input) {
        if (input.size() > 2) {
            return ConsoleResult.failure("Usage: help [command]");
        }
        if (input.size() == 1) {
            return ConsoleResult.success("""
                    SeiraCore administration console
                      help [command]                     Show all commands or detailed help
                      status                             Show a concise service health summary
                      metrics                            Show command, replay, and uptime counters
                      system                             Show JVM, OS, thread, and memory information
                      config <show|check|reload>         Inspect or reload config.yml safely
                      admin <list|check|add|remove>      Manage bot administrators
                      data <stats|tables|describe|query> Inspect the SQLite database (read-only)
                      send <group|private> <id> <text>   Send a proactive text message
                      watch <status|list|poll|remove|clear>
                                                         Inspect and control score watches
                      cache <query|delete|get|fetch> <type> <id>
                                                         Inspect/delete cache through oStella and workers
                      gateway <status|reconnect>         Inspect or reconnect the QQ gateway
                      log <show|level>                   Inspect or change the runtime log level
                      inspect                            Show the last dispatched message context
                      stop confirm                       Gracefully stop SeiraCore

                    Aliases: ? (help), stat (status), shutdown/exit/quit (stop)
                    Use 'help <command>' for examples and safety notes.
                    """.stripTrailing());
        }
        String topic = input.value(1).toLowerCase(Locale.ROOT);
        String detail = switch (topic) {
            case "help", "?" -> "help [command]\nShows the command list or help for one command.";
            case "status", "stat" -> "status\nShows bot, gateway, token, watch, database, and memory health.";
            case "metrics" -> "metrics\nShows persisted totals and command counts for recent time windows.";
            case "system" -> "system\nShows the SeiraCore version and local JVM/OS resource information.";
            case "config" -> """
                    config show
                    config check
                    config reload
                    'show' redacts all credentials. 'check' validates config.yml without applying it.
                    'reload' applies supported settings and reports changes that require a restart.""";
            case "admin" -> """
                    admin list
                    admin check <openid>
                    admin add <openid>
                    admin remove <openid>
                    Administrators declared in config.yml cannot be removed from the console.""";
            case "data" -> """
                    data stats
                    data tables
                    data describe <table>
                    data query <SELECT|WITH|PRAGMA|EXPLAIN statement>
                    Queries are read-only, limited to 50 rows, and time-bounded by the data layer.""";
            case "send" -> """
                    send group <group-id> <message>
                    send private <user-id> <message>
                    Quote text when it contains significant leading/trailing spaces. Maximum: 4000 characters.""";
            case "watch" -> """
                    watch status
                    watch list [group-id]
                    watch poll
                    watch remove <group-id> <osu-uid>
                    watch clear <group-id> confirm
                    Polling runs in the watch worker. Clearing a group requires explicit confirmation.""";
            case "cache" -> """
                    cache <query|delete|get|fetch> <score|beatmap|beatmapset|replay> <id>
                    query reports presence at oStella and every osuRenderer worker.
                    get includes metadata; fetch populates oStella and downstream workers; delete removes every reachable copy.""";
            case "gateway" -> """
                    gateway status
                    gateway reconnect
                    Reconnect closes the current session; the normal connection loop opens a new one.""";
            case "log" -> """
                    log show
                    log level <trace|debug|info|warn|error>
                    The change lasts until restart or a config reload changes debugMode.""";
            case "inspect" -> "inspect\nShows IDs and parsed command data from the last dispatched QQ message.";
            case "stop", "shutdown", "exit", "quit" -> "stop confirm\nGracefully stops the gateway and all application services.";
            default -> null;
        };
        return detail == null
                ? ConsoleResult.failure("No help topic named '" + input.value(1) + "'.")
                : ConsoleResult.success(detail.stripTrailing());
    }

    private ConsoleResult status() {
        ConsoleRuntimeControl.RuntimeStatus status = runtimeControl.status();
        long usedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        return ConsoleResult.success("""
                SeiraCore %s
                  Bot: %s
                  QQ gateway: %s
                  Access token: %s
                  Score watch worker: %s (%d groups, %d tasks, every %s)
                  Database: %d bound users, %d known groups
                  Uptime: %s
                  Heap: %s used / %s max
                """.formatted(
                version(),
                status.running() ? "RUNNING" : "STOPPED",
                status.gatewayConnected() ? "CONNECTED" : status.running() ? "CONNECTING" : "DISCONNECTED",
                status.tokenValid() ? "VALID" : "UNAVAILABLE",
                status.watchServiceRunning() ? "RUNNING" : "STOPPED",
                status.watchedGroups(),
                status.watchTasks(),
                formatDuration(status.watchPollInterval()),
                dataAccess.boundUsers(),
                dataAccess.groups(),
                formatDuration(Duration.ofMillis(BotStat.getCurrentUptime())),
                formatBytes(usedMemory),
                formatBytes(Runtime.getRuntime().maxMemory())
        ).stripTrailing());
    }

    private ConsoleResult metrics() {
        return ConsoleResult.success("""
                Runtime metrics
                  Commands: %d total; %d / 1 min; %d / 5 min; %d / 15 min; %d / 60 min
                  Replays: %d total
                  Current uptime: %s
                  Lifetime uptime: %s
                """.formatted(
                BotStat.getTotalCommands(),
                BotStat.getCommandCountFor(1),
                BotStat.getCommandCountFor(5),
                BotStat.getCommandCountFor(15),
                BotStat.getCommandCountFor(60),
                BotStat.getTotalReplays(),
                formatDuration(Duration.ofMillis(BotStat.getCurrentUptime())),
                formatDuration(Duration.ofMillis(BotStat.getTotalUptime()))
        ).stripTrailing());
    }

    private ConsoleResult system() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        return ConsoleResult.success("""
                System information
                  SeiraCore: %s
                  Java: %s (%s)
                  OS: %s %s
                  Processors: %d
                  Threads: %d
                  Heap: %s used / %s committed / %s max
                """.formatted(
                version(),
                System.getProperty("java.version", "unknown"),
                System.getProperty("java.vendor", "unknown"),
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.arch", "unknown"),
                runtime.availableProcessors(),
                Thread.getAllStackTraces().size(),
                formatBytes(used),
                formatBytes(runtime.totalMemory()),
                formatBytes(runtime.maxMemory())
        ).stripTrailing());
    }

    private ConsoleResult config(ConsoleInputParser.ParsedInput input) {
        if (input.size() != 2) {
            return ConsoleResult.failure("Usage: config <show|check|reload>");
        }
        return switch (input.value(1).toLowerCase(Locale.ROOT)) {
            case "show" -> showConfig();
            case "check" -> checkConfig();
            case "reload" -> reloadConfig();
            default -> ConsoleResult.failure("Usage: config <show|check|reload>");
        };
    }

    private ConsoleResult showConfig() {
        AppConfig config = runtimeConfig.current();
        String pending = runtimeConfig.pendingRestart().isEmpty()
                ? "none"
                : String.join(", ", runtimeConfig.pendingRestart());
        return ConsoleResult.success("""
                Effective configuration (credentials redacted)
                  seira.sqlitePath = %s
                  seira.directUrl = %s
                  seira.queueMessageInGroup = %s
                  seira.watchIntervalMinutes = %d
                  seira.debugMode = %s
                  seira.administrators = %d
                  ostella.endpoint = %s
                  binding.listener = 0.0.0.0:%d%s
                  binding.clientId = %d
                  qq.selfId = %s
                  qq.appId = %s
                  cos.region = %s
                  cos.bucket = %s
                  cos.baseUrl = %s
                  Pending restart changes = %s
                """.formatted(
                config.seira().sqlitePath(),
                config.seira().directUrl(),
                config.seira().queueMessageInGroup(),
                config.seira().effectiveWatchIntervalMinutes(),
                config.seira().debugMode(),
                admins.list().size(),
                config.ostella().endpoint(),
                config.binding().listenPort(),
                config.binding().listenPath(),
                config.binding().clientId(),
                config.qq().selfId(),
                config.qq().appId(),
                config.cos().region(),
                config.cos().bucket(),
                blankAs(config.cos().baseUrl(), "not set"),
                pending
        ).stripTrailing());
    }

    private ConsoleResult checkConfig() {
        runtimeConfig.validateSource();
        return ConsoleResult.success("config.yml is valid. No settings were applied.");
    }

    private ConsoleResult reloadConfig() {
        RuntimeConfig.ReloadResult result = runtimeConfig.reload();
        String applied = result.applied().isEmpty() ? "none" : String.join(", ", result.applied());
        String restart = result.restartRequired().isEmpty()
                ? "none"
                : String.join(", ", result.restartRequired());
        return ConsoleResult.success(
                "Configuration reloaded.\nApplied online: " + applied + "\nRestart required: " + restart
        );
    }

    private ConsoleResult admin(ConsoleInputParser.ParsedInput input) {
        if (input.size() < 2) {
            return ConsoleResult.failure("Usage: admin <list|check|add|remove> [openid]");
        }
        return switch (input.value(1).toLowerCase(Locale.ROOT)) {
            case "list" -> input.size() == 2 ? listAdmins() : ConsoleResult.failure("Usage: admin list");
            case "check" -> input.size() == 3 ? checkAdmin(input.value(2))
                    : ConsoleResult.failure("Usage: admin check <openid>");
            case "add" -> input.size() == 3 ? addAdmin(input.value(2))
                    : ConsoleResult.failure("Usage: admin add <openid>");
            case "remove" -> input.size() == 3 ? removeAdmin(input.value(2))
                    : ConsoleResult.failure("Usage: admin remove <openid>");
            default -> ConsoleResult.failure("Usage: admin <list|check|add|remove> [openid]");
        };
    }

    private ConsoleResult listAdmins() {
        List<AdminRegistry.AdminView> values = admins.list();
        if (values.isEmpty()) {
            return ConsoleResult.success("No administrators are configured.");
        }
        StringBuilder output = new StringBuilder("Administrators (" + values.size() + "):");
        values.forEach(admin -> output.append("\n  ")
                .append(admin.openId())
                .append(" [")
                .append(admin.configured() && admin.persisted() ? "config + console"
                        : admin.configured() ? "config" : "console")
                .append(']'));
        return ConsoleResult.success(output.toString());
    }

    private ConsoleResult checkAdmin(String openId) {
        return ConsoleResult.success(openId + (admins.isAdmin(openId)
                ? " is an administrator."
                : " is not an administrator."));
    }

    private ConsoleResult addAdmin(String openId) {
        AdminRegistry.AddResult result = admins.add(openId);
        return result == AdminRegistry.AddResult.ADDED
                ? ConsoleResult.success("Administrator added and persisted: " + openId)
                : ConsoleResult.success("Already an administrator: " + openId);
    }

    private ConsoleResult removeAdmin(String openId) {
        return switch (admins.remove(openId)) {
            case REMOVED -> ConsoleResult.success("Console administrator removed: " + openId);
            case CONFIGURED -> ConsoleResult.failure(
                    "This administrator comes from config.yml. Edit the file and run 'config reload'."
            );
            case NOT_FOUND -> ConsoleResult.failure("Console administrator not found: " + openId);
        };
    }

    private ConsoleResult data(ConsoleInputParser.ParsedInput input) {
        if (input.size() < 2) {
            return ConsoleResult.failure("Usage: data <stats|tables|describe|query> [arguments]");
        }
        return switch (input.value(1).toLowerCase(Locale.ROOT)) {
            case "stats" -> input.size() == 2
                    ? ConsoleResult.success("Bound users: %d\nKnown groups: %d".formatted(
                    dataAccess.boundUsers(), dataAccess.groups()))
                    : ConsoleResult.failure("Usage: data stats");
            case "tables" -> input.size() == 2
                    ? ConsoleResult.success(formatQueryResult(dataAccess.query(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                    QUERY_ROW_LIMIT
            )))
                    : ConsoleResult.failure("Usage: data tables");
            case "describe" -> describeTable(input);
            case "query" -> query(input.remainderAfterTokens(2));
            default -> ConsoleResult.failure("Usage: data <stats|tables|describe|query> [arguments]");
        };
    }

    private ConsoleResult describeTable(ConsoleInputParser.ParsedInput input) {
        if (input.size() != 3 || !SQL_IDENTIFIER.matcher(input.value(2)).matches()) {
            return ConsoleResult.failure("Usage: data describe <table>");
        }
        return ConsoleResult.success(formatQueryResult(
                dataAccess.query("PRAGMA table_info(\"" + input.value(2) + "\")", QUERY_ROW_LIMIT)
        ));
    }

    private ConsoleResult query(String sql) {
        if (sql.isBlank()) {
            return ConsoleResult.failure("Usage: data query <read-only SQL>");
        }
        return ConsoleResult.success(formatQueryResult(dataAccess.query(sql, QUERY_ROW_LIMIT)));
    }

    private ConsoleResult send(ConsoleInputParser.ParsedInput input) {
        if (input.size() < 4) {
            return ConsoleResult.failure("Usage: send <group|private> <target-id> <message>");
        }
        String targetType = input.value(1).toLowerCase(Locale.ROOT);
        String targetId = input.value(2);
        String content = String.join(" ", input.valuesFrom(3));
        if (targetId.isBlank() || content.isBlank()) {
            return ConsoleResult.failure("Target ID and message must not be blank.");
        }
        if (targetId.length() > 256 || targetId.chars().anyMatch(Character::isWhitespace)
                || targetId.chars().anyMatch(Character::isISOControl)) {
            return ConsoleResult.failure("Target ID contains invalid characters.");
        }
        if (content.length() > 4_000) {
            return ConsoleResult.failure("Proactive messages cannot exceed 4000 characters.");
        }

        boolean sent = switch (targetType) {
            case "group" -> messenger.sendGroupText(targetId, content);
            case "private" -> messenger.sendPrivateText(targetId, content);
            default -> throw new IllegalArgumentException("Message type must be 'group' or 'private'.");
        };
        return sent
                ? ConsoleResult.success("Proactive message sent successfully.")
                : ConsoleResult.failure("Message delivery failed. Check the target ID, token, and logs.");
    }

    private ConsoleResult watch(ConsoleInputParser.ParsedInput input) {
        if (input.size() < 2) {
            return ConsoleResult.failure("Usage: watch <status|list|poll|remove|clear> [arguments]");
        }
        return switch (input.value(1).toLowerCase(Locale.ROOT)) {
            case "status" -> input.size() == 2 ? watchStatus() : ConsoleResult.failure("Usage: watch status");
            case "list" -> input.size() <= 3 ? listWatches(input) : ConsoleResult.failure("Usage: watch list [group-id]");
            case "poll" -> input.size() == 2 ? pollWatches() : ConsoleResult.failure("Usage: watch poll");
            case "remove" -> removeWatch(input);
            case "clear" -> clearWatches(input);
            default -> ConsoleResult.failure("Usage: watch <status|list|poll|remove|clear> [arguments]");
        };
    }

    private ConsoleResult watchStatus() {
        ConsoleRuntimeControl.RuntimeStatus status = runtimeControl.status();
        return ConsoleResult.success("""
                Score watch service: %s
                Watched groups: %d
                Active tasks: %d
                Poll interval: %s
                """.formatted(
                status.watchServiceRunning() ? "RUNNING" : "STOPPED",
                status.watchedGroups(),
                status.watchTasks(),
                formatDuration(status.watchPollInterval())
        ).stripTrailing());
    }

    private ConsoleResult listWatches(ConsoleInputParser.ParsedInput input) {
        if (input.size() == 3) {
            List<WatchView> watches = runtimeControl.listWatches(input.value(2));
            return ConsoleResult.success(formatWatches(input.value(2), watches));
        }
        List<ConsoleRuntimeControl.GroupWatches> groups = runtimeControl.listWatches();
        if (groups.isEmpty()) {
            return ConsoleResult.success("No active score watches.");
        }
        StringBuilder output = new StringBuilder("Active score watches:");
        groups.forEach(group -> output.append('\n').append(formatWatches(group.groupId(), group.watches())));
        return ConsoleResult.success(output.toString());
    }

    private ConsoleResult pollWatches() {
        return runtimeControl.requestWatchPoll()
                ? ConsoleResult.success("A score watch poll was queued.")
                : ConsoleResult.failure("The score watch service is not running.");
    }

    private ConsoleResult removeWatch(ConsoleInputParser.ParsedInput input) {
        if (input.size() != 4) {
            return ConsoleResult.failure("Usage: watch remove <group-id> <osu-uid>");
        }
        long userId = positiveLong(input.value(3), "osu-uid");
        WatchView removed = runtimeControl.removeWatch(input.value(2), userId);
        return removed == null
                ? ConsoleResult.failure("No matching watch was found in that group.")
                : ConsoleResult.success("Removed watch for " + removed.target().username()
                + " (UID " + removed.target().userId() + ").");
    }

    private ConsoleResult clearWatches(ConsoleInputParser.ParsedInput input) {
        if (input.size() != 4 || !"confirm".equalsIgnoreCase(input.value(3))) {
            return ConsoleResult.failure("Usage: watch clear <group-id> confirm");
        }
        int removed = runtimeControl.clearWatches(input.value(2));
        return ConsoleResult.success("Cleared " + removed + " watch task(s) from group " + input.value(2) + ".");
    }

    private ConsoleResult gateway(ConsoleInputParser.ParsedInput input) {
        if (input.size() != 2) {
            return ConsoleResult.failure("Usage: gateway <status|reconnect>");
        }
        return switch (input.value(1).toLowerCase(Locale.ROOT)) {
            case "status" -> {
                ConsoleRuntimeControl.RuntimeStatus status = runtimeControl.status();
                yield ConsoleResult.success("QQ gateway: " + (status.gatewayConnected()
                        ? "CONNECTED"
                        : status.running() ? "CONNECTING" : "DISCONNECTED"));
            }
            case "reconnect" -> runtimeControl.reconnectGateway()
                    ? ConsoleResult.success("Gateway reconnect requested.")
                    : ConsoleResult.failure("No active gateway session is available to reconnect.");
            default -> ConsoleResult.failure("Usage: gateway <status|reconnect>");
        };
    }

    private ConsoleResult cache(ConsoleInputParser.ParsedInput input) {
        if (input.size() != 4) {
            return ConsoleResult.failure(
                    "Usage: cache <query|delete|get|fetch> <score|beatmap|beatmapset|replay> <id>"
            );
        }
        String operation = input.value(1).toLowerCase(Locale.ROOT);
        if (!List.of("query", "delete", "get", "fetch").contains(operation)) {
            return ConsoleResult.failure("Cache operation must be query, delete, get, or fetch.");
        }
        String type = input.value(2).toUpperCase(Locale.ROOT);
        if (!List.of("SCORE", "BEATMAP", "BEATMAPSET", "REPLAY").contains(type)) {
            return ConsoleResult.failure("Cache type must be score, beatmap, beatmapset, or replay.");
        }
        long id = positiveLong(input.value(3), "id");
        return ConsoleResult.success(formatCacheControl(runtimeControl.controlCache(operation, type, id)));
    }

    private static String formatCacheControl(ConsoleRuntimeControl.CacheControlResult result) {
        StringBuilder output = new StringBuilder(result.operation().toLowerCase(Locale.ROOT))
                .append(' ').append(result.type().toLowerCase(Locale.ROOT)).append(' ').append(result.id());
        for (ConsoleRuntimeControl.CacheNodeResult node : result.nodes()) {
            output.append("\n  ").append(node.node()).append(": ").append(node.status());
            if (node.path() != null) output.append(" | path=").append(node.path());
            if (node.sizeBytes() != null) output.append(" | size=").append(formatBytes(node.sizeBytes()));
            if (node.modifiedAt() != null) output.append(" | modified=").append(node.modifiedAt());
            if (node.message() != null) output.append(" | ").append(node.message());
        }
        return output.toString();
    }

    private ConsoleResult log(ConsoleInputParser.ParsedInput input) {
        if (input.size() == 2 && "show".equalsIgnoreCase(input.value(1))) {
            return ConsoleResult.success("Root log level: " + LogManager.getRootLogger().getLevel());
        }
        if (input.size() == 3 && "level".equalsIgnoreCase(input.value(1))) {
            Level level = switch (input.value(2).toLowerCase(Locale.ROOT)) {
                case "trace" -> Level.TRACE;
                case "debug" -> Level.DEBUG;
                case "info" -> Level.INFO;
                case "warn" -> Level.WARN;
                case "error" -> Level.ERROR;
                default -> null;
            };
            if (level == null) {
                return ConsoleResult.failure("Log level must be trace, debug, info, warn, or error.");
            }
            Configurator.setRootLevel(level);
            return ConsoleResult.success("Root log level changed to " + level + ".");
        }
        return ConsoleResult.failure("Usage: log <show|level <trace|debug|info|warn|error>>");
    }

    private ConsoleResult inspect() {
        Context lastContext = Router.getLastContext();
        if (lastContext == null) {
            return ConsoleResult.success("No message context has been dispatched yet.");
        }
        return ConsoleResult.success("""
                Last dispatched context
                  Sender: %s
                  Group: %s
                  Message: %s
                  Command: %s
                  Arguments: %s
                  Query: %s
                """.formatted(
                blankAs(lastContext.senderUserId(), "private/unknown"),
                blankAs(lastContext.groupId(), "private"),
                blankAs(lastContext.messageId(), "unknown"),
                lastContext.command(),
                String.join(" ", lastContext.args()),
                lastContext.query()
        ).stripTrailing());
    }

    private ConsoleResult stop(ConsoleInputParser.ParsedInput input) {
        if (input.size() != 2 || !"confirm".equalsIgnoreCase(input.value(1))) {
            return ConsoleResult.failure("Usage: stop confirm");
        }
        CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(runtimeControl::requestStop);
        return ConsoleResult.success("Graceful shutdown requested.");
    }

    private static ConsoleResult exact(
            ConsoleInputParser.ParsedInput input,
            int size,
            java.util.function.Supplier<ConsoleResult> action,
            String usage
    ) {
        return input.size() == size ? action.get() : ConsoleResult.failure(usage);
    }

    private static String formatWatches(String groupId, List<WatchView> watches) {
        if (watches.isEmpty()) {
            return "Group " + groupId + ": no active watches.";
        }
        StringBuilder output = new StringBuilder("Group " + groupId + " (" + watches.size() + "):");
        watches.forEach(watch -> output.append("\n  ")
                .append(watch.target().username())
                .append(" | UID ").append(watch.target().userId())
                .append(" | QQ ").append(watch.target().qqOpenId())
                .append(" | remaining ").append(formatDuration(watch.remaining())));
        return output.toString();
    }

    private static String formatQueryResult(UserDataStore.QueryResult result) {
        StringBuilder output = new StringBuilder();
        output.append(result.columns().stream().map(ConsoleCommandProcessor::cell).reduce(
                (left, right) -> left + " | " + right
        ).orElse("(no columns)"));
        for (List<String> row : result.rows()) {
            output.append('\n').append(row.stream().map(ConsoleCommandProcessor::cell).reduce(
                    (left, right) -> left + " | " + right
            ).orElse(""));
        }
        output.append("\nReturned ").append(result.rows().size()).append(" row(s)");
        if (result.truncated()) {
            output.append(" (result truncated)");
        }
        return output.toString();
    }

    private static String cell(String value) {
        if (value == null) {
            return "NULL";
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return normalized.length() <= MAX_CELL_LENGTH
                ? normalized
                : normalized.substring(0, MAX_CELL_LENGTH - 1) + "...";
    }

    private static long positiveLong(String value, String name) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException(name + " must be a positive integer.");
    }

    private static String formatDuration(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return "unknown";
        }
        long seconds = duration.toSeconds();
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        long remainingSeconds = seconds % 60;
        if (days > 0) {
            return "%dd %02dh %02dm".formatted(days, hours, minutes);
        }
        if (hours > 0) {
            return "%dh %02dm %02ds".formatted(hours, minutes, remainingSeconds);
        }
        if (minutes > 0) {
            return "%dm %02ds".formatted(minutes, remainingSeconds);
        }
        return remainingSeconds + "s";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String blankAs(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String version() {
        Package currentPackage = ConsoleCommandProcessor.class.getPackage();
        String implementation = currentPackage == null ? null : currentPackage.getImplementationVersion();
        if (implementation != null && !implementation.isBlank()) {
            return implementation;
        }
        Properties properties = new Properties();
        try (InputStream input = ConsoleCommandProcessor.class.getResourceAsStream("/version.properties")) {
            if (input != null) {
                properties.load(input);
                return properties.getProperty("version", "development");
            }
        } catch (IOException ignored) {
        }
        return "development";
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }

    public record ConsoleResult(boolean success, String message) {
        static ConsoleResult success(String message) {
            return new ConsoleResult(true, message);
        }

        static ConsoleResult failure(String message) {
            return new ConsoleResult(false, message);
        }
    }
}
