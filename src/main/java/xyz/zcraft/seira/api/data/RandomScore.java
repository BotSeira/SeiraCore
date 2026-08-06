package xyz.zcraft.seira.api.data;

import xyz.zcraft.osu.model.Score;
import xyz.zcraft.osu.model.UserExtended;

public record RandomScore(UserExtended user, Score score, int bestIndex) {
}
