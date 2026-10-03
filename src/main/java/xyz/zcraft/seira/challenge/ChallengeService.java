package xyz.zcraft.seira.challenge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.watch.RecentScore;
import xyz.zcraft.seira.watch.RecentScoreListener;
import xyz.zcraft.seira.watch.ScoreWatchApi;

import java.time.Clock;
import java.time.Duration;
import java.util.*;

import static xyz.zcraft.seira.challenge.ChallengeModels.*;

public final class ChallengeService implements RecentScoreListener {
    public static final int DEFAULT_HOURS = 24;
    public static final int MAX_HOURS = 168;
    public static final int MAX_PARTICIPANTS = 100;
    private static final Logger LOG = LogManager.getLogger(ChallengeService.class);
    private final ChallengeApi api;
    private final ChallengeStore store;
    private final ScoreWatchApi watchApi;
    private final Eligibility eligibility;
    private final Notifier notifier;
    private final Clock clock;
    private final Map<String, Round> rounds = new LinkedHashMap<>();
    private final Map<String, String> latestByGroup = new HashMap<>();
    private final Map<String, Draft> drafts = new HashMap<>();
    private final List<Long> randomPool;

    public ChallengeService(ChallengeApi api, ChallengeStore store, ScoreWatchApi watchApi,
                            Eligibility eligibility, Notifier notifier) {
        this(api, store, watchApi, eligibility, notifier, Clock.systemUTC());
    }

    public ChallengeService(ChallengeApi api, ChallengeStore store, ScoreWatchApi watchApi,
                            Eligibility eligibility, Notifier notifier, Clock clock) {
        this(api, store, watchApi, eligibility, notifier, clock, loadRandomPool());
    }

    public ChallengeService(ChallengeApi api, ChallengeStore store, ScoreWatchApi watchApi,
                            Eligibility eligibility, Notifier notifier, Clock clock, List<Long> randomPool) {
        this.api = Objects.requireNonNull(api);
        this.store = Objects.requireNonNull(store);
        this.watchApi = Objects.requireNonNull(watchApi);
        this.eligibility = Objects.requireNonNull(eligibility);
        this.notifier = Objects.requireNonNull(notifier);
        this.clock = Objects.requireNonNull(clock);
        this.randomPool = List.copyOf(randomPool);
        for (Round round : store.load()) {
            rounds.put(round.id(), round);
            latestByGroup.put(round.groupId(), round.id());
        }
        for (Draft draft : store.loadDrafts()) drafts.put(draft.groupId(), draft);
    }

    private static List<Long> loadRandomPool() {
        try (var stream = ChallengeService.class.getResourceAsStream("/beatmapset-ids.json")) {
            if (stream == null) throw new IllegalStateException("随机谱面池不存在喵。");
            Long[] ids = new com.google.gson.Gson().fromJson(new java.io.InputStreamReader(stream,
                    java.nio.charset.StandardCharsets.UTF_8), Long[].class);
            return List.of(ids);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取随机谱面池失败。", e);
        }
    }

    public static boolean ready(Draft draft) {
        return draft.beatmapset() != null && !draft.settings().choices(draft.beatmapset()).isEmpty()
                && (draft.settings().differentDifficulties() || draft.settings().fixedMapId() != null);
    }

    private static void requireDraftOwner(Draft draft, String sender, boolean admin) {
        if (!draft.owner().equals(sender) && !admin)
            throw new IllegalArgumentException("只有配置创建者或机器人管理员可以修改和启动挑战喵。");
    }

    private static void requireRevision(Draft draft, String id, Long revision) {
        if (id != null && (!draft.id().equals(id) || !Objects.equals(draft.revision(), revision)))
            throw new IllegalArgumentException("该配置按钮已过期，请重新执行 /gch configure 使用最新按钮喵。");
    }

