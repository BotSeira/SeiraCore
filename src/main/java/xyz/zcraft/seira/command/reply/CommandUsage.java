package xyz.zcraft.seira.command.reply;

public final class CommandUsage {
    public static final String ADDPP = "用法：/addpp [user] <PP或PP*条数>，或 /addpp [user] <m谱面ID或rs/bp/rp[N]> [条件]";
    public static final String WHATIF = "用法：/whatif <总PPpp 或 #排名>";
    public static final String BP = "用法：/bp <个数或范围> [玩家ID/用户名/@用户] [过滤条件 ...]";
    public static final String NO_BIND = "你还没有绑定玩家ID，请先使用 /bind 绑定喵";
    public static final String REBIND = "由于发生了一个技术问题，使用此功能需要重新绑定。请使用 `/unbind` 解除绑定，再使用 `/bind` 重新绑定~";
    public static final String RS = "用法：/rs <个数或范围> [玩家ID/用户名/@用户] [过滤条件 ...]";
    public static final String TB = "用法：/tb [#天数] [玩家ID/用户名/@用户]";
    public static final String SCORE_FILTERS = "过滤示例：acc>=98 any=stream miss=1";
    public static final String M = "用法：/m <谱面ID 或 快捷查询> [Mod]";
    public static final String BMA = "用法：/bma <谱面ID 或 快捷查询> [Mod]";
    public static final String AP = "用法：/ap <谱面ID 或 快捷查询>";
    public static final String BPV = "用法：/bpv [谱面ID 或 快捷查询] [Mod]";
    public static final String BGP = "用法：/bgp <谱面ID 或 快捷查询>";
    public static final String DL = "用法：/dl <谱面集ID 或 快捷查询>";
    public static final String S = "用法：/s [成绩ID、[@用户] m谱面ID 或 快捷查询] [用户] [+Mod]";
    public static final String SM = "用法：/sm [谱面ID 或 快捷查询] [@玩家] [+Mod]";
    public static final String SA = "用法：/sa <成绩ID、[@用户] m谱面ID 或 快捷查询>";
    public static final String MA = "用法：/ma [成绩ID、[@用户] m谱面ID 或 快捷查询] [#序号]";
    public static final String REP = "用法：/rep [成绩ID、玩家及快捷查询] <时间 / obj物件序号 / #Miss序号> [±偏移ms] [window]。";
    public static final String SNAP = "用法：/snap [成绩] <秒数s/#Miss序号> [±偏移ms]";
    public static final String R = "用法：/r [成绩ID、[@用户] m谱面ID 或 快捷查询] [[mm:ss]-[mm:ss]]";
    public static final String RSC = "用法：/rsc [谱面ID或快捷查询] [+/=用户ID列表，逗号分隔]";
    public static final String F = "用法：/f [@用户]";
    public static final String RCANCEL = "用法：/rcancel <渲染任务ID>";
    public static final String SUP = "用法：/sup [@用户]";

    private CommandUsage() {
    }
}
