package xyz.zcraft.seira.command.target;

import java.util.List;

public interface TargetLookup {
    long beatmap(TargetQuery query, String auth);
    long beatmapset(TargetQuery query, String auth);
    String score(TargetQuery query, List<String> filters);
}