    private static long positive(String text) {
        try {
            long value = Long.parseLong(text);
            if (value > 0) return value;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("ID 和时长必须为正整数喵。");
    }

    private static String validate(Round round, Participant member, ScoreData score) {
        if (score.rejection() != null) return score.rejection();
        if (score.userId() != member.userId()) return "只能提交自己的成绩喵。";
        if (score.beatmapsetId() != round.beatmapset().id()
                || round.beatmapset().maps().stream().noneMatch(map -> map.id() == score.beatmapId()))
            return "成绩不属于本次挑战的可选难度喵。";
        String ruleRejection = round.rules().rejection(round.beatmapset(), score);
        if (ruleRejection != null) return ruleRejection;
        if (score.endedAt() < Math.max(round.startedAt(), member.joinedAt()) || score.endedAt() >= round.endsAt())
            return "成绩必须在报名后、挑战结束前完成喵。";
        if (!Double.isFinite(score.stars()) || score.stars() <= 0 || score.totalScore() <= 0
                || !Double.isFinite(score.accuracy()) || score.accuracy() < 0 || score.accuracy() > 1 || score.maxCombo() < 0)
            return "成绩数据无效喵。";
        return null;
    }

    private static void requireGroup(String group) {
        if (group == null || group.isBlank()) throw new IllegalArgumentException("/gch 仅支持群聊使用。");
    }

    public synchronized Draft configure(String group, String owner, boolean reset, boolean admin) {
        requireGroup(group);
        Draft current = drafts.get(group);
        if (current != null && !reset) return current;
        if (current != null) requireDraftOwner(current, owner, admin);
        Draft next = new Draft("cfg-" + UUID.randomUUID().toString().substring(0, 8), group, owner, 0,
                null, false, DEFAULT_HOURS, ChallengeSettings.defaults());
        saveDraft(next);
        return next;
    }

    public Draft edit(String group, String sender, boolean admin, String draftId, Long revision, String key, String value) {
        Draft before;
        synchronized (this) {
            before = requireDraft(group);
            requireDraftOwner(before, sender, admin);
            requireRevision(before, draftId, revision);
        }
        SetData set = before.beatmapset();
        ChallengeSettings settings = before.settings();
        boolean random = before.randomMap();
        int hours = before.hours();
        switch (key.toLowerCase(Locale.ROOT)) {
            case "set" -> {
                set = api.getBeatmapset(positive(value));
                random = false;
                settings = settings.withMap(null);
            }
            case "map" -> {
                long mapId = positive(value);
                set = api.getBeatmapsetForMap(mapId);
                if (settings.choices(set).stream().noneMatch(map -> map.id() == mapId))
                    throw new IllegalArgumentException("谱面不是合格的 osu!standard 难度，或不在星数范围内喵。");
                settings = new ChallengeSettings(false, mapId, settings.requiredMods(), settings.bannedMods(),
                        settings.minStars(), settings.maxStars(), settings.skillAdjustment());
                random = false;
            }
            case "random", "reroll" -> {
                set = randomSet(settings);
                var choices = settings.choices(set);
                settings = settings.withMap(choices.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(choices.size())).id());
                random = true;
            }
            case "hours" -> {
                long parsed = positive(value);
                if (parsed > MAX_HOURS) throw new IllegalArgumentException("挑战时长为 `1–168` 小时喵。");
                hours = (int) parsed;
            }
            case "difficulty" -> settings = new ChallengeSettings(switch (value.toLowerCase(Locale.ROOT)) {
                case "any", "on" -> true;
                case "fixed", "off" -> false;
                default -> throw new IllegalArgumentException("难度设置应为 `any` 或 `fixed` 喵。");
            }, settings.fixedMapId(), settings.requiredMods(), settings.bannedMods(), settings.minStars(), settings.maxStars(), settings.skillAdjustment());
            case "required" -> settings = new ChallengeSettings(settings.differentDifficulties(), settings.fixedMapId(),
                    ChallengeSettings.parseMods(value, true), settings.bannedMods(), settings.minStars(), settings.maxStars(), settings.skillAdjustment());
            case "banned" -> settings = new ChallengeSettings(settings.differentDifficulties(), settings.fixedMapId(),
                    settings.requiredMods(), ChallengeSettings.parseMods(value, false), settings.minStars(), settings.maxStars(), settings.skillAdjustment());
            case "stars" -> {
                String[] range = value.split("[-:]", -1);
                if (range.length != 2) throw new IllegalArgumentException("星数范围请写成 `3-7` 喵。");
                settings = new ChallengeSettings(settings.differentDifficulties(), settings.fixedMapId(), settings.requiredMods(),
                        settings.bannedMods(), Double.parseDouble(range[0]), Double.parseDouble(range[1]), settings.skillAdjustment());
            }
            case "skill" ->
                    settings = new ChallengeSettings(settings.differentDifficulties(), settings.fixedMapId(), settings.requiredMods(),
                            settings.bannedMods(), settings.minStars(), settings.maxStars(), switch (value.toLowerCase(Locale.ROOT)) {
                        case "on" -> true;
                        case "off" -> false;
                        default -> throw new IllegalArgumentException("水平调整应为 `on` 或 `off` 喵。");
                    });
            default -> throw new IllegalArgumentException("未知设置：" + key);
        }
        if (set != null) {
            List<MapChoice> choices = settings.choices(set);
            Long fixed = settings.fixedMapId();
            if (choices.stream().noneMatch(map -> Objects.equals(map.id(), fixed)))
                settings = settings.withMap(choices.isEmpty() ? null : choices.get(choices.size() / 2).id());
        }
        Draft next = new Draft(before.id(), group, before.owner(), before.revision() + 1, set, random, hours, settings);
        synchronized (this) {
            Draft current = requireDraft(group);
            requireRevision(current, before.id(), before.revision());
            saveDraft(next);
        }
        return next;
    }

