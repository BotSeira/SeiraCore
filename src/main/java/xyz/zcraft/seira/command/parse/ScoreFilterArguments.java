package xyz.zcraft.seira.command.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses score-list filters and converts aliases to oStella's compact filter syntax. */
public final class ScoreFilterArguments {
    private static final Pattern FILTER_PATTERN = Pattern.compile(
            "(?i)^(acc(?:uracy)?|combo|pp|time|length|len|star|stars|sr|bpm|miss|misses|score|mod|mods|rank)"
                    + "(大于等于|小于等于|不包含|不等于|包含|等于|大于|小于|>=|<=|!=|!~|>|<|=|~)(.+)$"
    );
    private static final Pattern DURATION_PATTERN = Pattern.compile("(?i)^(?:(\\d+)m)?(?:(\\d+(?:\\.\\d+)?)s)?$");
    private static final Set<String> RANKS = Set.of("XH", "X", "SH", "S", "A", "B", "C", "D", "F");

    private ScoreFilterArguments() {
    }

    public static boolean looksLikeFilter(String value) {
        if (value == null) return false;
        return value.matches(".*(?:>=|<=|!=|!~|>|<|=|~|大于等于|小于等于|不包含|不等于|包含|等于|大于|小于).*");
    }

    public static ParseResult parse(String[] args, int startIndex) {
        if (startIndex >= args.length) {
            return new ParseResult(List.of(), null);
        }

        List<String> filters = new ArrayList<>();
        for (int i = startIndex; i < args.length; i++) {
            try {
                filters.add(parseOne(args[i]));
            } catch (IllegalArgumentException e) {
                return new ParseResult(List.of(), "过滤条件错误：" + e.getMessage());
            }
        }
        return new ParseResult(List.copyOf(filters), null);
    }

    private static String parseOne(String token) {
        Matcher matcher = FILTER_PATTERN.matcher(token.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("无法识别 `" + token + "`");
        }

        String field = normalizeField(matcher.group(1));
        String operator = normalizeOperator(matcher.group(2));
        String value = matcher.group(3).trim();

        if (field.equals("mod")) {
            if (!Set.of("~", "!~", "=", "!=").contains(operator)) {
                throw new IllegalArgumentException("mod 仅支持 ~、!~、=、!=");
            }
            validateMods(value);
        } else if (field.equals("rank")) {
            if (!Set.of("=", "!=").contains(operator)) {
                throw new IllegalArgumentException("rank 仅支持 =、!=");
            }
            value = value.toUpperCase(Locale.ROOT);
            if (!RANKS.contains(value)) {
                throw new IllegalArgumentException("rank 必须是 XH/X/SH/S/A/B/C/D/F");
            }
        } else {
            if (Set.of("~", "!~").contains(operator)) {
                throw new IllegalArgumentException(field + " 不支持包含运算符");
            }
            double number = field.equals("time") ? parseDuration(value) : parseNumber(value);
            if (number < 0) {
                throw new IllegalArgumentException(field + " 不能小于 0");
            }
            if (field.equals("acc") && number > 100) {
                throw new IllegalArgumentException("acc 必须在 0 到 100 之间");
            }
        }

        return field + operator + value;
    }

    private static String normalizeField(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "acc", "accuracy" -> "acc";
            case "combo" -> "combo";
            case "pp" -> "pp";
            case "time", "length", "len" -> "time";
            case "star", "stars", "sr" -> "star";
            case "bpm" -> "bpm";
            case "miss", "misses" -> "miss";
            case "score" -> "score";
            case "mod", "mods" -> "mod";
            case "rank" -> "rank";
            default -> throw new IllegalArgumentException("未知字段 " + value);
        };
    }

    private static String normalizeOperator(String value) {
        return switch (value) {
            case "大于等于" -> ">=";
            case "小于等于" -> "<=";
            case "不包含" -> "!~";
            case "不等于" -> "!=";
            case "包含" -> "~";
            case "等于" -> "=";
            case "大于" -> ">";
            case "小于" -> "<";
            default -> value;
        };
    }

    private static void validateMods(String value) {
        String normalized = value.toUpperCase(Locale.ROOT).replace("+", "");
        if (normalized.equals("NM")) return;
        if (normalized.isEmpty() || normalized.length() % 2 != 0 || !normalized.matches("[A-Z]+")) {
            throw new IllegalArgumentException("mod 值应为 NM 或 Mod 缩写组合，例如 HDDT");
        }
    }

    private static double parseDuration(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains(":")) {
            String[] parts = normalized.split(":", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("time 格式应为秒数、m:ss 或 1m30s");
            }
            double minutes = parseNumber(parts[0]);
            double seconds = parseNumber(parts[1]);
            if (minutes < 0 || seconds < 0 || seconds >= 60) {
                throw new IllegalArgumentException("time 格式应为秒数、m:ss 或 1m30s");
            }
            return minutes * 60 + seconds;
        }

        Matcher matcher = DURATION_PATTERN.matcher(normalized);
        if (matcher.matches() && (matcher.group(1) != null || matcher.group(2) != null)) {
            double minutes = matcher.group(1) == null ? 0 : parseNumber(matcher.group(1));
            double seconds = matcher.group(2) == null ? 0 : parseNumber(matcher.group(2));
            return minutes * 60 + seconds;
        }
        return parseNumber(normalized);
    }

    private static double parseNumber(String value) {
        try {
            double number = Double.parseDouble(value);
            if (!Double.isFinite(number)) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("数值格式无效：" + value, e);
        }
    }

    public record ParseResult(List<String> filters, String errorMessage) {
        public boolean isError() {
            return errorMessage != null;
        }
    }
}
