package xyz.zcraft.seira.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CommandParserTest {
    private final CommandParser parser = new CommandParser(value -> value);

    @Test
    void ignoresContentThatIsNotACommand() {
        assertEquals(CommandParser.ParseResult.Status.IGNORED,
                parser.parse(null, "user", "group", "message").status());
        assertEquals(CommandParser.ParseResult.Status.IGNORED,
                parser.parse("hello", "user", "group", "message").status());
    }

    @Test
    void identifiesAnEmptyCommand() {
        CommandParser.ParseResult result = parser.parse("  /   ", "user", null, "message");

        assertEquals(CommandParser.ParseResult.Status.EMPTY_COMMAND, result.status());
        assertNull(result.context());
    }

    @Test
    void normalizesAndSplitsACommandWhilePreservingItsQuery() {
        CommandParser.ParseResult result = parser.parse(
                "  /Bo   8   SomeUser  ", "user", "group", "message"
        );

        assertEquals(CommandParser.ParseResult.Status.PARSED, result.status());
        Context context = result.context();
        assertEquals("bo", context.command());
        assertEquals("8   SomeUser", context.query());
        assertArrayEquals(new String[]{"8", "SomeUser"}, context.args());
        assertEquals("user", context.senderUserId());
        assertEquals("group", context.groupId());
        assertEquals("message", context.messageId());
    }

    @Test
    void parsesTheContentReturnedByThePreProcessor() {
        CommandParser macroParser = new CommandParser(new Resolver()::preProcess);

        Context context = macroParser.parse("/RS2", "user", null, "message").context();

        assertEquals("s", context.command());
        assertEquals("RS2", context.query());
        assertArrayEquals(new String[]{"RS2"}, context.args());
    }

    @Test
    void parsedArgumentsCannotBeMutatedThroughTheContextAccessor() {
        Context context = parser.parse("/bo 8 user", "user", null, "message").context();
        String[] exposedArguments = context.args();

        exposedArguments[0] = "changed";

        assertEquals(2, context.argumentCount());
        assertEquals("8", context.argument(0));
    }
}