    private SetData randomSet(ChallengeSettings settings) {
        var pool = new ArrayList<>(randomPool);
        Collections.shuffle(pool);
        for (long id : pool.stream().limit(30).toList()) {
            try {
                SetData set = api.getBeatmapset(id);
                if (!settings.choices(set).isEmpty()) return set;
            } catch (
                    IllegalArgumentException ignored) { /* Skip deleted/unranked candidates. Network failures propagate. */ }
        }
        throw new IllegalArgumentException("未找到星数范围内的随机谱面，请扩大范围后重新抽取喵。");
    }

    public synchronized Draft getDraft(String group) {
        return drafts.get(group);
    }

    public synchronized Round startConfigured(String group, String sender, boolean admin, String id, Long revision) {
        Draft draft = requireDraft(group);
        requireDraftOwner(draft, sender, admin);
        requireRevision(draft, id, revision);
        requireNoActiveRound(group);
        if (!ready(draft))
            throw new IllegalArgumentException("配置尚未完成：请先选择谱面，并确保星数范围内有可选难度喵。");
        long now = clock.millis();
        Round round = new Round(UUID.randomUUID().toString(), group, draft.owner(), draft.beatmapset(), now,
                now + Duration.ofHours(draft.hours()).toMillis(), false, false, Map.of(), draft.settings());
        persist(round);
        latestByGroup.put(group, round.id());
        pruneCompleted();
        return round;
    }

    private void saveDraft(Draft draft) {
        store.saveDraft(draft);
        drafts.put(draft.groupId(), draft);
    }

    private Draft requireDraft(String group) {
        requireGroup(group);
        Draft draft = drafts.get(group);
        if (draft == null) throw new IllegalArgumentException("请先使用 /gch configure 创建配置喵。");
        return draft;
    }

    public Round start(String groupId, String creator, long setId, int hours) {
        return start(groupId, creator, setId, hours, false);
    }

    public Round start(String groupId, String creator, long setId, int hours, boolean admin) {
        requireGroup(groupId);
        if (creator == null || creator.isBlank()) throw new IllegalArgumentException("缺少挑战发起者喵。");
        if (setId <= 0 || hours < 1 || hours > MAX_HOURS)
            throw new IllegalArgumentException("时长必须为 1–168 小时，谱面集 ID 必须为正整数喵。");
        synchronized (this) {
            requireNoActiveRound(groupId);
            Draft draft = drafts.get(groupId);
            if (draft != null) requireDraftOwner(draft, creator, admin);
        }
        SetData set = api.getBeatmapset(setId);
        if (set.id() != setId || set.maps().isEmpty()) throw new IllegalStateException("谱面集没有可用难度喵。");
        long now = clock.millis();
        Round round = new Round(UUID.randomUUID().toString(), groupId, creator, set, now,
                now + Duration.ofHours(hours).toMillis(), false, false, Map.of(), ChallengeSettings.defaults());
        synchronized (this) {
            requireNoActiveRound(groupId);
            Draft draft = drafts.get(groupId);
            if (draft != null) requireDraftOwner(draft, creator, admin);
            persist(round);
            latestByGroup.put(groupId, round.id());
            pruneCompleted();
        }
        return round;
    }

