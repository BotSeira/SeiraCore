package xyz.zcraft.seira.api.data;

public record MissData(
        int index,
        long time,
        Type type
) {
    public enum Type {
        HIT_CIRCLE, SLIDER, SPINNER;

        @Override
        public String toString() {
            return switch (this) {
                case HIT_CIRCLE -> "圈圈";
                case SLIDER -> "滑条";
                case SPINNER -> "转盘";
            };
        }
    }
}
