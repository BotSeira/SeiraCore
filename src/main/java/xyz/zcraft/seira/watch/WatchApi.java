package xyz.zcraft.seira.watch;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Backend boundary used by the score watch domain service. */
public interface WatchApi {
    Map<Long, List<RecentScore>> getRecentScores(Collection<Long> userIds, int limit);

    byte[] renderScore(long userId, long scoreId);
}