    public Participant join(String groupId, String openId, long uid) {
        if (uid <= 0 || !eligibility.eligible(groupId, openId, uid))
            throw new IllegalArgumentException("只能使用自己在当前群聊中的绑定账号参赛喵。");
        Round before;
        synchronized (this) {
            before = requireActive(groupId);
            Participant existing = existingParticipant(before, openId, uid);
            if (existing != null) return existing;
            if (before.participants().size() >= MAX_PARTICIPANTS)
                throw new IllegalArgumentException("本次挑战最多允许 100 名玩家喵。");
        }
        SkillData skill = before.rules().skillAdjustment() ? api.getSkill(uid, before.beatmapset().id())
                : new SkillData(Long.toString(uid), 0, 0);
        if (before.rules().skillAdjustment() && (!Double.isFinite(skill.stars()) || skill.stars() <= 0 || skill.samples() < 5))
            throw new IllegalArgumentException("有效 BP 不足，暂时无法估计水平喵。");
        var baseline = watchApi.getRecentScores(List.of(uid), 1);
        if (!baseline.containsKey(uid)) throw new IllegalStateException("无法获取参赛账号的最近成绩喵。");
        Long cursor = baseline.get(uid).stream().findFirst().map(RecentScore::scoreId).orElse(null);
        synchronized (this) {
            Round current = requireActive(groupId);
            if (!current.id().equals(before.id())) throw new IllegalStateException("挑战已更换，请重新加入喵。");
            if (!eligibility.eligible(groupId, openId, uid))
                throw new IllegalArgumentException("绑定或群成员状态已变化，请重新加入喵。");
            Participant existing = existingParticipant(current, openId, uid);
            if (existing != null) return existing;
            if (current.participants().size() >= MAX_PARTICIPANTS)
                throw new IllegalArgumentException("本次挑战人数已满喵。");
            var participant = new Participant(openId, uid, skill.username(), skill.stars(), skill.samples(), clock.millis(), cursor, null);
            updateParticipant(current, participant);
            return participant;
        }
    }

    private Participant existingParticipant(Round round, String openId, long uid) {
        Participant existing = round.participants().get(uid);
        if (existing != null && !existing.openId().equals(openId))
            throw new IllegalArgumentException("这个 osu! 账号已由其他群成员报名喵。");
        if (round.participants().values().stream().anyMatch(p -> p.openId().equals(openId) && p.userId() != uid))
            throw new IllegalArgumentException("同一群成员在本次挑战中不能更换参赛账号喵。");
        return existing;
    }

    public Round submit(String groupId, String openId, long uid, long scoreId) {
        if (scoreId <= 0) throw new IllegalArgumentException("成绩 ID 必须为正整数喵。");
        String roundId;
        synchronized (this) {
            Round round = requireRound(groupId);
            requireParticipant(round, openId, uid);
            if (round.finished()) throw new IllegalArgumentException("本次挑战已经结束喵。");
            roundId = round.id();
        }
        ScoreData score = api.getScore(scoreId);
        synchronized (this) {
            Round round = requireRound(groupId);
            if (!round.id().equals(roundId) || round.finished())
                throw new IllegalArgumentException("本次挑战已经结束或更换。");
            Participant participant = requireParticipant(round, openId, uid);
            String rejection = validate(round, participant, score);
            if (rejection != null) throw new IllegalArgumentException(rejection);
            recordResult(round, participant, score, participant.lastScoreId());
            return rounds.get(round.id());
        }
    }

    public synchronized Round end(String groupId, String sender, boolean admin) {
        Round round = requireRound(groupId);
        if (!round.creator().equals(sender) && !admin)
            throw new IllegalArgumentException("只有挑战发起者或机器人管理员可以结束挑战喵。");
        if (round.finished()) throw new IllegalArgumentException("本次挑战已经结束喵。");
        // Finalize after one last shared poll so plays completed before the cutoff are collected.
        Round stopped = new Round(round.id(), groupId, round.creator(), round.beatmapset(), round.startedAt(),
                Math.min(clock.millis(), round.endsAt()), false, false, round.participants(), round.settings());
        persist(stopped);
        return stopped;
    }

    public synchronized Round get(String groupId) {
        return rounds.get(latestByGroup.get(groupId));
    }

    public long now() {
        return clock.millis();
    }

    @Override
    public synchronized Collection<Long> watchedUserIds() {
        Set<Long> ids = new LinkedHashSet<>();
        rounds.values().stream().filter(round -> !round.finished()).forEach(round -> round.participants().values().stream()
                .filter(p -> eligibility.eligible(round.groupId(), p.openId(), p.userId())).forEach(p -> ids.add(p.userId())));
        return Set.copyOf(ids);
    }

