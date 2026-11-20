package xyz.zcraft.seira.bot.data;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@FunctionalInterface
public interface FileUploader {
    @NotNull
    FileInfo upload(@NotNull String target, @NotNull String sourceUrl, @Nullable String fileName);
}
