package xyz.zcraft.seira.command.reply;

public final class CommandUsage {
    public static final String BO = "用法：/bo <个数> [玩家ID/用户名/@用户] [过滤条件 ...]";
    public static final String NO_BIND = "你还没有绑定玩家ID，请先使用 /bind 绑定";
    public static final String REBIND = "由于发生了一个技术问题，使用此功能需要重新绑定。请使用 `/unbind` 解除绑定，再使用 `/bind` 重新绑定~";
    public static final String RS = "用法：/rs <个数> [玩家ID/用户名/@用户] [过滤条件 ...]";
    public static final String TB = "用法：/tb [#天数] [玩家ID/用户名/@用户]；天数必须为正整数";
    public static final String SCORE_FILTERS = "过滤示例：acc>=98 combo>=1000 mod~HD mod!~DT time<2:00 pp>=300；多个条件同时生效。";
    public static final String M = "用法：/m <谱面ID 或 快捷查询> [Mod]";
    public static final String BMA = "用法：/bma <谱面ID 或 快捷查询> [Mod]";
    public static final String AP = "用法：/ap <谱面ID 或 快捷查询>";
    public static final String BPV = "用法：/bpv <谱面ID 或 快捷查询> [Mod]";
    public static final String BGP = "用法：/bgp <谱面ID 或 快捷查询>";
    public static final String DL = "用法：/dl <谱面集ID 或 快捷查询>";
    public static final String S = "用法：/s <成绩ID 或 快捷查询>";
    public static final String SA = "用法：/sa <成绩ID 或 快捷查询>";
    public static final String MA = "用法：/ma [成绩ID 或 快捷查询] [序号]；省略目标并指定序号时请使用 #序号";
    public static final String R = "用法：/r [成绩ID 或 快捷查询] [[mm:ss]-[mm:ss]]";
    public static final String RSC = "用法：/rsc [谱面ID或快捷查询] [+/=用户ID列表，逗号分隔]";

    private CommandUsage() {
    }
}
