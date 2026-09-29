package xyz.zcraft.seira.watch;

import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.data.UploadedImage;

import java.util.Objects;

import static xyz.zcraft.seira.command.reply.ReplyFactory.s;

public final class WatchScoreNotifier {
    private final MessageSender messageSender;

    public WatchScoreNotifier(MessageSender messageSender) {
        this.messageSender = Objects.requireNonNull(messageSender);
    }

    public boolean sendScore(String groupId, RecentScore score, byte[] imageBytes) {
        final UploadedImage uploadedImage = messageSender.uploadImageToCos(imageBytes);

        if (uploadedImage == null) return false;

        return messageSender.sendGroupMarkdown(
                groupId,
                """
                %s
                > ID %s
                """.formatted(uploadedImage.toMarkdown(), s(score.scoreId())).trim()
        ) != null;
    }
}
