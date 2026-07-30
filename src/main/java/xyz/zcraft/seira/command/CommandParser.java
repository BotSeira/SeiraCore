package xyz.zcraft.seira.command;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.function.UnaryOperator;

final class CommandParser {
    private static final String PREFIX = "/";

    private final UnaryOperator<String> preProcessor;

    CommandParser(UnaryOperator<String> preProcessor) {
        this.preProcessor = Objects.requireNonNull(preProcessor);
    }

    ParseResult parse(String rawContent, String senderUserId, String groupId, String messageId) {
        if (rawContent == null) {
            return ParseResult.ignored();
        }

        String normalized = rawContent.trim();
        if (!normalized.startsWith(PREFIX)) {
            return ParseResult.ignored();
        }

        String body = normalized.substring(PREFIX.length()).trim();
        if (body.isEmpty()) {
            return ParseResult.emptyCommand();
        }

        body = Objects.requireNonNull(preProcessor.apply(body), "Command pre-processor returned null").trim();
        if (body.isEmpty()) {
            return ParseResult.emptyCommand();
        }

        String[] parts = body.split("\\s+");
        String command = parts[0].toLowerCase(Locale.ROOT);
        String query = body.substring(parts[0].length()).trim();
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);

        return ParseResult.parsed(new Context(senderUserId, groupId, messageId, command, args, query));
    }

    record ParseResult(Status status, Context context) {
        enum Status {
            IGNORED,
            EMPTY_COMMAND,
            PARSED
        }

        ParseResult {
            if ((status == Status.PARSED) == (context == null)) {
                throw new IllegalArgumentException("Only parsed results may contain a context");
            }
        }

        static ParseResult ignored() {
            return new ParseResult(Status.IGNORED, null);
        }

        static ParseResult emptyCommand() {
            return new ParseResult(Status.EMPTY_COMMAND, null);
        }

        static ParseResult parsed(Context context) {
            return new ParseResult(Status.PARSED, Objects.requireNonNull(context));
        }
    }
}
