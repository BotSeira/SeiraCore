package xyz.zcraft.seira.challenge;

import static xyz.zcraft.seira.challenge.ChallengeModels.*;

public interface ChallengeApi {
    SetData getBeatmapset(long id);

    SetData getBeatmapsetForMap(long id);

    SkillData getSkill(long userId, long excludedSetId);

    ScoreData getScore(long id);
}
