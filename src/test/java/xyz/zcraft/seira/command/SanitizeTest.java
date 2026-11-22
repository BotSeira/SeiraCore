package xyz.zcraft.seira.command;

import org.junit.jupiter.api.Test;
import xyz.zcraft.seira.command.parse.Resolver;

import java.util.List;

public class SanitizeTest {
    private static final List<String> TEST_CASES = List.of(
            "bind",
            "p5 @abc",
            "bp4",
            "ps @abc",
            "bs 20 @abc",
            "rp 10 @abc",
            "bd20",
            "p @abc",
            "bp10-20",
            "rp20-40 @abc"
    );

    @Test
    public void testSanitize() {
        Resolver r = new Resolver();
        for (String s : TEST_CASES) {
            System.out.println(s + " -> " + r.sanitize(s));
        }
    }
}
