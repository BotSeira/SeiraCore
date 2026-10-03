package xyz.zcraft.seira.challenge;

import java.util.List;
import java.util.Map;

public final class ChallengeModels {
    private ChallengeModels() {
    }

    public record MapChoice(long id, String name, double stars) {
    }

    public record SetData(long id, String title, List<MapChoice> maps) {
        public SetData {
            maps = List.copyOf(maps);
        }
    }

    public record SkillData(String username, double stars, int samples) {
    }

    public record ScoreData(long id, long userId, long beatmapId, long beatmapsetId, long totalScore,
                            double stars, double accuracy, long maxCombo, String mods, long endedAt, String rejection) {
    }

    public record Result(ScoreData score, double multiplier, long points) {
    }

    public record Participant(String openId, long userId, String username, double skillStars,
                              int skillSamples, long joinedAt, Long lastScoreId, Result best) {
    }

    public record Draft(String id, String groupId, String owner, long revision, SetData beatmapset,
                        boolean randomMap, int hours, ChallengeSettings settings) {
    }

    public record Round(String id, String groupId, String creator, SetData beatmapset, long startedAt,
                        long endsAt, boolean finished, boolean notified, Map<Long, Participant> participants,
                        ChallengeSettings settings) {
        public Round {
            participants = Map.copyOf(participants);
        }

        public Round withParticipants(Map<Long, Participant> members) {
            return new Round(id, groupId, creator, beatmapset, startedAt, endsAt, finished, notified, members, settings);
        }

        public Round finish() {
            return new Round(id, groupId, creator, beatmapset, startedAt, endsAt, true, false, participants, settings);
        }

        public Round announced() {
            return new Round(id, groupId, creator, beatmapset, startedAt, endsAt, true, true, participants, settings);
        }

        public ChallengeSettings rules() {
            return settings == null ? ChallengeSettings.defaults() : settings;
        }
    }
}
