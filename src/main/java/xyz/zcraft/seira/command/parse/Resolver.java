package xyz.zcraft.seira.command.parse;

import xyz.zcraft.seira.api.data.SearchQuery;
import xyz.zcraft.seira.binding.UserDataStore;
import xyz.zcraft.seira.data.UserRef;

import java.util.*;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Resolver {
    private static final ArrayList<String> USER_MACRO_TYPES = new ArrayList<>(List.of("rs", "bo", "rp"));

    public String preProcess(String rawContent) {
        Matcher matcher = Patterns.USER_MACRO_PATTERN.matcher(rawContent);
        if (matcher.matches()) {
            return "s " + rawContent;
        }

        return rawContent;
    }

    public SearchQuery resolveSearchQuery(String arg) {
        final Optional<MatchResult> first = Patterns.SEARCH_PATTERN.matcher(arg.trim()).results().findFirst();
        if (first.isPresent()) {
            final MatchResult m = first.get();
            String pagePart = m.group(1);
            String queryPart = m.group(2);
            if (pagePart != null) {
                Integer page = parsePositiveInt(pagePart);
                if (page != null) {
                    return new SearchQuery(page, queryPart);
                }
            }
            return new SearchQuery(1, queryPart);
        }

        return null;
    }

    public TargetResolution resolveTargetWithOptionalMention(String[] args, String senderUserId) {
        if (args.length >= 2 && isUserMacro(args[1])) {
            String mentionedUserId = extractMentionedUserId(args[0]);
            if (mentionedUserId != null) {
                Long boundUid = resolveBoundUid(mentionedUserId);
                if (boundUid == null) {
                    return new TargetResolution(new ShortcutTarget(null, null, null, null,
                            "被@的用户还没有绑定玩家ID，无法使用快捷查询。"), 2);
                }
                return new TargetResolution(parseTarget(args[1], new UserRef.ByUid(boundUid), true, false), 2);
            }

            Long uid = parsePositiveLong(args[0]);
            if (uid != null) {
                return new TargetResolution(parseTarget(args[1], new UserRef.ByUid(uid), false, false), 2);
            }

            if (!looksLikeMention(args[0]) && !args[0].isBlank()) {
                return new TargetResolution(parseTarget(args[1], new UserRef.ByUsername(args[0]), false, false), 2);
            }

//            if (looksLikeMention(args[0])) {
//                return new TargetResolution(new ShortcutTarget(null, null, null, null, "@用户格式无效，请使用@用户后再输入快捷查询（如 rs2）。"), 2);
//            }
        }
        return new TargetResolution(parseTarget(args[0], senderUserId), 1);
    }

    public ShortcutTarget parseTarget(String arg, String senderUserId) {
        Long boundUid = resolveBoundUid(senderUserId);
        UserRef userRef = boundUid == null ? null : new UserRef.ByUid(boundUid);
        return parseTarget(arg, userRef, false, true);
    }

    public UserRefResolution resolveUserRefArgument(String arg) {
        Long explicitUid = parsePositiveLong(arg);
        if (explicitUid != null) {
            return new UserRefResolution(new UserRef.ByUid(explicitUid), null);
        }

        String mentionedUserId = extractMentionedUserId(arg);
        if (mentionedUserId != null) {
            Long boundUid = resolveBoundUid(mentionedUserId);
            if (boundUid == null) {
                return new UserRefResolution(null, "被@的用户还没有绑定玩家ID，请先让对方使用 /bind <玩家ID>");
            }
            return new UserRefResolution(new UserRef.ByUid(boundUid), null);
        }

        if (looksLikeMention(arg)) {
            return new UserRefResolution(null, "@用户格式无效，请使用 @用户 后再输入指令。示例：/bo 5 @123456");
        }

        String username = arg == null ? "" : arg.trim();
        return new UserRefResolution(username.isEmpty() ? null : new UserRef.ByUsername(username), null);
    }

    public RscTarget resolveRscTarget(String groupId, String extraUidArg) {
        Set<String> merged = new LinkedHashSet<>();

        if (extraUidArg == null || extraUidArg.trim().startsWith("+")) {
            List<Long> groupBoundUids = UserDataStore.findBoundUidsByGroup(groupId);
            if (groupBoundUids.isEmpty()) {
                return new RscTarget(null, "本群还没有已绑定的玩家，请先使用 /bind <玩家ID>");
            }
            groupBoundUids.stream().map(String::valueOf).forEach(merged::add);
        }

        if (extraUidArg == null) return new RscTarget(merged.toArray(String[]::new), null);

        String trimmed = extraUidArg.trim();
        String body = trimmed.substring(1).trim();
        if (body.isEmpty()) {
            return new RscTarget(null, "追加ID列表不能为空。");
        }

        String[] extraTokens = body.split(",");
        for (String token : extraTokens) {
            if (!Patterns.RSC_TARGET_PATTERN.matcher(token.trim()).matches()) {
                return new RscTarget(null, "追加ID列表包含非法值。");
            }
            merged.add(token.trim());
        }

        return new RscTarget(merged.toArray(String[]::new), null);
    }

    public Long resolveBoundUid(String senderUserId) {
        if (senderUserId == null || senderUserId.isBlank()) {
            return null;
        }
        return UserDataStore.findBoundUid(senderUserId);
    }

    public Integer parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private ShortcutTarget parseTarget(String arg, UserRef userRef, boolean mentionedUser, boolean needResolveBound) {
        Matcher setMatcher = Patterns.SET_MACRO_PATTERN.matcher(arg.trim());
        if (setMatcher.matches()) {
            Long setId = parsePositiveLong(setMatcher.group(1));
            Long index = parsePositiveLong(setMatcher.group(2));

            if (setId == null || index == null || index < 1) {
                return new ShortcutTarget(null, null, null, null, "谱面集索引无效。例如: 12345#2");
            }

            return new ShortcutTarget(setId, userRef, "ms", index, null);
        }

        Matcher userMatcher = Patterns.USER_MACRO_PATTERN.matcher(arg.trim());
        if (userMatcher.matches()) {
            String type = userMatcher.group(1).toLowerCase();

            if (Objects.equals("bp", type)) {
                type = "bo";
            }

            if (!USER_MACRO_TYPES.contains(type)) {
                return new ShortcutTarget(null, null, null, null, "未知的快捷查询");
            }

            Long index = parsePositiveLong(userMatcher.group(2));

            if (index == null) {
                index = 1L;
            }

            if (index < 1 || index > 100) {
                return new ShortcutTarget(null, null, null, null, "快捷指令索引无效，请输入 1-100 之间的数字。例如: rs5");
            }

            if (userRef == null) {
                String errorMessage;
                if (needResolveBound) {
                    errorMessage = mentionedUser
                            ? "被@的用户还没有绑定玩家ID，无法使用快捷查询。"
                            : "你还没有绑定玩家ID，无法使用快捷查询。请先使用 /bind";
                } else {
                    errorMessage = "无法识别指定的玩家ID";
                }

                return new ShortcutTarget(null, null, null, null, errorMessage);
            }

            return new ShortcutTarget(null, userRef, type, index, null);
        }

        if ("mp".equalsIgnoreCase(arg.trim())) {
            if (userRef == null) {
                String errorMessage;
                if (needResolveBound) {
                    errorMessage = mentionedUser
                            ? "被@的用户还没有绑定玩家ID，无法使用快捷查询。"
                            : "你还没有绑定玩家ID，无法使用快捷查询。请先使用 /bind";
                } else {
                    errorMessage = "无法识别指定的玩家ID";
                }

                return new ShortcutTarget(null, null, null, null, errorMessage);
            }

            return new ShortcutTarget(null, userRef, "mp", null, null);
        }

        Matcher beatmapMatcher = Patterns.BEATMAP_MACRO_PATTERN.matcher(arg.trim());
        if (beatmapMatcher.matches()) {
            Long mapId = parsePositiveLong(beatmapMatcher.group(1));
            return new ShortcutTarget(mapId, userRef, "m", null, null);
        }

        if (Patterns.LOCAL_SCORE_PATTERN.matcher(arg.trim()).matches()) {
            return ShortcutTarget.localScore(arg.trim().toLowerCase(Locale.ROOT));
        }

        Long id = parsePositiveLong(arg);
        if (id == null) {
            return new ShortcutTarget(null, null, null, null,
                    "参数无效。请输入数字ID、本地成绩ID或快捷指令 (例如 loc123456789, rs1, 12345#2)。");
        }

        return new ShortcutTarget(id, null, null, null, null);
    }

    private boolean isUserMacro(String arg) {
        return Patterns.USER_MACRO_PATTERN.matcher(arg.trim()).matches();
    }

    private boolean looksLikeMention(String token) {
        String trimmed = token == null ? "" : token.trim();
        return trimmed.startsWith("@") || trimmed.startsWith("[CQ:at,");
    }

    public String extractMentionedUserId(String token) {
        if (token == null) {
            return null;
        }

        String trimmed = token.trim();
        Matcher qqMatcher = Patterns.QQ_AT_PATTERN.matcher(trimmed);
        if (qqMatcher.matches()) {
            return qqMatcher.group(1);
        }

        Matcher plainMatcher = Patterns.PLAIN_AT_PATTERN.matcher(trimmed);
        if (plainMatcher.matches()) {
            return plainMatcher.group(1);
        }

        return null;
    }

    public Long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static final class Patterns {
        private static final Pattern USER_MACRO_PATTERN = Pattern.compile("(?i)^(rs|bo|rp|bp)(\\d+)?$");
        private static final Pattern SET_MACRO_PATTERN = Pattern.compile("^(\\d+)#(\\d+)$");
        private static final Pattern BEATMAP_MACRO_PATTERN = Pattern.compile("^m(\\d+)$");
        private static final Pattern LOCAL_SCORE_PATTERN = Pattern.compile("(?i)^loc[1-9]\\d*$");
        private static final Pattern QQ_AT_PATTERN = Pattern.compile("^<@([A-Z|0-9]{32})>$");
        private static final Pattern PLAIN_AT_PATTERN = Pattern.compile("^@(\\d+)$");
        private static final Pattern SEARCH_PATTERN = Pattern.compile("^(?:#(\\d+) )?(.+)$");
        private static final Pattern RSC_TARGET_PATTERN = Pattern.compile("^[us]?\\d+$");
    }
}