    @Override
    public void acceptRecentScores(Map<Long, List<RecentScore>> scores) {
        List<Round> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(rounds.values());
        }
        Map<Long, ScoreData> details = new HashMap<>();
        for (Round round : snapshot) {
            if (round.finished()) {
                announce(round.id());
                continue;
            }
            boolean complete = true;
            for (Participant participant : round.participants().values()) {
                if (!eligibility.eligible(round.groupId(), participant.openId(), participant.userId())) continue;
                if (!scores.containsKey(participant.userId())) {
                    complete = false;
                    continue;
                }
                List<RecentScore> recent = scores.get(participant.userId());
                int cursor = -1;
                if (participant.lastScoreId() != null)
                    for (int i = 0; i < recent.size(); i++)
                        if (recent.get(i).scoreId() == participant.lastScoreId()) {
                            cursor = i;
                            break;
                        }
                List<RecentScore> unseen = cursor < 0 ? recent : recent.subList(0, cursor);
                for (RecentScore item : unseen.reversed()) {
                    try {
                        ScoreData detail = item.beatmapsetId() == round.beatmapset().id()
                                ? details.computeIfAbsent(item.scoreId(), api::getScore) : null;
                        synchronized (this) {
                            Round current = rounds.get(round.id());
                            if (current.finished()) break;
                            Participant member = current.participants().get(participant.userId());
                            if (!eligibility.eligible(current.groupId(), member.openId(), member.userId())) break;
                            if (detail != null && validate(current, member, detail) == null)
                                recordResult(current, member, detail, item.scoreId());
                            else
                                updateParticipant(current, new Participant(member.openId(), member.userId(), member.username(),
                                        member.skillStars(), member.skillSamples(), member.joinedAt(), item.scoreId(), member.best()));
                        }
                    } catch (RuntimeException e) {
                        complete = false;
                        LOG.warn("Failed to collect challenge score {} for group {}", item.scoreId(), round.groupId(), e);
                        break;
                    }
                }
            }
            synchronized (this) {
                Round current = rounds.get(round.id());
                // A concurrent join must receive a poll before finalisation too.
                if (complete && current.participants().keySet().equals(round.participants().keySet())
                        && clock.millis() >= current.endsAt()) persist(current.finish());
            }
            announce(round.id());
        }
    }

    private synchronized void announce(String id) {
        Round round = rounds.get(id);
        if (round == null || !round.finished() || round.notified()) return;
        try {
            if (notifier.send(round.groupId(), "群挑战已结束！\n" + ChallengeMessages.leaderboard(round, 1))) {
                persist(round.announced());
                pruneCompleted();
            }
        } catch (RuntimeException e) {
            LOG.warn("Failed to announce challenge {}", id, e);
        }
    }

    private Participant requireParticipant(Round round, String openId, long uid) {
        Participant member = round.participants().get(uid);
        if (member == null || !member.openId().equals(openId) || !eligibility.eligible(round.groupId(), openId, uid))
            throw new IllegalArgumentException("请先用自己的当前群绑定账号执行 /gch join。");
        return member;
    }

    private void recordResult(Round round, Participant member, ScoreData score, Long cursor) {
        Result result = round.rules().skillAdjustment() ? ChallengeScoring.calculate(score, member.skillStars())
                : new Result(score, 1, score.totalScore());
        Result best = member.best() == null || ChallengeScoring.compare(result, member.best()) > 0 ? result : member.best();
        updateParticipant(round, new Participant(member.openId(), member.userId(), member.username(), member.skillStars(),
                member.skillSamples(), member.joinedAt(), cursor, best));
    }

    private void updateParticipant(Round round, Participant member) {
        var members = new LinkedHashMap<>(round.participants());
        members.put(member.userId(), member);
        persist(round.withParticipants(members));
    }

    private void persist(Round round) {
        store.save(round);
        rounds.put(round.id(), round);
    }

    private void pruneCompleted() {
        rounds.values().removeIf(round -> round.finished() && round.notified()
                && !round.id().equals(latestByGroup.get(round.groupId())));
    }

    private void requireNoActiveRound(String group) {
        Round current = get(group);
        if (current != null && !current.finished())
            throw new IllegalArgumentException("当前群已有挑战，请等待结束或使用 /gch end。");
    }

    private Round requireActive(String group) {
        Round round = requireRound(group);
        if (round.finished() || clock.millis() >= round.endsAt())
            throw new IllegalArgumentException("挑战已结束或正在结算。");
        return round;
    }

    private Round requireRound(String group) {
        requireGroup(group);
        Round round = get(group);
        if (round == null)
            throw new IllegalArgumentException("当前群还没有挑战，请使用 /gch start <谱面集ID> [小时数]。");
        return round;
    }

    @FunctionalInterface
    public interface Eligibility {
        boolean eligible(String groupId, String openId, long uid);
    }

    @FunctionalInterface
    public interface Notifier {
        boolean send(String groupId, String message);
    }
}
