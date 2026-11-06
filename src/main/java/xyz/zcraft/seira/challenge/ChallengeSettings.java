package xyz.zcraft.seira.challenge;

import xyz.zcraft.osu.model.ModSettings;

import xyz.zcraft.seira.challenge.ChallengeModels.MapChoice;
import xyz.zcraft.seira.challenge.ChallengeModels.ScoreData;
import xyz.zcraft.seira.challenge.ChallengeModels.SetData;

import java.util.*;

public record ChallengeSettings(boolean differentDifficulties, Long fixedMapId, Set<String> requiredMods,
                                Set<String> bannedMods, double minStars, double maxStars, boolean skillAdjustment) {
    public static final Set<String> SUPPORTED_MODS = Set.of("NF", "EZ", "HD", "HR", "SD", "DT", "HT", "FL", "SO", "CL", "MR");

    public ChallengeSettings {
        requiredMods = Set.copyOf(requiredMods);
        bannedMods = Set.copyOf(bannedMods);
        if (!Double.isFinite(minStars) || !Double.isFinite(maxStars) || minStars < 0 || maxStars > 15 || minStars >= maxStars)
            throw new IllegalArgumentException("基础星数范围需要满足 0 ≤ 最小值 < 最大值 ≤ 15。");
        if (fixedMapId != null && fixedMapId <= 0) throw new IllegalArgumentException("固定谱面 ID 必须为正整数。");
        for (String mod : requiredMods)
            if (!SUPPORTED_MODS.contains(mod) && !"NM".equals(mod))
                throw new IllegalArgumentException("不支持的必选 Mod：" + mod);
        for (String mod : bannedMods)
            if (!SUPPORTED_MODS.contains(mod)) throw new IllegalArgumentException("不支持的禁用 Mod：" + mod);
        if (!Collections.disjoint(requiredMods, bannedMods))
            throw new IllegalArgumentException("同一 Mod 不能同时必选和禁用。");
        if (requiredMods.contains("NM") && requiredMods.size() > 1)
            throw new IllegalArgumentException("NM 不能与其他必选 Mod 同时设置。");
        if (both(requiredMods, "HR", "EZ") || both(requiredMods, "DT", "HT") || both(requiredMods, "NF", "SD"))
            throw new IllegalArgumentException("必选 Mod 组合存在冲突。");
    }

    public static ChallengeSettings defaults() {
        return new ChallengeSettings(true, null, Set.of(), Set.of(), 0, 15, true);
    }

    private static boolean both(Set<String> mods, String a, String b) {
        return mods.contains(a) && mods.contains(b);
    }

    public static Set<String> parseMods(String value, boolean allowNM) {
        if (value == null || value.isBlank() || "none".equalsIgnoreCase(value) || "clear".equalsIgnoreCase(value))
            return Set.of();
        String text = value.toUpperCase(Locale.ROOT).replaceAll("[+,，\\s]", "");
        if (text.equals("NM")) {
            if (!allowNM) throw new IllegalArgumentException("不能禁用 NM；请指定具体 Mod。");
            return Set.of("NM");
        }
        if (text.length() % 2 != 0) throw new IllegalArgumentException("Mod 请使用 HDHR 或 HD,HR 等写法。");
        Set<String> mods = new TreeSet<>();
        for (int i = 0; i < text.length(); i += 2) {
            String mod = canonical(text.substring(i, i + 2));
            if (!SUPPORTED_MODS.contains(mod)) throw new IllegalArgumentException("不支持的 Mod：" + mod);
            mods.add(mod);
        }
        return Set.copyOf(mods);
    }

    private static String canonical(String mod) {
        return switch (mod) {
            case "NC" -> "DT";
            case "PF" -> "SD";
            default -> mod;
        };
    }

    public static Set<String> scoreMods(String value) {
        if (value == null || value.isEmpty()) return Set.of();
        return parseMods(ModSettings.parse(value).stream()
                .map(mod -> mod.getAcronym()).collect(java.util.stream.Collectors.joining()), false);
    }

    public static String displayMods(Set<String> mods) {
        return mods.isEmpty() ? "无" : String.join(",", new TreeSet<>(mods));
    }

    public List<MapChoice> choices(SetData set) {
        return set.maps().stream().filter(map -> map.stars() >= minStars && map.stars() <= maxStars).toList();
    }

    public String rejection(SetData set, ScoreData score) {
        MapChoice map = choices(set).stream().filter(choice -> choice.id() == score.beatmapId()).findFirst().orElse(null);
        if (map == null) return "成绩难度不在本次挑战的基础星数范围内。";
        if (!differentDifficulties && !Objects.equals(fixedMapId, score.beatmapId()))
            return "本次挑战只允许指定的固定难度。";
        Set<String> mods;
        try {
            mods = scoreMods(score.mods());
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        if (!Collections.disjoint(mods, bannedMods)) return "成绩使用了本次挑战禁用的 Mod。";
        if (requiredMods.contains("NM")) {
            // Classic identifies stable scoring too; it is neutral for the NM requirement, but may be banned explicitly.
            if (mods.stream().anyMatch(mod -> !mod.equals("CL"))) return "本次挑战要求 NM。";
        } else if (!mods.containsAll(requiredMods)) return "成绩缺少本次挑战的必选 Mod。";
        return null;
    }

    public ChallengeSettings withMap(Long map) {
        return new ChallengeSettings(differentDifficulties, map, requiredMods, bannedMods, minStars, maxStars, skillAdjustment);
    }
}
