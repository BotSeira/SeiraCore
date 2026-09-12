package xyz.zcraft.seira.data;

import org.jetbrains.annotations.NotNull;

public record Notice(
        long id,
        String title,
        String fileName,
        boolean published,
        NoticeLevel level,
        Long publishedAt,
        Long expiresInMinutes
) implements Comparable<Notice>{
    @Override
    public int compareTo(@NotNull Notice other) {
        return Long.compare(this.id, other.id);
    }

    public enum NoticeLevel {
        INFO, UPDATE, IMPORTANT, MAINTENANCE
    }

    public boolean isActive() {
        if (!published) {
            return false;
        }

        if (expiresInMinutes == null) {
            return true;
        }

        return System.currentTimeMillis()
                < publishedAt + expiresInMinutes * 60_000L;
    }

    public boolean valid() {
        return title != null && !title.isEmpty()
                && fileName != null && !fileName.isEmpty();
    }
}
