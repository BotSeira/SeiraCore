package xyz.zcraft.seira.command.parse;

import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.data.UserRef;

/**
 * target 为 null 表示省略目标；consumedArgs 之后是指令自己的可选参数。
 */
public record TargetResolution(ShortcutTarget target, int consumedArgs, UserRef userOverride) {
    public TargetResolution(ShortcutTarget target, int consumedArgs) {
        this(target, consumedArgs, null);
    }

    public String nextArgument(Context ctx) {
        return ctx.argumentCount() > consumedArgs ? ctx.argument(consumedArgs) : null;
    }
}
