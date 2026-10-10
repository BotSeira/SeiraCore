package xyz.zcraft.seira.api.data.ncm;

public record Album(
        long publishTime,
        int size,
        Artist artist,
        long copyrightId,
        String name,
        long id,
        long picId,
        String picUrl,
        long mark,
        int status
) {
}
