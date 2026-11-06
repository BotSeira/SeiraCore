package xyz.zcraft.seira.api.data.gacha;

public record Guide(
        long listed,
        Long lowest,
        long sales,
        Long median,
        Long highest,
        long days,
        String updatedAt
) {
    private static final Guide EMPTY = new Guide(0, null, 0, null, null, 30, null);

    public static Guide empty() {
        return EMPTY;
    }
}
