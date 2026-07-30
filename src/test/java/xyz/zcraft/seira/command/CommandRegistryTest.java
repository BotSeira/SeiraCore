package xyz.zcraft.seira.command;

import org.junit.jupiter.api.Test;
import xyz.zcraft.seira.bot.data.PendingMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandRegistryTest {
    @Test
    void dispatchesAliasesToTheSameHandler() {
        CommandRegistry registry = CommandRegistry.builder()
                .register(context -> reply(context.command()), "bo", "bp")
                .build();

        assertEquals("bo", dispatch(registry, "bo").initialMessage().getContent());
        assertEquals("BP", dispatch(registry, "BP").initialMessage().getContent());
    }

    @Test
    void usesFallbackForAnUnknownCommand() {
        CommandRegistry registry = CommandRegistry.builder()
                .register(_ -> reply("known"), "known")
                .build();

        RouteDecision result = registry.dispatch(context("unknown"), () -> reply("fallback"));

        assertEquals("fallback", result.initialMessage().getContent());
    }

    @Test
    void rejectsDuplicateRegistrationsRegardlessOfCase() {
        CommandRegistry.Builder builder = CommandRegistry.builder()
                .register(_ -> reply("first"), "help");

        assertThrows(IllegalArgumentException.class,
                () -> builder.register(_ -> reply("second"), " HELP "));
    }

    private static RouteDecision dispatch(CommandRegistry registry, String command) {
        return registry.dispatch(context(command), () -> reply("fallback"));
    }

    private static Context context(String command) {
        return new Context("user", null, "message", command, new String[0], "");
    }

    private static RouteDecision reply(String content) {
        return RouteDecision.sync(PendingMessage.ofString(content));
    }
}
