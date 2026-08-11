package xyz.zcraft.seira.watch;

import xyz.zcraft.seira.bot.MessageSender;

import java.util.Objects;

import static xyz.zcraft.seira.command.reply.ReplyFactory.cmd;

public final class SpecificScoreNotifier {
    private final MessageSender messageSender;

    public SpecificScoreNotifier(MessageSender messageSender) {
        this.messageSender = Objects.requireNonNull(messageSender);
    }

    public boolean sendScoreId(String groupId, long scoreId) {
        return messageSender.sendGroupMarkdown(groupId, "捕获到成绩 ID：" + cmd("/s " + scoreId, String.valueOf(scoreId)));
    }
}
