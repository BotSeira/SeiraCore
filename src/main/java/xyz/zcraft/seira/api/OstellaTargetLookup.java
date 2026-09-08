package xyz.zcraft.seira.api;

import xyz.zcraft.seira.command.target.*;
import java.util.List;

public final class OstellaTargetLookup implements TargetLookup {
    @Override
    public long beatmap(TargetQuery query, String auth) {
        return APIHelper.lookupBeatmap(TargetAdapter.toShortcut(query), auth);
    }

    @Override
    public long beatmapset(TargetQuery query, String auth) {
        return APIHelper.lookupBeatmapset(TargetAdapter.toShortcut(query), auth);
    }

    @Override
    public String score(TargetQuery query, List<String> filters) {
        return APIHelper.lookupScoreId(TargetAdapter.toShortcut(query), filters);
    }
}
