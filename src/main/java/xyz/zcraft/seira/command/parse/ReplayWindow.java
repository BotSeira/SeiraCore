package xyz.zcraft.seira.command.parse;
import java.math.BigDecimal;
public record ReplayWindow(BigDecimal before, BigDecimal after) {
    public ReplayWindow {
        if (before == null || after == null || before.signum() < 0 || after.signum() < 0
                || before.add(after).signum() == 0 || before.add(after).compareTo(new BigDecimal("6")) > 0)
            throw new IllegalArgumentException("GIF 时间范围必须大于 0 且不超过 6 秒。");
    }
    public static ReplayWindow parse(String value) {
        if (value == null) return new ReplayWindow(new BigDecimal("3"), BigDecimal.ONE);
        if (!value.matches("[0-9]+(?:\\.[0-9]+)?(?:-[0-9]+(?:\\.[0-9]+)?)?"))
            throw new IllegalArgumentException("window 请使用秒数 X 或 X-Y，例如 2、1.5、3-1。");
        String[] parts = value.split("-");
        return new ReplayWindow(new BigDecimal(parts[0]), new BigDecimal(parts[parts.length - 1]));
    }
    public String queryString() {
        return "before=" + before.toPlainString() + "&after=" + after.toPlainString();
    }
}
