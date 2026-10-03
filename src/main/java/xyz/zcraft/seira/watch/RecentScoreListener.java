package xyz.zcraft.seira.watch;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Additional consumers share the watcher scheduler and one UID-deduplicated upstream batch.
 */
public interface RecentScoreListener {
    Collection<Long> watchedUserIds();

    void acceptRecentScores(Map<Long, List<RecentScore>> scores);
}
