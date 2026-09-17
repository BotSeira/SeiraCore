package xyz.zcraft.seira.rankguess.data;

import xyz.zcraft.osu.model.Score;
import xyz.zcraft.osu.model.UserExtended;
import xyz.zcraft.seira.api.data.RandomScore;
import xyz.zcraft.seira.data.UploadedImage;
import xyz.zcraft.seira.rankguess.RankGuessGame;
import xyz.zcraft.seira.rankguess.RankGuessGameService;
import xyz.zcraft.seira.util.ImageUtil;
import xyz.zcraft.seira.util.WeightedRandom;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public record Round(long userId, long scoreId, int bestIndex, long actualRank, Double pp,
                    RandomScore randomScore, boolean standard) {
    public Round {
        if (userId <= 0 || scoreId <= 0 || actualRank <= 0) {
            throw new IllegalArgumentException("Rank Guess 数据必须包含有效的用户、成绩和排名");
        }
    }

    public static Round from(RandomScore randomScore, boolean standard) {
        if (randomScore == null || randomScore.user() == null || randomScore.score() == null) {
            throw new IllegalArgumentException("随机成绩响应缺少用户或成绩数据");
        }
        if (randomScore.user().getStatistics() == null
                || randomScore.user().getStatistics().getGlobalRank() == null
                || randomScore.user().getStatistics().getGlobalRank() <= 0) {
            throw new IllegalArgumentException("随机用户缺少有效的全球排名");
        }
        if (randomScore.score().getId() == null || randomScore.score().getId() <= 0) {
            throw new IllegalArgumentException("随机成绩缺少有效的成绩ID");
        }
        if (randomScore.user().getId() <= 0) {
            throw new IllegalArgumentException("随机用户缺少有效的用户ID");
        }

        return new Round(
                randomScore.user().getId(),
                randomScore.score().getId(),
                randomScore.bestIndex(),
                randomScore.user().getStatistics().getGlobalRank(),
                randomScore.score().getPp(),
                randomScore,
                standard
        );
    }

    public LinkedList<RankGuessGame.Hint> getNormalHints() {
        LinkedList<RankGuessGame.Hint> hints = new LinkedList<>();

        final UserExtended user = this.randomScore.user();
        final Score score = this.randomScore.score();

        // ===== Rank =====

        String range = RankGuessGameService.getGlobalRankRange(
                user.getStatistics().getGlobalRank()
        );

        hints.add(new RankGuessGame.Hint(
                "本玩家的排名范围为 `" + range + "`",
                "排名范围",
                RankGuessGame.Hint.HintCategory.RANK,
                RankGuessGame.Hint.HintStrength.VERY_STRONG
        ));

        // ===== Target score =====

        hints.add(new RankGuessGame.Hint(
                "这是此玩家的 `BP" + this.bestIndex + "`",
                "BP位置",
                RankGuessGame.Hint.HintCategory.TARGET_SCORE,
                RankGuessGame.Hint.HintStrength.MEDIUM
        ));

        final long perfect = score.getStatistics().getOrDefault("great", 0L);
        final long ok = score.getStatistics().getOrDefault("ok", 0L);
        final long meh = score.getStatistics().getOrDefault("meh", 0L);
        final long miss = score.getStatistics().getOrDefault("miss", 0L);

        hints.add(new RankGuessGame.Hint(
                "本成绩的结果为: `300: %d / 100: %d / 50: %d / Miss: %d (%.2f%%)`"
                        .formatted(
                                perfect,
                                ok,
                                meh,
                                miss,
                                score.getAccuracy() * 100
                        ),
                "成绩结果",
                RankGuessGame.Hint.HintCategory.TARGET_SCORE,
                RankGuessGame.Hint.HintStrength.WEAK
        ));

        hints.add(new RankGuessGame.Hint(
                "本成绩的最大连击为 `%d`".formatted(score.getMaxCombo()),
                "成绩连击",
                RankGuessGame.Hint.HintCategory.TARGET_SCORE,
                RankGuessGame.Hint.HintStrength.WEAK
        ));

        String ppRange = RankGuessGameService.getPPRange(
                score.getPp()
        );

        hints.add(new RankGuessGame.Hint(
                "本成绩的PP范围为 `%s`".formatted(ppRange),
                "成绩PP范围",
                RankGuessGame.Hint.HintCategory.TARGET_SCORE,
                RankGuessGame.Hint.HintStrength.REVEALING
        ));

//            if (score.getEndedAt() != null) {
//                hints.add(new RankGuessGame.Hint(
//                        "本成绩完成于 `%s`".formatted(score.getEndedAt()),
//                        "成绩时间",
//                        RankGuessGame.Hint.HintCategory.TARGET_SCORE,
//                        RankGuessGame.Hint.HintStrength.WEAK
//                ));
//            }

        // ===== Difficulty =====

        hints.add(new RankGuessGame.Hint(
                "本谱面的难度为: `%s`".formatted(this.randomScore.beatmapDiff()),
                "谱面难度",
                RankGuessGame.Hint.HintCategory.DIFFICULTY,
                RankGuessGame.Hint.HintStrength.STRONG
        ));

        // ===== Account activity =====

        if (user.getStatistics() != null) {
            var stats = user.getStatistics();

            hints.add(new RankGuessGame.Hint(
                    "本玩家的总游玩次数为 `%d`".formatted(stats.getPlayCount()),
                    "游玩次数",
                    RankGuessGame.Hint.HintCategory.ACTIVITY,
                    RankGuessGame.Hint.HintStrength.WEAK
            ));

            hints.add(new RankGuessGame.Hint(
                    "本玩家的总游玩时间约为 `%.0f 小时`".formatted(stats.getPlayTime() / 3600.0),
                    "游玩时间",
                    RankGuessGame.Hint.HintCategory.ACTIVITY,
                    RankGuessGame.Hint.HintStrength.WEAK
            ));

            hints.add(new RankGuessGame.Hint(
                    "本玩家的总命中数约为 `%,d`".formatted(stats.getTotalHits()),
                    "总命中数",
                    RankGuessGame.Hint.HintCategory.ACTIVITY,
                    RankGuessGame.Hint.HintStrength.WEAK
            ));

            hints.add(new RankGuessGame.Hint(
                    "本玩家的平均准确率为 `%.2f%%`".formatted(stats.getAccuracy() * 100),
                    "总准确率",
                    RankGuessGame.Hint.HintCategory.ACTIVITY,
                    RankGuessGame.Hint.HintStrength.WEAK
            ));

            hints.add(new RankGuessGame.Hint(
                    "本玩家的历史最大连击为 `%d`".formatted(stats.getMaximumCombo()),
                    "最大连击",
                    RankGuessGame.Hint.HintCategory.ACTIVITY,
                    RankGuessGame.Hint.HintStrength.WEAK
            ));

            hints.add(new RankGuessGame.Hint(
                    "本玩家当前等级约为 `%.1f`".formatted(stats.getLevel().getCurrent() + stats.getLevel().getProgress() / 100.0),
                    "玩家等级",
                    RankGuessGame.Hint.HintCategory.ACTIVITY,
                    RankGuessGame.Hint.HintStrength.WEAK
            ));
        }

        // ===== Account metadata =====

        if (user.getJoinDate() != null) {
            hints.add(new RankGuessGame.Hint(
                    "本玩家于 `%s` 注册 osu!".formatted(user.getJoinDate()),
                    "注册时间",
                    RankGuessGame.Hint.HintCategory.ACTIVITY,
                    RankGuessGame.Hint.HintStrength.WEAK
            ));
        }

        return hints;
    }

    public List<RankGuessGame.Hint> getGroupHints(String avatarUrl, Function<byte[], UploadedImage> imageUploader) {
        WeightedRandom<RankGuessGame.Hint> hintRandom = new WeightedRandom<>();

        final UserExtended user = this.randomScore.user();

        final UserExtended.Team team = user.getTeam();
        if (team != null && team.getName() != null && team.getShortName() != null) {
            hintRandom.add(new RankGuessGame.Hint(
                    "该玩家所处的队伍缩写为 `%s`".formatted(team.getShortName()),
                    "玩家队伍缩写",
                    RankGuessGame.Hint.HintCategory.USER,
                    RankGuessGame.Hint.HintStrength.SPECIAL
            ), 2.0);
        }

        if (user.getHasSupported() && user.isSupporter()) {
            hintRandom.add(new RankGuessGame.Hint(
                    "该玩家是尊贵的撒泼特！",
                    "支持者状态",
                    RankGuessGame.Hint.HintCategory.USER,
                    RankGuessGame.Hint.HintStrength.SPECIAL
            ), 1.5);
        } else if (user.getHasSupported() && !user.isSupporter()) {
            hintRandom.add(new RankGuessGame.Hint(
                    "该玩家的撒泼特已经过期了。",
                    "支持者状态",
                    RankGuessGame.Hint.HintCategory.USER,
                    RankGuessGame.Hint.HintStrength.SPECIAL
            ), 1.5);
        }

        if (user.getInterests() != null && !user.getInterests().isBlank()) {
            hintRandom.add(new RankGuessGame.Hint(
                    "该玩家填写的兴趣爱好为 `%s`".formatted(user.getInterests()),
                    "玩家自述兴趣",
                    RankGuessGame.Hint.HintCategory.USER,
                    RankGuessGame.Hint.HintStrength.SPECIAL
            ), 0.5);
        }

        if (user.getLocation() != null && !user.getLocation().isBlank()) {
            hintRandom.add(new RankGuessGame.Hint(
                    "该玩家填写的位置为 `%s`".formatted(user.getLocation()),
                    "玩家自述位置",
                    RankGuessGame.Hint.HintCategory.USER,
                    RankGuessGame.Hint.HintStrength.SPECIAL
            ), 0.5);
        }

        if (user.getOccupation() != null && !user.getOccupation().isBlank()) {
            hintRandom.add(new RankGuessGame.Hint(
                    "该玩家填写的职业为 `%s`".formatted(user.getOccupation()),
                    "玩家自述职业",
                    RankGuessGame.Hint.HintCategory.USER,
                    RankGuessGame.Hint.HintStrength.SPECIAL
            ), 0.5);
        }

        final var features = new ArrayList<>(RankGuessGameService.getUsernameFeature(user.getUsername()));

        if (!features.isEmpty()) {
            Collections.shuffle(features);

            for (int i = 0; i < Math.min(features.size(), 4); i++) {
                final var s = features.get(i);
                hintRandom.add(new RankGuessGame.Hint(
                        "该玩家用户名" + s.getKey(),
                        "用户名特征",
                        RankGuessGame.Hint.HintCategory.USER,
                        RankGuessGame.Hint.HintStrength.SPECIAL
                ), s.getValue());
            }
        }

        if (avatarUrl != null) {
            try {
                final BufferedImage original = ImageUtil.readImage(avatarUrl);

                final BufferedImage mosaic = ImageUtil.mosaic(original, 15);
                final BufferedImage blur = ImageUtil.gaussianBlur(original, 50);

                final byte[] mosaicBytes = ImageUtil.toPngBytes(mosaic);
                final byte[] blurBytes = ImageUtil.toPngBytes(blur);

                final var mosaicImage = imageUploader.apply(mosaicBytes);
                final var blurImage = imageUploader.apply(blurBytes);

                hintRandom.add(new RankGuessGame.Hint(
                        "该玩家头像: " + mosaicImage.toMarkdown(25, 25),
                        "玩家头像",
                        RankGuessGame.Hint.HintCategory.USER,
                        RankGuessGame.Hint.HintStrength.SPECIAL
                ), 1.25);

                hintRandom.add(new RankGuessGame.Hint(
                        "该玩家头像: " + blurImage.toMarkdown(25, 25),
                        "玩家头像",
                        RankGuessGame.Hint.HintCategory.USER,
                        RankGuessGame.Hint.HintStrength.SPECIAL
                ), 1.25);
            } catch (Exception ignored) {
                // Ignored
            }
        }

        return List.of(hintRandom.next());
    }
}
