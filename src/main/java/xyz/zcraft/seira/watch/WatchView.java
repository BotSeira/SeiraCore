package xyz.zcraft.seira.watch;

import java.time.Duration;

public record WatchView(WatchTarget target, Duration remaining) {
}
