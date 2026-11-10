package xyz.zcraft.seira.agent;

final class ServiceToolDefinitions {
    private ServiceToolDefinitions() {
    }

    static void register(ServiceTools service) {
        service.declare("ostella", "/beatmaps/lookup",
                "ostella_get_beatmaps_lookup",
                "根据 ID、成绩引用或用户成绩序号解析谱面 ID\n查询参数按以下几种方式使用：m 解析谱面，ms+i 解析谱面集 ms 中的第 i 个谱面，\nof+i+u 解析用户 u 的 bp/rs/rp 的第 i 个成绩对应谱面。返回的 data 只包含 beatmap_id 和\nbeatmapset_id。", "GET",
                ToolSchema.object()
                        .property("m", ToolSchema.integer()
                                .description("谱面 ID；与 ms、of 互斥"))
                        .property("ms", ToolSchema.integer()
                                .description("谱面集 ID；通常与 i 一起使用"))
                        .property("of", ToolSchema.string()
                                .description("成绩或多人房间引用类型"))
                        .property("i", ToolSchema.integer()
                                .description("成绩序号或谱面集内序号，从 1 开始"))
                        .property("u", ToolSchema.integer()
                                .description("osu! 用户 ID；使用 of=bp、rs 或 rp 时必填"))
                        .required()
                        .additionalProperties(false),
                new String[]{}, new String[]{"m", "ms", "of", "i", "u"}, false, false, false);

        service.declare("ostella", "/beatmaps/pp",
                "ostella_post_beatmaps_pp",
                "给定谱面与成绩条件估计成绩PP\n给定指定谱面，估计其指定条件的成绩的PP。", "POST",
                ToolSchema.object()
                        .property("body", ToolSchema.object()
                                .property("beatmapId", ToolSchema.integer()
                                        .description("指定谱面ID"))
                                .property("conditions", ToolSchema.array(ToolSchema.string())
                                        .description("成绩条件。支持 Mod/Acc/判定计数/连击等。例如：[\"HDDT\",\"97.41%\",\"13miss\",\"18ok\",\"1200x\"]。缺少的条件将尝试自动补全。"))
                                .required("beatmapId", "conditions"))
                        .required("body")
                        .additionalProperties(false),
                new String[]{}, new String[]{}, true, false, false);

        service.declare("ostella", "/beatmaps/{beatmapId}",
                "ostella_get_beatmaps_by_beatmapId",
                "获取谱面基本信息\n获取谱面基本信息", "GET",
                ToolSchema.object()
                        .property("beatmapId", ToolSchema.integer()
                                .description("osu! 谱面 ID"))
                        .property("mod", ToolSchema.string()
                                .description("连续的 mod acronym，例如 HDHR 或 DT；空值表示 NM。"))
                        .required("beatmapId")
                        .additionalProperties(false),
                new String[]{"beatmapId"}, new String[]{"mod"}, false, false, false);

        service.declare("ostella", "/beatmaps/{beatmapId}/analysis",
                "ostella_get_beatmaps_by_beatmapId_analysis",
                "获取谱面分析\n获取指定谱面(可选带有指定Mod)的分类分析、PP构成以及难度分析。可以计算指定谱面在指定Mod下的实际难度数据，也可以在分析指定成绩时获取谱面难度、PP构成用于参考评价玩家的Skill。", "GET",
                ToolSchema.object()
                        .property("beatmapId", ToolSchema.integer()
                                .description("osu! 谱面 ID"))
                        .property("mod", ToolSchema.string()
                                .description("连续的 mod acronym，例如 HDHR 或 DT；空值表示 NM。"))
                        .required("beatmapId")
                        .additionalProperties(false),
                new String[]{"beatmapId"}, new String[]{"mod"}, false, false, false);

        service.declare("ostella", "/beatmapsets",
                "ostella_post_beatmapsets",
                "批量获取谱面集信息\n按照ID列表批量获取谱面集基本信息。返回的结果略去了每张谱面的的详细信息，所有转谱以及相关用户信息。适合在进行大量谱面集统计分析时使用。", "POST",
                ToolSchema.object()
                        .property("body", ToolSchema.object()
                                .property("ids", ToolSchema.array(ToolSchema.integer())
                                        .description("要获取的用户的UID列表"))
                                .required("ids"))
                        .required("body")
                        .additionalProperties(false),
                new String[]{}, new String[]{}, true, false, false);

        service.declare("ostella", "/beatmapsets/lookup",
                "ostella_get_beatmapsets_lookup",
                "根据 ID、成绩引用解析谱面集\n使用 ms 解析谱面集，m 解析谱面所属谱面集，of=bp/rs/rp 加 i、u 解析用户 u 最好/最近成绩的第 i 个。", "GET",
                ToolSchema.object()
                        .property("ms", ToolSchema.integer()
                                .description("谱面集 ID"))
                        .property("m", ToolSchema.integer()
                                .description("谱面 ID"))
                        .property("of", ToolSchema.string()
                                .description("成绩或多人房间引用类型"))
                        .property("i", ToolSchema.integer()
                                .description("成绩序号，从 1 开始；of 为成绩类型时必填"))
                        .property("u", ToolSchema.integer()
                                .description("osu! 用户 ID；of 为 bp、rs 或 rp 时必填"))
                        .required()
                        .additionalProperties(false),
                new String[]{}, new String[]{"ms", "m", "of", "i", "u"}, false, false, false);

        service.declare("ostella", "/beatmapsets/search",
                "ostella_get_beatmapsets_search",
                "搜索谱面集\n使用指定关键字搜索谱面集。\n关键字支持 `普通关键词` `字段 操作符 值` `字段=\"带空格的文本\"`。\n字段可以直接组合，普通文本和字段搜索可以混用，多个条件基本上按 AND 理解。\n支持的比较操作符：= : < > <= >=。\n常用字段：\n- 文本: artist title source creator difficulty\n- 谱面属性: stars(star) ar od cs hp bpm length\n- 物件数量: circles sliders\n- 其他: favourites featured_artist status tag\n- 日期: created(submitted) updated ranked\n示例:\n- Camellia source=\"osu!\" title=\"Exit This Earth's Atomosphere\"\n- artist=\"Camellia\" creator=Sotarks length<180 \n- stars>=5 ar>=9 cs<=4 od>8 hp=6 difficulty=\"Extra\"\n- bpm>=180 circles>300 sliders>=200\n- favourites>=1000 featured_artist=true\n- status=ranked updated>=2025-01\n- tag=\"reading skin\"", "GET",
                ToolSchema.object()
                        .property("q", ToolSchema.string()
                                .description("搜索关键词"))
                        .required("q")
                        .additionalProperties(false),
                new String[]{}, new String[]{"q"}, false, false, false);

        service.declare("ostella", "/beatmapsets/{beatmapsetId}",
                "ostella_get_beatmapsets_by_beatmapsetId",
                "获取谱面集\n获取谱面集对象，谱面按星数升序排列。", "GET",
                ToolSchema.object()
                        .property("beatmapsetId", ToolSchema.integer()
                                .description("osu! 谱面集 ID"))
                        .required("beatmapsetId")
                        .additionalProperties(false),
                new String[]{"beatmapsetId"}, new String[]{}, false, false, false);

        service.declare("ostella", "/daily",
                "ostella_get_daily",
                "获取当前每日挑战\n获取当前每日挑战", "GET",
                ToolSchema.object()
                        .required()
                        .additionalProperties(false),
                new String[]{}, new String[]{}, false, false, false);

        service.declare("ostella", "/health",
                "ostella_get_health",
                "检查 oStella 和 osu! API 状态\n检查 oStella 和 osu! API 状态", "GET",
                ToolSchema.object()
                        .required()
                        .additionalProperties(false),
                new String[]{}, new String[]{}, false, false, false);

        service.declare("ostella", "/multiplayer/rooms/{roomId}/playlist/{playlistItemId}/result",
                "ostella_get_room_playlist_result_result",
                "获取多人对局结果\n获取指定多人房间的指定一张图上的玩家、队伍和系列赛数据。", "GET",
                ToolSchema.object()
                        .property("roomId", ToolSchema.integer()
                                .description("多人房间 ID 或 stable 比赛 ID"))
                        .property("playlistItemId", ToolSchema.integer()
                                .description("lazer 播放列表项 ID 或 stable 对局 ID"))
                        .property("version", ToolSchema.string()
                                .description("多人房间数据来源，默认 lazer，可选stable。"))
                        .required("roomId", "playlistItemId")
                        .additionalProperties(false),
                new String[]{"roomId", "playlistItemId"}, new String[]{"version"}, false, false, false);

        service.declare("ostella", "/multiplayer/rooms/{roomId}/watch",
                "ostella_get_multiplayer_rooms_by_roomId_watch",
                "获取多人房间基本信息和已完成对局\n获取指定多人房间已经完成了的对局的列表。包含房间基本信息以及每场对局的 ID，可用于查找详情。", "GET",
                ToolSchema.object()
                        .property("roomId", ToolSchema.integer()
                                .description("多人房间 ID 或 stable 比赛 ID"))
                        .property("version", ToolSchema.string()
                                .description("多人房间数据来源，默认 lazer，可选stable。"))
                        .required("roomId")
                        .additionalProperties(false),
                new String[]{"roomId"}, new String[]{"version"}, false, false, false);

        service.declare("ostella", "/replays/status",
                "ostella_get_replays_status",
                "查看回放渲染器状态\n查看回放渲染器状态", "GET",
                ToolSchema.object()
                        .required()
                        .additionalProperties(false),
                new String[]{}, new String[]{}, false, false, false);

        service.declare("ostella", "/replays/{jobId}/status",
                "ostella_get_replays_by_jobId_status",
                "查询回放渲染任务状态\n查询指定ID的回放渲染任务的状态。状态可能为 queued、rendering、upload_queued、uploading、done、failed、timeout 或 canceled。", "GET",
                ToolSchema.object()
                        .property("jobId", ToolSchema.string()
                                .description("回放渲染任务 ID"))
                        .required("jobId")
                        .additionalProperties(false),
                new String[]{"jobId"}, new String[]{}, false, false, false);

        service.declare("ostella", "/scores/lookup",
                "ostella_get_scores_lookup",
                "根据成绩 ID、谱面、谱面集或用户成绩序号解析成绩\n直接用 s 查询成绩；用 m+u 可查询用户 u 在指定谱面 m 的成绩；用 ms+i+u 查询用户 u 在\n谱面集 ms 第 i 张谱面的成绩；用 of=bp/rs/rp 加 i+u 查询用户最好/最近所有/最近通过成绩列表中的第 i 条。\nfilters 可用于成绩筛选，格式与成绩列表接口相同。", "GET",
                ToolSchema.object()
                        .property("s", ToolSchema.string()
                                .description("成绩 ID；也接受带 s 前缀的本地成绩引用"))
                        .property("m", ToolSchema.integer()
                                .description("谱面 ID，与 u 一起使用"))
                        .property("ms", ToolSchema.integer()
                                .description("谱面集 ID，与 i、u 一起使用"))
                        .property("of", ToolSchema.string()
                                .description("用户成绩类型"))
                        .property("i", ToolSchema.integer()
                                .description("成绩序号或谱面集内序号，从 1 开始"))
                        .property("u", ToolSchema.integer()
                                .description("osu! 用户 ID"))
                        .property("filters", ToolSchema.string()
                                .description("逗号分隔的成绩过滤器，例如 acc>=98,miss<=2,mod~HD。"))
                        .property("mod", ToolSchema.string()
                                .description("连续的 mod acronym，例如 HDHR 或 DT；空值表示 NM。"))
                        .required()
                        .additionalProperties(false),
                new String[]{}, new String[]{"s", "m", "ms", "of", "i", "u", "filters", "mod"}, false, false, false);

        service.declare("ostella", "/scores/random",
                "ostella_get_scores_random",
                "获取随机成绩\n获取随机成绩", "GET",
                ToolSchema.object()
                        .property("min_rank", ToolSchema.integer()
                                .description("允许的最低全球排名，默认不限制"))
                        .required()
                        .additionalProperties(false),
                new String[]{}, new String[]{"min_rank"}, false, false, false);

        service.declare("ostella", "/scores/{scoreId}",
                "ostella_get_scores_by_scoreId",
                "获取成绩基本信息\n获取指定成绩的基本信息。", "GET",
                ToolSchema.object()
                        .property("scoreId", ToolSchema.string()
                                .description("成绩 ID；可使用在线成绩数字 ID 或 oStella 本地成绩引用"))
                        .required("scoreId")
                        .additionalProperties(false),
                new String[]{"scoreId"}, new String[]{}, false, false, false);

        service.declare("ostella", "/scores/{scoreId}/analysis",
                "ostella_get_scores_by_scoreId_analysis",
                "获取成绩分析\n获得对应成绩的详细分析数据，包括成绩本身、谱面难度解析、击打时间和位置统计、不稳定率、PP曲线等。", "GET",
                ToolSchema.object()
                        .property("scoreId", ToolSchema.string()
                                .description("成绩 ID；可使用在线成绩数字 ID 或 oStella 本地成绩引用"))
                        .required("scoreId")
                        .additionalProperties(false),
                new String[]{"scoreId"}, new String[]{}, false, false, false);

        service.declare("ostella", "/scores/{scoreId}/highlight",
                "ostella_get_scores_by_scoreId_highlight",
                "获取成绩高光片段\n获取指定成绩的高光片段的起始、终止时间。", "GET",
                ToolSchema.object()
                        .property("scoreId", ToolSchema.string()
                                .description("成绩 ID；可使用在线成绩数字 ID 或 oStella 本地成绩引用"))
                        .required("scoreId")
                        .additionalProperties(false),
                new String[]{"scoreId"}, new String[]{}, false, false, false);

        service.declare("ostella", "/scores/{scoreId}/misses",
                "ostella_get_scores_by_scoreId_misses",
                "获取指定成绩的Miss列表\n获取指定成绩的Miss列表。在分析成绩时、使用获取Miss详情前调用。", "GET",
                ToolSchema.object()
                        .property("scoreId", ToolSchema.string()
                                .description("成绩 ID；可使用在线成绩数字 ID 或 oStella 本地成绩引用"))
                        .required("scoreId")
                        .additionalProperties(false),
                new String[]{"scoreId"}, new String[]{}, false, false, false);

        service.declare("ostella", "/scores/{scoreId}/misses/{missIndex}/visualize",
                "ostella_get_scores_by_scoreId_misses_by_missIndex_visualize",
                "获取指定成绩的指定Miss的分析\n获取指定成绩中指定序号的Miss的分析数据，包括时间、类型、PP损失等。在分析成绩可用于参考，使用前需要使用 获取指定成绩的Miss列表 获取成绩的Miss列表和Miss序号。", "GET",
                ToolSchema.object()
                        .property("scoreId", ToolSchema.string()
                                .description("成绩 ID；可使用在线成绩数字 ID 或 oStella 本地成绩引用"))
                        .property("missIndex", ToolSchema.integer()
                                .description("失误序号，从 1 开始"))
                        .required("scoreId", "missIndex")
                        .additionalProperties(false),
                new String[]{"scoreId", "missIndex"}, new String[]{}, false, false, false);

        service.declare("ostella", "/users",
                "ostella_post_users",
                "批量获取玩家资料\n按照UID列表批量获取玩家资料", "POST",
                ToolSchema.object()
                        .property("body", ToolSchema.object()
                                .property("ids", ToolSchema.array(ToolSchema.integer())
                                        .description("要获取的用户的UID列表"))
                                .required("ids"))
                        .required("body")
                        .additionalProperties(false),
                new String[]{}, new String[]{}, true, false, false);

        service.declare("ostella", "/users/lookup",
                "ostella_post_users_lookup",
                "按用户名查找玩家\n按用户名查找玩家信息。", "POST",
                ToolSchema.object()
                        .property("body", ToolSchema.object()
                                .property("user_name", ToolSchema.string()
                                        .description("要查找的用户名。空格用下划线代替。不区分大小写。"))
                                .required("user_name"))
                        .required("body")
                        .additionalProperties(false),
                new String[]{}, new String[]{}, true, false, false);

        service.declare("ostella", "/users/scores/recent/batch",
                "ostella_post_users_scores_recent_batch",
                "批量获取多个用户的最近成绩\n按照UID批量获取多个用户的最近成绩。返回以用户 ID 字符串为键的对象。每个用户没有成绩时对应空数组；include_fails 默认 false。", "POST",
                ToolSchema.object()
                        .property("body", ToolSchema.object()
                                .property("include_fails", ToolSchema.bool()
                                        .description("是否包含失败成绩"))
                                .property("limit", ToolSchema.integer()
                                        .description("每个玩家最大返回成绩数量"))
                                .property("user_ids", ToolSchema.array(ToolSchema.integer())
                                        .description("要查找的玩家的UID列表"))
                                .required("limit", "user_ids"))
                        .required("body")
                        .additionalProperties(false),
                new String[]{}, new String[]{}, true, false, false);

        service.declare("ostella", "/users/{userId}",
                "ostella_get_users_by_userId",
                "获取玩家基本资料\n获取玩家基本信息。", "GET",
                ToolSchema.object()
                        .property("userId", ToolSchema.integer()
                                .description("osu! 用户 ID"))
                        .required("userId")
                        .additionalProperties(false),
                new String[]{"userId"}, new String[]{}, false, false, false);

        service.declare("ostella", "/users/{userId}/rank",
                "ostella_get_users_by_userId_rank",
                "获取玩家排名摘要\n获取玩家全球排名和国家/地区排名。", "GET",
                ToolSchema.object()
                        .property("userId", ToolSchema.integer()
                                .description("osu! 用户 ID"))
                        .required("userId")
                        .additionalProperties(false),
                new String[]{"userId"}, new String[]{}, false, false, false);

        service.declare("ostella", "/users/{userId}/scores/bestof",
                "ostella_get_users_by_userId_scores_bestof",
                "获取玩家最佳成绩列表\n获取指定玩家的最佳成绩（BP）列表，支持范围选择以及过滤器。", "GET",
                ToolSchema.object()
                        .property("userId", ToolSchema.integer()
                                .description("osu! 用户 ID"))
                        .property("n", ToolSchema.integer()
                                .description("成绩列表结束序号，范围 1 到 200。"))
                        .property("start", ToolSchema.integer()
                                .description("成绩列表起始序号，默认 1，且不能大于 n。"))
                        .property("filters", ToolSchema.string()
                                .description("逗号分隔的成绩过滤器，例如 acc>=98,miss<=2,mod~HD。"))
                        .required("userId", "n")
                        .additionalProperties(false),
                new String[]{"userId"}, new String[]{"n", "start", "filters"}, false, false, false);

        service.declare("ostella", "/users/{userId}/scores/deranked-best",
                "ostella_get_users_by_userId_scores_deranked_best",
                "获取指定玩家所有成绩的最佳成绩列表\n返回指定玩家的最佳成绩列表（包含已经记录的在Unranked谱面、或使用ScoreV2等不在官方BP计算范围之内的分数），以及使用这些成绩计算的排名、PP及其组成等数据。", "GET",
                ToolSchema.object()
                        .property("userId", ToolSchema.integer()
                                .description("目标用户ID"))
                        .property("start", ToolSchema.integer()
                                .description("成绩范围开始"))
                        .property("n", ToolSchema.integer()
                                .description("查询数量"))
                        .required("userId", "n")
                        .additionalProperties(false),
                new String[]{"userId"}, new String[]{"start", "n"}, false, false, false);

        service.declare("ostella", "/users/{userId}/scores/recent",
                "ostella_get_users_by_userId_scores_recent",
                "获取玩家最近成绩列表\n获取指定玩家最近的成绩，支持指定数量、过滤和筛选通过的成绩。", "GET",
                ToolSchema.object()
                        .property("userId", ToolSchema.integer()
                                .description("osu! 用户 ID"))
                        .property("n", ToolSchema.integer()
                                .description("成绩列表结束序号，范围 1 到 200。"))
                        .property("start", ToolSchema.integer()
                                .description("成绩列表起始序号，默认 1，且不能大于 n。"))
                        .property("fail", ToolSchema.bool()
                                .description("是否包含失败的最近成绩，默认 false。"))
                        .property("filters", ToolSchema.string()
                                .description("逗号分隔的成绩过滤器，例如 acc>=98,miss<=2,mod~HD。"))
                        .required("userId", "n")
                        .additionalProperties(false),
                new String[]{"userId"}, new String[]{"n", "start", "fail", "filters"}, false, false, false);

        service.declare("ostella", "/users/{userId}/scores/today-best",
                "ostella_get_users_by_userId_scores_today_best",
                "获取最近若干天内达成的最佳成绩\n获取指定用户在一段时间范围内达成的最佳成绩（BP）。默认查询最近 1 天。", "GET",
                ToolSchema.object()
                        .property("userId", ToolSchema.integer()
                                .description("osu! 用户 ID"))
                        .property("days", ToolSchema.integer()
                                .description("查询最近几天，默认 1。"))
                        .required("userId")
                        .additionalProperties(false),
                new String[]{"userId"}, new String[]{"days"}, false, false, false);

        service.declare("ostella", "/whatif",
                "ostella_get_whatif",
                "根据排名估计PP/根据PP估计排名\n根据全球排名/PP中的一个，估计另外一个。调用时只需给定 pp 和 rank 中的一个。", "GET",
                ToolSchema.object()
                        .property("pp", ToolSchema.integer()
                                .description("要估计的PP"))
                        .property("rank", ToolSchema.number()
                                .description("要估计的排名"))
                        .required()
                        .additionalProperties(false),
                new String[]{}, new String[]{"pp", "rank"}, false, false, false);

        service.declare("asteroid", "/browsers/create",
                "asteroid_get_browsers_create",
                "创建浏览器\n创建一个新的浏览器进程，可用于后续创建页面、获取页面、以及关闭进程。十分钟内未进行操作的浏览器进程会自动被清理。", "GET",
                ToolSchema.object()
                        .required()
                        .additionalProperties(false),
                new String[]{}, new String[]{}, false, false, false);

        service.declare("asteroid", "/browsers/{browserUuid}/close",
                "asteroid_get_browsers_by_browserUuid_close",
                "关闭浏览器及其全部页面\n关闭指定浏览器。", "GET",
                ToolSchema.object()
                        .property("browserUuid", ToolSchema.string()
                                .description("要操作的浏览器UUID"))
                        .required("browserUuid")
                        .additionalProperties(false),
                new String[]{"browserUuid"}, new String[]{}, false, false, false);

        service.declare("asteroid", "/browsers/{browserUuid}/pages",
                "asteroid_get_browsers_by_browserUuid_pages",
                "列出浏览器内已登记的页面\n列出指定浏览器所有打开的页面的UUID、标题、URL。", "GET",
                ToolSchema.object()
                        .property("browserUuid", ToolSchema.string()
                                .description("要操作的浏览器UUID"))
                        .required("browserUuid")
                        .additionalProperties(false),
                new String[]{"browserUuid"}, new String[]{}, false, false, false);

        service.declare("asteroid", "/browsers/{browserUuid}/pages/create",
                "asteroid_get_browsers_by_browserUuid_pages_create",
                "创建空白页面\n在指定浏览器中创建新页面，随后使用返回的 page_uuid 导航。", "GET",
                ToolSchema.object()
                        .property("browserUuid", ToolSchema.string()
                                .description("要操作的浏览器UUID"))
                        .required("browserUuid")
                        .additionalProperties(false),
                new String[]{"browserUuid"}, new String[]{}, false, false, false);

        service.declare("asteroid", "/browsers/{browserUuid}/pages/{pageUuid}/close",
                "asteroid_get_browser_page_close",
                "关闭单个页面\n关闭指定浏览器的指定页面。", "GET",
                ToolSchema.object()
                        .property("browserUuid", ToolSchema.string()
                                .description("要操作的浏览器UUID"))
                        .property("pageUuid", ToolSchema.string()
                                .description("要操作的页面UUID"))
                        .required("browserUuid", "pageUuid")
                        .additionalProperties(false),
                new String[]{"browserUuid", "pageUuid"}, new String[]{}, false, false, false);

        service.declare("asteroid", "/browsers/{browserUuid}/pages/{pageUuid}/eval",
                "asteroid_post_browser_page_eval",
                "在指定页面执行JS表达式\n在指定的页面中执行（eval）指定的JS表达式。", "POST",
                ToolSchema.object()
                        .property("browserUuid", ToolSchema.string()
                                .description("要操作的浏览器UUID"))
                        .property("pageUuid", ToolSchema.string()
                                .description("要操作的页面UUID"))
                        .property("body", ToolSchema.object()
                                .property("expr", ToolSchema.string()
                                        .description("要执行的JS表达式"))
                                .required("expr"))
                        .required("browserUuid", "pageUuid", "body")
                        .additionalProperties(false),
                new String[]{"browserUuid", "pageUuid"}, new String[]{}, true, true, false);

        service.declare("asteroid", "/browsers/{browserUuid}/pages/{pageUuid}/get",
                "asteroid_get_browser_page_get",
                "读取页面或指定元素的 HTML\n未指定 selector 或其为空白时返回整页当前 DOM 的 HTML，指定 selector 时只返回对应选择器内的 HTML。", "GET",
                ToolSchema.object()
                        .property("selector", ToolSchema.string()
                                .description("Playwright 选择器，优先使用基于已读取 HTML 的明确 CSS 选择器。当前服务额外 URL 解码一次，避免未经核对的加号或百分号。"))
                        .property("browserUuid", ToolSchema.string()
                                .description("要操作的浏览器UUID"))
                        .property("pageUuid", ToolSchema.string()
                                .description("要操作的页面UUID"))
                        .property("purify", ToolSchema.bool()
                                .description("是否去除页面中的CSS和JS"))
                        .required("browserUuid", "pageUuid", "purify")
                        .additionalProperties(false),
                new String[]{"browserUuid", "pageUuid"}, new String[]{"selector", "purify"}, false, false, false);

        service.declare("asteroid", "/browsers/{browserUuid}/pages/{pageUuid}/navigate",
                "asteroid_post_browser_page_navigate",
                "导航到指定地址\n导航复用现有页面，使用 Playwright 默认导航等待与超时设置，未提供自定义选项。\n返回目标主文档响应的信息，不包含页面 HTML；随后调用 getPageContent。\n目标站点返回 404/500 时，本接口仍可能 success=true，必须检查 data.ok/status。\n使用完整 HTTP/HTTPS 地址。当前实现对 about:blank 或同文档片段导航的空响应没有处理，可能返回 500。\n", "POST",
                ToolSchema.object()
                        .property("browserUuid", ToolSchema.string()
                                .description("要操作的浏览器UUID"))
                        .property("pageUuid", ToolSchema.string()
                                .description("要操作的页面UUID"))
                        .property("body", ToolSchema.object()
                                .property("url", ToolSchema.string()
                                        .description("完整 HTTP/HTTPS 目标地址；服务端当前仅检查非空，智能体应遵守此协议限制"))
                                .required("url"))
                        .required("browserUuid", "pageUuid", "body")
                        .additionalProperties(false),
                new String[]{"browserUuid", "pageUuid"}, new String[]{}, true, false, false);

        service.declare("asteroid", "/browsers/{browserUuid}/pages/{pageUuid}/screenshot",
                "asteroid_get_browser_page_screenshot",
                "获取指定页面截图\n获取指定页面的截图。返回结果为Base64。", "GET",
                ToolSchema.object()
                        .property("browserUuid", ToolSchema.string()
                                .description("要操作的浏览器UUID"))
                        .property("pageUuid", ToolSchema.string()
                                .description("要操作的页面UUID"))
                        .property("url", ToolSchema.bool()
                                .description("是否使用图床URL返回结果。若为false则返回Base64"))
                        .property("selector", ToolSchema.string()
                                .description("要截取的元素的选择器。若未提供则截取整个页面。"))
                        .required("browserUuid", "pageUuid")
                        .additionalProperties(false),
                new String[]{"browserUuid", "pageUuid"}, new String[]{"url", "selector"}, false, false, false);

        service.declare("asteroid", "/browsers/{browserUuid}/pages/{pageUuid}/waitfor",
                "asteroid_get_browser_page_waitfor",
                "等待页面达到网络空闲状态\n等待 NETWORKIDLE，即至少约 500 毫秒没有网络连接；已达到时立即返回。timeout 是最大等待时间，单位毫秒，不是固定休眠时长。", "GET",
                ToolSchema.object()
                        .property("timeout", ToolSchema.integer()
                                .description("最大等待毫秒数，必须是正整数；无默认值。建议 10000；最大值来自 Java int 范围，不代表推荐等待时长。"))
                        .property("browserUuid", ToolSchema.string()
                                .description("要操作的浏览器UUID"))
                        .property("pageUuid", ToolSchema.string()
                                .description("要操作的页面UUID"))
                        .required("timeout", "browserUuid", "pageUuid")
                        .additionalProperties(false),
                new String[]{"browserUuid", "pageUuid"}, new String[]{"timeout"}, false, false, false);

        service.declare("asteroid", "/minecraft/servers/{server}/status",
                "asteroid_get_minecraft_servers_by_server_status",
                "获取 Minecraft 服务器状态\n根据地址获取指定 Minecraft 服务器状态，包括服务器名称、版本、延迟、玩家人数等。", "GET",
                ToolSchema.object()
                        .property("server", ToolSchema.string()
                                .description("服务器地址，可加端口"))
                        .required("server")
                        .additionalProperties(false),
                new String[]{"server"}, new String[]{}, false, false, false);

        service.declare("asteroid", "/nbnhhsh",
                "asteroid_get_nbnhhsh",
                "查询互联网缩写含义\n查找给定的缩写或术语在当下互联网环境下可能的含义。", "GET",
                ToolSchema.object()
                        .property("text", ToolSchema.string()
                                .description("要查找的缩写"))
                        .required("text")
                        .additionalProperties(false),
                new String[]{}, new String[]{"text"}, false, false, false);

        service.declare("asteroid", "/ncm/playlists/{id}",
                "asteroid_get_ncm_playlists_by_id",
                "获取网易云音乐歌单详情\n获取指定ID的网易云音乐歌单的详情", "GET",
                ToolSchema.object()
                        .property("id", ToolSchema.integer()
                                .description("要获取的歌单ID"))
                        .required("id")
                        .additionalProperties(false),
                new String[]{"id"}, new String[]{}, false, false, false);

        service.declare("asteroid", "/ncm/playlists/{id}/songs",
                "asteroid_get_ncm_playlists_by_id_songs",
                "获取网易云音乐歌单所有音乐\n获取指定ID的网易云音乐歌单中的所有音乐信息", "GET",
                ToolSchema.object()
                        .property("id", ToolSchema.integer()
                                .description("要获取的歌单ID"))
                        .property("limit", ToolSchema.integer()
                                .description("最大返回结果数量"))
                        .property("offset", ToolSchema.integer()
                                .description("返回结果偏移"))
                        .required("id")
                        .additionalProperties(false),
                new String[]{"id"}, new String[]{"limit", "offset"}, false, false, false);

        service.declare("asteroid", "/ncm/songs/match",
                "asteroid_post_ncm_songs_match",
                "网易云音乐听歌识曲\n使用网易云音乐对指定URL的音频进行听歌识曲", "POST",
                ToolSchema.object()
                        .property("body", ToolSchema.object()
                                .property("duration", ToolSchema.integer()
                                        .description("指定识别几秒的片段，一般3秒即可，不得超过10"))
                                .property("startSeconds", ToolSchema.integer()
                                        .description("指定从目标音频第几秒开始"))
                                .property("url", ToolSchema.string()
                                        .description("目标音频的URL"))
                                .required("url", "duration"))
                        .required("body")
                        .additionalProperties(false),
                new String[]{}, new String[]{}, true, false, false);

        service.declare("asteroid", "/ncm/songs/search",
                "asteroid_get_ncm_songs_search",
                "搜索网易云音乐歌曲\n使用指定关键词在网易云音乐平台搜索音乐", "GET",
                ToolSchema.object()
                        .property("q", ToolSchema.string()
                                .description("关键字"))
                        .required("q")
                        .additionalProperties(false),
                new String[]{}, new String[]{"q"}, false, false, false);

        service.declare("asteroid", "/ncm/songs/{id}/url",
                "asteroid_get_ncm_songs_by_id_url",
                "获取网易云音乐歌曲URL\n获取指定ID的网易云音乐歌曲的歌曲文件URL", "GET",
                ToolSchema.object()
                        .property("id", ToolSchema.integer()
                                .description("要获取的歌曲ID"))
                        .required("id")
                        .additionalProperties(false),
                new String[]{"id"}, new String[]{}, false, false, false);

        service.declare("asteroid", "/search/brave",
                "asteroid_get_search_brave",
                "Brave 网络搜索\n使用指定关键词进行联网搜索。", "GET",
                ToolSchema.object()
                        .property("q", ToolSchema.string()
                                .description("关键词"))
                        .required("q")
                        .additionalProperties(false),
                new String[]{}, new String[]{"q"}, false, false, false);

    }
}
