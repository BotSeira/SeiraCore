package xyz.zcraft.seira.services.handler;

import xyz.zcraft.seira.config.AppConfig;
import xyz.zcraft.seira.config.RuntimeConfig;
import xyz.zcraft.seira.console.ConsoleCommandProcessor;
import xyz.zcraft.seira.console.ConsoleInputParser;
import xyz.zcraft.seira.util.AdminRegistry;

import java.util.Locale;
import java.util.Objects;

import static xyz.zcraft.seira.console.ConsoleCommandProcessor.blankAs;

public class ConfigHandler {
    private final RuntimeConfig runtimeConfig;
    private final AdminRegistry admins;

    public ConfigHandler(RuntimeConfig runtimeConfig, AdminRegistry admins) {
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig);
        this.admins = Objects.requireNonNull(admins);
    }

    public ConsoleCommandProcessor.ConsoleResult dispatch(ConsoleInputParser.ParsedInput input) {
        if (input.size() != 2) {
            return ConsoleCommandProcessor.ConsoleResult.failure("Usage: config <show|check|reload>");
        }
        return switch (input.value(1).toLowerCase(Locale.ROOT)) {
            case "show" -> showConfig();
            case "check" -> checkConfig();
            case "reload" -> reloadConfig();
            default -> ConsoleCommandProcessor.ConsoleResult.failure("Usage: config <show|check|reload>");
        };
    }

    private ConsoleCommandProcessor.ConsoleResult showConfig() {
        AppConfig config = runtimeConfig.current();
        String pending = runtimeConfig.pendingRestart().isEmpty()
                ? "none"
                : String.join(", ", runtimeConfig.pendingRestart());
        return ConsoleCommandProcessor.ConsoleResult.success("""
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

    private ConsoleCommandProcessor.ConsoleResult checkConfig() {
        runtimeConfig.validateSource();
        return ConsoleCommandProcessor.ConsoleResult.success("config.yml is valid. No settings were applied.");
    }

    private ConsoleCommandProcessor.ConsoleResult reloadConfig() {
        RuntimeConfig.ReloadResult result = runtimeConfig.reload();
        String applied = result.applied().isEmpty() ? "none" : String.join(", ", result.applied());
        String restart = result.restartRequired().isEmpty()
                ? "none"
                : String.join(", ", result.restartRequired());
        return ConsoleCommandProcessor.ConsoleResult.success(
                "Configuration reloaded.\nApplied online: " + applied + "\nRestart required: " + restart
        );
    }
}
