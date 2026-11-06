package xyz.zcraft.seira.challenge;

import xyz.zcraft.seira.challenge.ChallengeModels.Draft;
import xyz.zcraft.seira.challenge.ChallengeModels.Round;

import java.util.List;

public interface ChallengeStore {
    List<Round> load();

    void save(Round round);

    List<Draft> loadDrafts();

    void saveDraft(Draft draft);
}
