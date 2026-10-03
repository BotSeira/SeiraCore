package xyz.zcraft.seira.command.reply;

public final class CommandUsage {
    public static final String ADDPP = "用法：/addpp [user] <PP或PP*条数>，或 /addpp [user] <m谱面ID或rs/bp/rp[N]> [Mod/ACC/判定数量/Combo/FC...]；例：/addpp 200*4、/addpp 用户名 200*4、/addpp 用户名 m1234567 HD 98% FC。user 支持 UID、用户名或 @用户，含空格用户名用双引号包裹；目标须放在成绩参数前；省略时使用自己的绑定账号。快捷查询 N 默认为 1，范围 1–200，例如 /addpp @用户 rp2 HD 98%。条件可省略、顺序不限；未提供的值由 rosu-pp 处理。";
    public static final String WHATIF = "用法：/whatif <总PPpp 或 #排名>；如 /whatif 12345pp、/whatif #12345。纯数字按排名解析。";
    public static final String BP = "用法：/bp <个数或范围> [玩家ID/用户名/@用户] [过滤条件 ...]";
    public static final String NO_BIND = "你还没有绑定玩家ID，请先使用 /bind 绑定喵";
    public static final String REBIND = "由于发生了一个技术问题，使用此功能需要重新绑定。请使用 `/unbind` 解除绑定，再使用 `/bind` 重新绑定~";
    public static final String RS = "用法：/rs <个数或范围> [玩家ID/用户名/@用户] [过滤条件 ...]";
    public static final String TB = "用法：/tb [#天数] [玩家ID/用户名/@用户]；天数必须为正整数";
    public static final String SCORE_FILTERS = "过滤示例：acc>=98 any=stream title=[Song.*] 1miss video !sb fc !S；含空格的值请使用双引号，多个条件同时生效。";
    public static final String M = "用法：/m <谱面ID 或 快捷查询> [Mod]";
    public static final String BMA = "用法：/bma <谱面ID 或 快捷查询> [Mod]";
    public static final String AP = "用法：/ap <谱面ID 或 快捷查询>";
    public static final String BPV = "用法：/bpv [谱面ID 或 快捷查询] [Mod]（省略目标时使用记忆）";
    public static final String BGP = "用法：/bgp <谱面ID 或 快捷查询>";
    public static final String DL = "用法：/dl <谱面集ID 或 快捷查询>";
    public static final String S = "用法：/s [成绩ID、[@用户] m谱面ID 或 快捷查询] [用户] [+Mod]（省略目标时使用记忆）";
    public static final String SM = "用法：/sm [谱面ID 或 快捷查询] [@玩家] [+Mod]（省略目标时使用记忆，省略玩家时使用自己的绑定账号）";
    public static final String SA = "用法：/sa <成绩ID、[@用户] m谱面ID 或 快捷查询>";
    public static final String MA = "用法：/ma [成绩ID、[@用户] m谱面ID 或 快捷查询] [序号]；省略目标并指定序号时请使用 #序号";
    public static final String SNAP = "用法：/snap [成绩ID、[@用户] m谱面ID 或 快捷查询] <mm:ss.fff / 秒数s / 毫秒数ms / obj物件序号 / #Miss序号> [±偏移ms]；省略成绩时使用最近目标。例：/snap rp1 01:23.456、/snap obj123、/snap #3 -50ms。";
    public static final String R = "用法：/r [成绩ID、[@用户] m谱面ID 或 快捷查询] [[mm:ss]-[mm:ss]]";
    public static final String RSC = "用法：/rsc [谱面ID或快捷查询] [+/=用户ID列表，逗号分隔]";
    public static final String F = "用法：/f [@用户]";
    public static final String RCANCEL = "用法：/rcancel <渲染任务ID>";
    public static final String SUP = "用法：/sup [@用户]";

    private CommandUsage() {
    }
}
