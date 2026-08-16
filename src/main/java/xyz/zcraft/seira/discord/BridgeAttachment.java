package xyz.zcraft.seira.discord;

import java.util.List;

public record BridgeAttachment(
        String filename,
        List<String> candidateUrls,
        String sourceTextUrl,
        boolean animatedExpression
) {
    public BridgeAttachment {
        candidateUrls = candidateUrls == null ? List.of() : List.copyOf(candidateUrls);
    }

    public BridgeAttachment(String filename, String url) {
        this(filename, List.of(url), null, false);
    }
}
