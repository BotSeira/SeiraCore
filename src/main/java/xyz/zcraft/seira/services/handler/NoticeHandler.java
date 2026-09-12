package xyz.zcraft.seira.services.handler;

import xyz.zcraft.seira.console.ConsoleCommandProcessor;
import xyz.zcraft.seira.console.ConsoleInputParser;
import xyz.zcraft.seira.data.Notice;
import xyz.zcraft.seira.services.NoticeStore;

import java.util.Locale;
import java.util.Set;

public class NoticeHandler {

    public ConsoleCommandProcessor.ConsoleResult dispatch(
            ConsoleInputParser.ParsedInput input
    ) {
        if (input.size() < 2) {
            return usage();
        }

        return switch (input.value(1).toLowerCase(Locale.ROOT)) {
            case "new" -> newNotice(input);
            case "reload" -> reloadNotice(input);
            case "publish" -> publishNotice(input);
            case "revoke" -> revokeNotice(input);
            case "list" -> listNotice(input);
            default -> usage();
        };
    }

    private ConsoleCommandProcessor.ConsoleResult usage() {
        return ConsoleCommandProcessor.ConsoleResult.failure(
                "Usage: notice <new|reload|publish|revoke|list>"
        );
    }

    private ConsoleCommandProcessor.ConsoleResult revokeNotice(
            ConsoleInputParser.ParsedInput input
    ) {
        if (input.size() != 3) {
            return ConsoleCommandProcessor.ConsoleResult.failure(
                    "Usage: notice revoke <notice-id>"
            );
        }

        final long id;

        try {
            id = Long.parseLong(input.value(2));
        } catch (NumberFormatException e) {
            return ConsoleCommandProcessor.ConsoleResult.failure(
                    "Invalid notice id: " + input.value(2)
            );
        }

        final boolean revoked = NoticeStore.revoke(id);

        return revoked
                ? ConsoleCommandProcessor.ConsoleResult.success("Notice #" + id + " revoked.")
                : ConsoleCommandProcessor.ConsoleResult.failure("Notice #" + id + " was not revoked.");
    }

    private ConsoleCommandProcessor.ConsoleResult newNotice(
            ConsoleInputParser.ParsedInput input
    ) {
        if (input.size() != 2) {
            return ConsoleCommandProcessor.ConsoleResult.failure(
                    "Usage: notice new"
            );
        }

        final long id = NoticeStore.createNoticeDraft();

        return ConsoleCommandProcessor.ConsoleResult.success(
                "New notice draft created, id: " + id
        );
    }

    private ConsoleCommandProcessor.ConsoleResult reloadNotice(
            ConsoleInputParser.ParsedInput input
    ) {
        if (input.size() != 2) {
            return ConsoleCommandProcessor.ConsoleResult.failure(
                    "Usage: notice reload"
            );
        }

        final int count = NoticeStore.loadFromFile();

        return ConsoleCommandProcessor.ConsoleResult.success(
                "Notices reloaded, count: " + count
        );
    }

    private ConsoleCommandProcessor.ConsoleResult publishNotice(
            ConsoleInputParser.ParsedInput input
    ) {
        if (input.size() != 3) {
            return ConsoleCommandProcessor.ConsoleResult.failure(
                    "Usage: notice publish <notice-id>"
            );
        }

        final long id;

        try {
            id = Long.parseLong(input.value(2));
        } catch (NumberFormatException e) {
            return ConsoleCommandProcessor.ConsoleResult.failure(
                    "Invalid notice id: " + input.value(2)
            );
        }

        final boolean published = NoticeStore.publish(id);

        return published
                ? ConsoleCommandProcessor.ConsoleResult.success("Notice #" + id + " published.")
                : ConsoleCommandProcessor.ConsoleResult.failure("Notice #" + id + " was not published.");
    }

    private ConsoleCommandProcessor.ConsoleResult listNotice(
            ConsoleInputParser.ParsedInput input
    ) {
        final boolean all;

        if (input.size() == 2) {
            all = false;
        } else if (input.size() == 3 && input.value(2).equalsIgnoreCase("all")) {
            all = true;
        } else {
            return ConsoleCommandProcessor.ConsoleResult.failure(
                    "Usage: notice list [all]"
            );
        }

        final Set<Notice> notices = NoticeStore.getNotices();

        final StringBuilder sb = new StringBuilder();

        sb.append(all ? "All" : "Active").append(" notices:\n");

        sb.append("id | title | active | published | level | expires\n");

        notices.stream()
                .filter(notice -> all || notice.isActive())
                .sorted()
                .forEach(n -> sb
                        .append(n.id()).append(" | ")
                        .append(n.title()).append(" | ")
                        .append(n.isActive()).append(" | ")
                        .append(n.published()).append(" | ")
                        .append(n.level()).append(" | ")
                        .append(n.expiresInMinutes()).append("\n"));

        return ConsoleCommandProcessor.ConsoleResult.success(
                sb.toString()
        );
    }
}