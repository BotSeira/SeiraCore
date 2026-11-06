package xyz.zcraft.seira.command.parse;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

public record SnapshotSelection(String key, long value, long offset) {
    private static final Pattern CLOCK = Pattern.compile("[0-9]+:[0-5][0-9](?:\\.[0-9]{1,3})?");
    private static final Pattern SECONDS = Pattern.compile("[0-9]+(?:\\.[0-9]{1,3})?s");
    private static final Pattern MILLIS = Pattern.compile("[0-9]+ms");
    private static final Pattern OBJECT = Pattern.compile("(?:obj|object)[0-9]+");
    private static final Pattern MISS = Pattern.compile("(?:#|miss)[0-9]+");

    public static boolean looksLikeSelector(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("#") || OBJECT.matcher(lower).matches() || MISS.matcher(lower).matches()
                || lower.contains(":") || lower.matches("[0-9.]+(?:ms|s)");
    }

    public static SnapshotSelection parse(String argument, String offsetArgument) {
        if (argument == null) throw new IllegalArgumentException("请指定快照位置。");
        String value = argument.toLowerCase(Locale.ROOT);
        try {
            String key;
            long number;
            if (CLOCK.matcher(value).matches()) {
                String[] parts = value.split(":");
                number = Math.addExact(Math.multiplyExact(Long.parseLong(parts[0]), 60000),
                        new BigDecimal(parts[1]).movePointRight(3).longValueExact());
                key = "time";
            } else if (SECONDS.matcher(value).matches()) {
                number = new BigDecimal(value.substring(0, value.length() - 1)).movePointRight(3).longValueExact();
                key = "time";
            } else if (MILLIS.matcher(value).matches()) {
                number = Long.parseLong(value.substring(0, value.length() - 2));
                key = "time";
            } else if (OBJECT.matcher(value).matches()) {
                number = Long.parseLong(value.replaceFirst("^(?:object|obj)", ""));
                key = "object";
            } else if (MISS.matcher(value).matches()) {
                number = Long.parseLong(value.replaceFirst("^(?:miss|#)", ""));
                key = "miss";
            } else throw new IllegalArgumentException();
            if (number < 0 || number > Integer.MAX_VALUE || (!key.equals("time") && number == 0))
                throw new IllegalArgumentException();
            long offset = 0;
            if (offsetArgument != null) {
                if (!offsetArgument.matches("[+-]?[0-9]+ms")) throw new IllegalArgumentException();
                offset = Long.parseLong(offsetArgument.substring(0, offsetArgument.length() - 2));
                if (Math.abs((double) offset) > 60000) throw new IllegalArgumentException();
            }
            return new SnapshotSelection(key, number, offset);
        } catch (ArithmeticException | IllegalArgumentException e) {
            throw new IllegalArgumentException("快照位置无效：使用 mm:ss.fff、秒数s、毫秒数ms、obj物件序号或 #Miss序号；偏移最多 ±60000ms。");
        }
    }

    public String queryString() {
        return key + "=" + value + "&offset=" + offset;
    }

    public String label() {
        String label = switch (key) {
            case "time" -> String.format(Locale.ROOT, "%d:%02d.%03d", value / 60000, value / 1000 % 60, value % 1000);
            case "object" -> "第 " + value + " 个物件";
            case "miss" -> "第 " + value + " 个 Miss";
            default -> throw new IllegalStateException("Invalid snapshot type");
        };
        return label + (offset == 0 ? "" : " " + (offset > 0 ? "+" : "") + offset + "ms");
    }
}
