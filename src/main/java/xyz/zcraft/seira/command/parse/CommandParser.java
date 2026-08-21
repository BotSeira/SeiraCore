package xyz.zcraft.seira.command.parse;

import xyz.zcraft.seira.command.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.UnaryOperator;

public final class CommandParser {
    private static final String PREFIX = "/";

    private final UnaryOperator<String> preProcessor;

    public CommandParser(UnaryOperator<String> preProcessor) {
        this.preProcessor = Objects.requireNonNull(preProcessor);
    }

    public ParseResult parse(String rawContent, String senderUserId, String groupId, String messageId) {
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

        String[] parts = splitArguments(body);
        String command = parts[0].toLowerCase(Locale.ROOT);
        String query = body.substring(parts[0].length()).trim();
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);

        return ParseResult.parsed(new Context(senderUserId, groupId, messageId, command, args, query));
    }

    /** Splits arguments on unquoted whitespace and removes double-quote delimiters. */
    private static String[] splitArguments(String body) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < body.length(); i++) {
            char character = body.charAt(i);
            if (character == '\\' && i + 1 < body.length() && body.charAt(i + 1) == '"') {
                current.append('"');
                i++;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(character) && !quoted) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }

        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts.toArray(String[]::new);
    }

    public record ParseResult(Status status, Context context) {
        public enum Status {
            IGNORED,
            EMPTY_COMMAND,
            PARSED
        }

        public ParseResult {
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
