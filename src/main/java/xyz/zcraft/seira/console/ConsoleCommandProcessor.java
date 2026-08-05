package xyz.zcraft.seira.console;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.binding.UserDataStore;
import xyz.zcraft.seira.bot.ProactiveMessenger;
import xyz.zcraft.seira.config.AppConfig;
import xyz.zcraft.seira.config.RuntimeConfig;
import xyz.zcraft.seira.security.AdminRegistry;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ConsoleCommandProcessor {
    private static final Logger LOG = LogManager.getLogger(ConsoleCommandProcessor.class);
    private static final int QUERY_ROW_LIMIT = 50;
    private static final int MAX_CELL_LENGTH = 120;

    private final RuntimeConfig runtimeConfig;
    private final AdminRegistry admins;
    private final ConsoleDataAccess dataAccess;
    private final ProactiveMessenger messenger;

    public ConsoleCommandProcessor(
            RuntimeConfig runtimeConfig,
            AdminRegistry admins,
            ConsoleDataAccess dataAccess,
            ProactiveMessenger messenger
    ) {
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig);
        this.admins = Objects.requireNonNull(admins);
        this.dataAccess = Objects.requireNonNull(dataAccess);
        this.messenger = Objects.requireNonNull(messenger);
    }

    public ConsoleResult execute(String line) {
        try {
            ConsoleInputParser.ParsedInput input = ConsoleInputParser.parse(line);
            if (input.size() == 0) {
                return ConsoleResult.success("");
            }
            return switch (input.value(0).toLowerCase(Locale.ROOT)) {
                case "help", "?" -> help();
                case "config" -> config(input);
                case "admin" -> admin(input);
                case "data" -> data(input);
                case "send" -> send(input);
                case "stop" -> stop();
                default -> ConsoleResult.failure("未知控制台指令。输入 help 查看帮助。");
            };
        } catch (IllegalArgumentException e) {
            return ConsoleResult.failure(e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("Console command failed", e);
            String detail = rootMessage(e);
            return ConsoleResult.failure("执行失败：" + detail);
        }
    }

    private ConsoleResult config(ConsoleInputParser.ParsedInput input) {
        if (input.size() != 2) {
            return ConsoleResult.failure("用法：config <show|reload>");
        }
        return switch (input.value(1).toLowerCase(Locale.ROOT)) {
            case "show" -> showConfig();
            case "reload" -> reloadConfig();
            default -> ConsoleResult.failure("用法：config <show|reload>");
        };
    }

    private ConsoleResult showConfig() {
        AppConfig config = runtimeConfig.current();
        String restart = runtimeConfig.pendingRestart().isEmpty()
                ? "无"
                : String.join(", ", runtimeConfig.pendingRestart());
        return ConsoleResult.success("""
                当前可热更新配置：
                  debugMode = %s
                  queueMessageInGroup = %s
                  directUrl = %s
                  administrators = %d
                等待重启生效：%s
                """.formatted(
                config.seira().debugMode(),
                config.seira().queueMessageInGroup(),
                config.seira().directUrl(),
                admins.list().size(),
                restart
        ).stripTrailing());
    }

    private ConsoleResult reloadConfig() {
        RuntimeConfig.ReloadResult result = runtimeConfig.reload();
        String applied = result.applied().isEmpty() ? "无变化" : String.join(", ", result.applied());
        String restart = result.restartRequired().isEmpty()
                ? "无"
                : String.join(", ", result.restartRequired());
        return ConsoleResult.success("配置已重新读取。\n已在线生效：" + applied + "\n需重启生效：" + restart);
    }

    private ConsoleResult stop() {
        Runtime.getRuntime().exit(0);
        return ConsoleResult.success("正在关闭");
    }

    private ConsoleResult admin(ConsoleInputParser.ParsedInput input) {
        if (input.size() < 2) {
            return ConsoleResult.failure("用法：admin <add|remove|list> [openid]");
        }
        return switch (input.value(1).toLowerCase(Locale.ROOT)) {
            case "list" -> input.size() == 2
                    ? listAdmins()
                    : ConsoleResult.failure("用法：admin list");
            case "add" -> input.size() == 3
                    ? addAdmin(input.value(2))
                    : ConsoleResult.failure("用法：admin add <openid>");
            case "remove" -> input.size() == 3
                    ? removeAdmin(input.value(2))
                    : ConsoleResult.failure("用法：admin remove <openid>");
            default -> ConsoleResult.failure("用法：admin <add|remove|list> [openid]");
        };
    }

    private ConsoleResult listAdmins() {
        List<AdminRegistry.AdminView> values = admins.list();
        if (values.isEmpty()) {
            return ConsoleResult.success("当前没有管理员。");
        }
        StringBuilder output = new StringBuilder("管理员列表：");
        values.forEach(admin -> output.append("\n  ")
                .append(admin.openId())
                .append(" [")
                .append(admin.configured() && admin.persisted() ? "配置+控制台"
                        : admin.configured() ? "配置" : "控制台")
                .append(']'));
        return ConsoleResult.success(output.toString());
    }

    private ConsoleResult addAdmin(String openId) {
        AdminRegistry.AddResult result = admins.add(openId);
        return result == AdminRegistry.AddResult.ADDED
                ? ConsoleResult.success("管理员已添加并持久化：" + openId)
                : ConsoleResult.success("该用户已经是管理员：" + openId);
    }

    private ConsoleResult removeAdmin(String openId) {
        return switch (admins.remove(openId)) {
            case REMOVED -> ConsoleResult.success("控制台管理员已移除：" + openId);
            case CONFIGURED -> ConsoleResult.failure("该管理员来自 config.yml，请修改配置后执行 config reload。");
            case NOT_FOUND -> ConsoleResult.failure("未找到该控制台管理员：" + openId);
        };
    }

    private ConsoleResult data(ConsoleInputParser.ParsedInput input) {
        if (input.size() < 2) {
            return ConsoleResult.failure("用法：data <stats|query> [SQL]");
        }
        return switch (input.value(1).toLowerCase(Locale.ROOT)) {
            case "stats" -> input.size() == 2
                    ? ConsoleResult.success("已绑定用户：%d\n已记录群聊：%d".formatted(
                    dataAccess.boundUsers(), dataAccess.groups()))
                    : ConsoleResult.failure("用法：data stats");
            case "query" -> query(input.remainderAfterTokens(2));
            default -> ConsoleResult.failure("用法：data <stats|query> [SQL]");
        };
    }

    private ConsoleResult query(String sql) {
        if (sql.isBlank()) {
            return ConsoleResult.failure("用法：data query <只读 SQL>");
        }
        return ConsoleResult.success(formatQueryResult(dataAccess.query(sql, QUERY_ROW_LIMIT)));
    }

    private ConsoleResult send(ConsoleInputParser.ParsedInput input) {
        if (input.size() < 4) {
            return ConsoleResult.failure("用法：send <group|private> <目标ID> <消息>");
        }
        String targetType = input.value(1).toLowerCase(Locale.ROOT);
        String targetId = input.value(2);
        String content = String.join(" ", input.valuesFrom(3));
        if (targetId.isBlank() || content.isBlank()) {
            return ConsoleResult.failure("目标 ID 和消息均不能为空。");
        }
        if (targetId.length() > 256 || targetId.chars().anyMatch(Character::isWhitespace)) {
            return ConsoleResult.failure("目标 ID 格式无效。");
        }
        if (content.length() > 4_000) {
            return ConsoleResult.failure("主动消息不能超过 4000 个字符。");
        }

        boolean sent = switch (targetType) {
            case "group" -> messenger.sendGroupText(targetId, content);
            case "private" -> messenger.sendPrivateText(targetId, content);
            default -> throw new IllegalArgumentException("消息类型必须是 group 或 private");
        };
        return sent
                ? ConsoleResult.success("主动消息发送成功。")
                : ConsoleResult.failure("主动消息发送失败，请检查目标 ID、令牌状态和日志。");
    }

    private static ConsoleResult help() {
        return ConsoleResult.success("""
                SeiraCore 控制台指令：
                  help                              显示帮助
                  config show                       查看可热更新配置状态
                  config reload                     重新读取 config.yml
                  admin list                        列出管理员
                  admin add <openid>                添加并持久化管理员
                  admin remove <openid>             移除控制台添加的管理员
                  data stats                        查看数据统计
                  data query <SQL>                  执行只读查询，最多返回 50 行
                  send group <group-id> <消息>       发送群主动消息
                  send private <user-id> <消息>      发送私聊主动消息
                """.stripTrailing());
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
        output.append("\n返回 ").append(result.rows().size()).append(" 行");
        if (result.truncated()) {
            output.append("（结果已截断）");
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
                : normalized.substring(0, MAX_CELL_LENGTH - 1) + "…";
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
