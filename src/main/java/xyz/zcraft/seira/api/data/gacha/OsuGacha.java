package xyz.zcraft.seira.api.data.gacha;

public class OsuGacha {
    public static String getClassName(String cls) {
        return switch (cls) {
            case "dps" -> "输出";
            case "cc" -> "控制";
            case "support" -> "辅助";
            case "tank" -> "坦克";
            default -> "未知(" + cls + ")";
        };
    }

    public static String getRarityName(String rarity) {
        return switch (rarity) {
            case "divine" -> "神圣";
            case "mythic" -> "神话";
            case "legendary" -> "传说";
            case "epic" -> "史诗";
            case "rare" -> "稀有";
            case "uncommon" -> "优秀";
            case "common" -> "普通";
            default -> "未知(" + rarity + ")";
        };
    }
}
