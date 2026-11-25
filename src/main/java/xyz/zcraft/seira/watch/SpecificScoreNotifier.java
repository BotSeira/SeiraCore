package xyz.zcraft.seira.watch;

import xyz.zcraft.seira.bot.MessageSender;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static xyz.zcraft.seira.command.reply.ReplyFactory.cmd;

public final class SpecificScoreNotifier {
    private final MessageSender messageSender;

    public SpecificScoreNotifier(MessageSender messageSender) {
        this.messageSender = Objects.requireNonNull(messageSender);
    }

    private static String joinIds(Set<Long> ids) {
        return ids.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    public boolean sendScoreId(String groupId, long scoreId) {
        return messageSender.sendGroupMarkdown(groupId, "捕获到成绩 ID：" + cmd("/s " + scoreId, String.valueOf(scoreId))) != null;
    }

    public boolean sendExpired(String groupId, Set<Long> userIds, Set<Long> beatmapIds) {
        String command = "/wx start " + joinIds(userIds) + " " + joinIds(beatmapIds);
        return messageSender.sendGroupMarkdown(groupId,
                "本群的指定谱面成绩监视已到期，点击" + cmd(command, "这里") + "再次启动喵。") != null;
    }
}
