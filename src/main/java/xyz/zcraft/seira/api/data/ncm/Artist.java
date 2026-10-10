package xyz.zcraft.seira.api.data.ncm;

import java.util.List;

public record Artist(
        String img1v1Url,
        int musicSize,
        int albumSize,
        int img1v1,
        String name,
        List<String> alias,
        long id,
        long picId
) {
}
