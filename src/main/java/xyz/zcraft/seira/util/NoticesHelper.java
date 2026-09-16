package xyz.zcraft.seira.util;

import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.data.Notice;
import xyz.zcraft.seira.db.NoticeTrackerStore;
import xyz.zcraft.seira.services.NoticeStore;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static xyz.zcraft.seira.command.reply.ReplyFactory.cmd;

public class NoticesHelper {
    private static final Object[] NOTICE_LOCKS = new Object[64];

    static {
        for (int i = 0; i < NOTICE_LOCKS.length; i++) {
            NOTICE_LOCKS[i] = new Object();
        }
    }

    private static Object noticeLock(String id) {
        return NOTICE_LOCKS[(id.hashCode() & 0x7fffffff) % NOTICE_LOCKS.length];
    }

    public static void checkNotices(Context context) {
        final String targetId = context.inGroup()
                ? "g:" + context.groupId()
                : "p:" + context.senderUserId();

        synchronized (noticeLock(targetId)) {
            checkNoticesInternal(context);
        }
    }

    private static void checkNoticesInternal(Context context) {
        final NoticeTrackerStore.NoticeTrackState tracker;
        if (context.inGroup()) {
            tracker = NoticeTrackerStore.getGroupTracker(context.groupId());
        } else {
            tracker = NoticeTrackerStore.getPrivateTracker(context.senderUserId());
        }

        final Set<Notice> notices = NoticeStore.getNotices();

        final List<Notice> list = notices.stream()
                .filter(Notice::isActive)
                .filter(n -> !tracker.hasNotified(n))
                .sorted(Comparator.comparingLong(Notice::id))
                .toList();

        if (list.isEmpty()) {
            return;
        }

        final StringBuilder sb = new StringBuilder();

        sb.append("注意注意，有新的公告喵：\n");
        for (Notice notice : list) {
            sb.append("> \\#").append(notice.id()).append(" ⌈").append(notice.title()).append("⌋ ")
                    .append(cmd("/notice " + notice.id(), "[查看]")).append("\n");
        }

        if (!context.sendReply(PendingMessage.ofMarkdownRaw(sb.toString())).success()) {
            return;
        }

        final long l = list.stream().mapToLong(Notice::id).max().orElse(0L);

        if (context.inGroup()) {
            NoticeTrackerStore.updateGroupTracker(context.groupId(), l);
        } else {
            NoticeTrackerStore.updatePrivateTracker(context.senderUserId(), l);
        }
    }
}
