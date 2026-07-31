package xyz.zcraft.seira.command;

import org.junit.jupiter.api.Test;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.config.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RouterCompatibilityTest {
    private final Router router = new Router(new TestMessageSender(), testConfig(), () -> {
    });

    @Test
    void preservesTheCompletePublicCommandSet() {
        assertEquals(Set.of(
                "bind", "unbind", "clearhistory", "bp", "bo", "daily", "mp", "rs", "rp",
                "m", "ap", "bgp", "f", "fall", "fclear", "dl", "s", "sa", "ma", "r",
                "rsc", "ms", "sms", "lb", "stat", "u", "luck", "rstat", "inspect", "help", "faq"
        ), router.registeredCommands());
    }

    @Test
    void keepsIgnoredEmptyAndUnknownRoutingBehavior() {
        assertNull(router.route("not a command", "user", null, "message"));
        assertEquals("请输入指令。使用/help获取帮助。",
                router.route("/", "user", null, "message").initialMessage().getContent());
        assertEquals("未知指令。使用/help获取帮助。",
                router.route("/does-not-exist", "user", null, "message").initialMessage().getContent());
    }

    @Test
    void keepsBestScoreAliasesAndUserShortcutDelegation() {
        RouteDecision bo = router.route("/bo 8 player", "user", null, "message");
        RouteDecision bp = router.route("/bp 8 player", "user", null, "message");
        RouteDecision user = router.route("/u player", "user", null, "message");

        assertAll(
                () -> assertEquals("Best Scores", bo.apiTask().requestType()),
                () -> assertEquals("Best Scores", bp.apiTask().requestType()),
                () -> assertEquals("Best Scores", user.apiTask().requestType())
        );
    }

    @Test
    void preservesCommandMetricsIncludingTheHistoricalUserShortcutDelegation() {
        AtomicInteger commandCount = new AtomicInteger();
        Router measuredRouter = new Router(
                new TestMessageSender(), testConfig(), commandCount::incrementAndGet
        );

        measuredRouter.route("/does-not-exist", "user", null, "message");
        assertEquals(1, commandCount.get());

        measuredRouter.route("/u player", "user", null, "message");
        assertEquals(3, commandCount.get());
    }

    private static AppConfig testConfig() {
        return new AppConfig(
                new SeiraConfig("test.db", "https://example.test", true, false, List.of("admin")),
                new OstellaConfig("https://api.example.test"),
                new BindingConfig(0, "/bind", 1, "secret"),
                new QqConfig("self", "app", "secret"),
                new CosConfig("id", "key", "region", "bucket", "https://cos.example.test", "prefix")
        );
    }

    private static final class TestMessageSender extends MessageSender {
        private TestMessageSender() {
            super(null, null);
        }
    }
}
