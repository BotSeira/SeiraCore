package xyz.zcraft.seira.api.data.ncm;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public record Song(
        Album album,
        int fee,
        long duration,
        int rtype,
        int ftype,
        @SerializedName(value = "artists", alternate = {"ar"}) List<Artist> artists,
        long copyrightId,
        long mvid,
        String name,
        List<String> alias,
        long id,
        long mark,
        int status
) {
}
