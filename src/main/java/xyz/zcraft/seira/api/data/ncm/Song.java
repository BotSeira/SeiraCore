package xyz.zcraft.seira.api.data.ncm;

import java.util.List;

public record Song(
        Album album,
        int fee,
        long duration,
        int rtype,
        int ftype,
        List<Artist> artists,
        long copyrightId,
        long mvid,
        String name,
        List<String> alias,
        long id,
        long mark,
        int status
) {
}
