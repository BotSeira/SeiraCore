package xyz.zcraft.seira.command.parse;

import xyz.zcraft.seira.api.data.SearchQuery;
import xyz.zcraft.seira.command.ResolutionException;
import xyz.zcraft.seira.db.UserDataStore;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Resolver {
    private final java.util.function.Function<String, Long> boundUid;

    public Resolver() {
        this(UserDataStore::findBoundUid);
    }

    public Resolver(java.util.function.Function<String, Long> boundUid) {
        this.boundUid = Objects.requireNonNull(boundUid);
    }

    public String sanitize(String rawContent) {
        // Add surrounding space to <@> before expanding compact commands so /bp5<@...> is recognized.
        rawContent = Patterns.QQ_INLINE_AT_PATTERN.matcher(rawContent).replaceAll(r -> " " + r.group() + " ");

        rawContent = Pattern.compile("(\\d+)(\\+)").matcher(rawContent).replaceAll(r -> r.group(1) + " " + r.group(2));

        Matcher matcher = Patterns.COMPACT_SCORE_COMMAND_PATTERN.matcher(rawContent);
        if (matcher.find()) {
            String type = matcher.group(1).toLowerCase(Locale.ROOT);
            String start = matcher.group(2);
            String end = matcher.group(3);
            String remaining = rawContent.substring(matcher.end());
            if (end == null) {
                String player = remaining.trim();
                rawContent = player.isEmpty()
                        ? "s " + type + start
                        : "s " + player + " " + type + start;
            } else {
                rawContent = type + " " + start + "-" + end + remaining;
            }
        }

        matcher = Patterns.SPACE_MISSING_COMMAND_PATTERN.matcher(rawContent);
        if (matcher.find()) {
            String command = matcher.group(1).toLowerCase(Locale.ROOT);
            String target = matcher.group(2);
            String remaining = rawContent.substring(matcher.end());
            rawContent = command + " " + target + " " + remaining;
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

    public String player(String argument, String senderUserId) {
        if (argument == null) {
            Long uid = resolveBoundUid(senderUserId);
            if (uid == null) throw new ResolutionException("你还没有绑定玩家ID，请先使用 /bind");
            return uid.toString();
        }
        String mentioned = extractMentionedUserId(argument);
        if (mentioned != null) {
            Long uid = resolveBoundUid(mentioned);
            if (uid == null) throw new ResolutionException("被@的用户还没有绑定玩家ID，请先让对方使用 /bind");
            return uid.toString();
        }
        String player = argument.trim();
        if (player.startsWith("@")) player = player.substring(1);
        if (player.isBlank()) throw new ResolutionException("无法识别指定的玩家");
        return player;
    }

    public Long resolveBoundUid(String senderUserId) {
        if (senderUserId == null || senderUserId.isBlank()) {
            return null;
        }
        return boundUid.apply(senderUserId);
    }

    public Integer parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public boolean looksLikeMention(String token) {
        String trimmed = token == null ? "" : token.trim();
        return trimmed.startsWith("@")
                || trimmed.startsWith("[CQ:at,")
                || (trimmed.startsWith("<@") && trimmed.endsWith(">"));
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

    public Set<String> extractAllMentionedIds(String token) {
        if (token == null) {
            return Set.of();
        }

        Set<String> result = new HashSet<>();

        Patterns.QQ_AT_IDS_PATTERN.matcher(token).results().forEach(m -> result.add(m.group(1)));

        return result;
    }

    public Long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public boolean looksLikeUid(String arg) {
        return Pattern.compile("^\\d+$").matcher(arg).matches();
    }

    private static final class Patterns {
        private static final Pattern COMPACT_SCORE_COMMAND_PATTERN = Pattern.compile(
                "(?i)^(rs|rp|bp)(\\d+)(?:-(\\d+))?(?=\\s|$)"
        );
        private static final Pattern SPACE_MISSING_COMMAND_PATTERN = Pattern.compile(
                "^([a-zA-Z]+)(\\d+(?:#\\d+)?)"
        );
        private static final Pattern QQ_AT_PATTERN = Pattern.compile("^<@([A-Z0-9]{32})>$");
        private static final Pattern QQ_AT_IDS_PATTERN = Pattern.compile("<@([A-Z0-9]{32})>");
        private static final Pattern QQ_INLINE_AT_PATTERN = Pattern.compile("(<@[A-Z0-9]{32}>)");
        private static final Pattern PLAIN_AT_PATTERN = Pattern.compile("^@(\\d+)$");
        private static final Pattern SEARCH_PATTERN = Pattern.compile("^(?:#(\\d+) )?(.+)$");
    }
}

