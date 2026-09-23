package xyz.zcraft.seira.api.data;

import java.util.List;
import java.util.Map;

/*
{
        "_id": "example",
        "osuUser1": null,
        "osuUser2": null,
        "players": ["example","example","example","example","example","example"],
        "teams": {
            "teamA": ["example","example","example"],
            "teamB": ["example","example","example"]
        },
        "tournament": null,
        "selectedPool": null,
        "customBO": 9,
        "customELO": 1335,
        "interactionIds": ["1551915321242239126"],
        "discordChannelId": "1295392984787811380",
        "isMatchmaking": false,
        "lobbyId": "121100588",
        "matchState": "Picking",
        "score": [2,3],
        "bans": ["dt2","hd2","dt1","nm6"],
        "picks": [
            {
                "map": 4722846,
                "mod": "nm2",
                "scores": [733676,342836]
            }
        ],
        "poolOptions": [
            "nm4","nm5","hd1","hd3","hr1","hr2","hr4","dt3","dt4"
        ],
        "availablePools": [
            {
                "maps": {
                    "noMod": [4168602,3596841,5019445,5375902,4706834],
                    "hidden": [4836596,3505413,5074450],
                    "hardRock": [2676188,4641931,5445369],
                    "doubleTime": [5014531,2246430,3632103],
                    "freeMod": [],
                    "tieBreaker": 5384525
                },
                "_id": "696d20ff8f8d4a2449abe8d1",
                "name": "example",
                "elo": 1334,
                "__v": 0
            }
        ],
        "poolInfoName": "Example Tournament",
        "firstPick": "example",
        "secondPick": "example",
        "currentMapId": 5341088,
        "currentMapMod": "hr",
        "timerEndsAt": "2026-09-22T15:09:19.111Z",
        "startedAt": "2026-09-22T14:36:47.445Z",
        "__v": 40,
        "playerData": [
            {
                "osuUserName": "example",
                "osuUserId": 12345678,
                "elo": {"3v3": 1300,"1v1": 1300,"2v2": 1300},
                "country": "CN"
            }
        ],
        "mode": "3v3",
        "avgElo": 1251,
        "isLeagueMatch": false
    }
 */
public record RomAIMatch(
        List<String> players,
        Teams teams,
        String lobbyId,
        List<Integer> score,
        Long currentMapId,
        String mode,
        List<PlayerData> playerData,
        Integer customBO,
        Integer customELO
) {

    public record Teams(List<String> teamA, List<String> teamB){}

    public record PlayerData(
            String osuUserName,
            Long osuUserId,
            Map<String, Integer> elo,
            String country
    ){}
}
