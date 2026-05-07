package xyz.zcraft.command;

import xyz.zcraft.binding.UserBindingStore;
import xyz.zcraft.command.resolution.TargetResolution;
import xyz.zcraft.command.resolution.UidListResolution;
import xyz.zcraft.command.resolution.UidResolution;
import xyz.zcraft.command.resolution.ShortcutTarget;
import xyz.zcraft.data.SearchQuery;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Resolver {
    private static final Pattern USER_MACRO_PATTERN = Pattern.compile("(?i)^(rs|bo)(\\d+)$");
    private static final Pattern SET_MACRO_PATTERN = Pattern.compile("^(\\d+)#(\\d+)$");
    private static final Pattern BEATMAP_MACRO_PATTERN = Pattern.compile("^m(\\d+)$");
    private static final Pattern CQ_AT_PATTERN = Pattern.compile("^\\[CQ:at,qq=(\\d+)(?:,.*)?]$");
    private static final Pattern PLAIN_AT_PATTERN = Pattern.compile("^@(\\d+)$");
    private static final Pattern SEARCH_PATTERN = Pattern.compile("^(?:#(\\d+) )?(.+)$");

    private final String rscUsage;

    Resolver(String rscUsage) {
        this.rscUsage = rscUsage;
    }

    public SearchQuery resolveSearchQuery(String arg) {
        final Optional<MatchResult> first = SEARCH_PATTERN.matcher(arg.trim()).results().findFirst();
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
                return new TargetResolution(parseTarget(args[1], mentionedUserId, true), 2);
            }
            if (looksLikeMention(args[0])) {
                return new TargetResolution(new ShortcutTarget(null, null, null, null, "@用户格式无效，请使用@用户后再输入快捷查询（如 rs2）。"), 2);
            }
        }
        return new TargetResolution(parseTarget(args[0], senderUserId), 1);
    }

    public ShortcutTarget parseTarget(String arg, String senderUserId) {
        return parseTarget(arg, senderUserId, false);
    }

    public UidResolution resolveUidArgument(String arg) {
        Integer explicitUid = parsePositiveInt(arg);
        if (explicitUid != null) {
            return new UidResolution(explicitUid, null);
        }

        String mentionedUserId = extractMentionedUserId(arg);
        if (mentionedUserId != null) {
            Integer boundUid = resolveBoundUid(mentionedUserId);
            if (boundUid == null) {
                return new UidResolution(null, "被@的用户还没有绑定玩家ID，请先让对方使用 /bind <玩家ID>");
            }
            return new UidResolution(boundUid, null);
        }

        if (looksLikeMention(arg)) {
            return new UidResolution(null, "@用户格式无效，请使用 @用户 后再输入指令。示例：/bo 5 @123456");
        }

        return new UidResolution(null, null);
    }

    public UidListResolution resolveRscUidList(String groupId, String extraUidArg) {
        List<Integer> groupBoundUids = UserBindingStore.findBoundUidsByGroup(groupId);
        if (groupBoundUids.isEmpty()) {
            return new UidListResolution(null, "本群还没有已绑定的玩家，请先使用 /bind <玩家ID>");
        }

        Set<String> merged = new LinkedHashSet<>();
        groupBoundUids.stream().map(String::valueOf).forEach(merged::add);

        if (extraUidArg != null) {
            String trimmed = extraUidArg.trim();
            if (!trimmed.startsWith("+")) {
                return new UidListResolution(null, "追加用户ID列表必须以 + 开头。" + rscUsage);
            }
            String body = trimmed.substring(1).trim();
            if (body.isEmpty()) {
                return new UidListResolution(null, "追加用户ID列表不能为空。" + rscUsage);
            }

            String[] extraTokens = body.split(",");
            for (String token : extraTokens) {
                Integer uid = parsePositiveInt(token.trim());
                if (uid == null) {
                    return new UidListResolution(null, "追加用户ID列表包含非法值。" + rscUsage);
                }
                merged.add(String.valueOf(uid));
            }
        }

        return new UidListResolution(merged.toArray(String[]::new), null);
    }

    public Integer resolveBoundUid(String senderUserId) {
        if (senderUserId == null || senderUserId.isBlank()) {
            return null;
        }
        return UserBindingStore.findBoundUid(senderUserId);
    }

    public Integer parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public ShortcutTarget parseTarget(String arg, String senderUserId, boolean mentionedUser) {
        Matcher setMatcher = SET_MACRO_PATTERN.matcher(arg.trim());
        if (setMatcher.matches()) {
            Long setId = parsePositiveLong(setMatcher.group(1));
            Long index = parsePositiveLong(setMatcher.group(2));

            if (setId == null || index == null || index < 1) {
                return new ShortcutTarget(null, null, null, null, "铺面集索引无效。例如: 12345#2");
            }

            Integer uid = resolveBoundUid(senderUserId);
            return new ShortcutTarget(setId, uid, "ms", index, null);
        }

        Matcher userMatcher = USER_MACRO_PATTERN.matcher(arg.trim());
        if (userMatcher.matches()) {
            String type = userMatcher.group(1).toLowerCase();
            Long index = parsePositiveLong(userMatcher.group(2));

            if (index == null || index < 1 || index > 100) {
                return new ShortcutTarget(null, null, null, null, "快捷指令索引无效，请输入 1-100 之间的数字。例如: rs5");
            }

            Integer uid = resolveBoundUid(senderUserId);
            if (uid == null) {
                String errorMessage = mentionedUser
                        ? "被@的用户还没有绑定玩家ID，无法使用快捷查询。"
                        : "你还没有绑定玩家ID，无法使用快捷查询。请先使用 /bind <玩家ID>";
                return new ShortcutTarget(null, null, null, null, errorMessage);
            }

            return new ShortcutTarget(null, uid, type, index, null);
        }

        Matcher beatmapMatcher = BEATMAP_MACRO_PATTERN.matcher(arg.trim());
        if (beatmapMatcher.matches()) {
            Long mapId = parsePositiveLong(beatmapMatcher.group(1));
            Integer uid = resolveBoundUid(senderUserId);
            return new ShortcutTarget(mapId, uid, "m", null, null);
        }

        Long id = parsePositiveLong(arg);
        if (id == null) {
            return new ShortcutTarget(null, null, null, null, "参数无效。请输入纯数字ID或快捷指令 (例如 rs1, 12345#2)。");
        }

        return new ShortcutTarget(id, null, null, null, null);
    }

    private boolean isUserMacro(String arg) {
        return USER_MACRO_PATTERN.matcher(arg.trim()).matches();
    }

    private boolean looksLikeMention(String token) {
        String trimmed = token == null ? "" : token.trim();
        return trimmed.startsWith("@") || trimmed.startsWith("[CQ:at,");
    }

    private String extractMentionedUserId(String token) {
        if (token == null) {
            return null;
        }

        String trimmed = token.trim();
        Matcher cqMatcher = CQ_AT_PATTERN.matcher(trimmed);
        if (cqMatcher.matches()) {
            return cqMatcher.group(1);
        }

        Matcher plainMatcher = PLAIN_AT_PATTERN.matcher(trimmed);
        if (plainMatcher.matches()) {
            return plainMatcher.group(1);
        }

        return null;
    }

    private Long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}

