package xyz.zcraft.seira.rankguess.data;

import lombok.Getter;
import xyz.zcraft.seira.rankguess.RankGuessGame;

import java.util.Objects;

public abstract class ScoreMultiplier {
    @Getter
    private final String reason;

    protected ScoreMultiplier(String reason) {
        this.reason = reason;
    }

    public static class FirstGuessMultiplier extends ScoreMultiplier {
        public FirstGuessMultiplier() {
            super("首猜加成");
        }
    }

    public static class OrderMultiplier extends ScoreMultiplier {
        @Getter
        private final int order;

        public OrderMultiplier(int order) {
            this.order = order;
            super("第" + order + "猜");
        }
    }

    public static class CopyPunishmentMultiplier extends ScoreMultiplier {
        public CopyPunishmentMultiplier() {
            super("抄袭惩罚");
        }
    }

    public static class HintMultiplier extends ScoreMultiplier {
        @Getter
        private final RankGuessGame.Hint hint;

        public HintMultiplier(RankGuessGame.Hint hint) {
            super("提示 " + hint.name() + " 已揭晓");
            this.hint = Objects.requireNonNull(hint, "hint");
        }
    }
}
