package xyz.zcraft.seira.command.parse;

import lombok.Getter;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.data.UserRef;

/**
 * target 为 null 表示省略目标；consumedArgs 之后是指令自己的可选参数。
 */
@Getter
public final class TargetResolution {
    private final ShortcutTarget target;
    private final UserRef userOverride;
    private int consumedArgs;

    public TargetResolution(ShortcutTarget target, int consumedArgs, UserRef userOverride) {
        this.target = target;
        this.consumedArgs = consumedArgs;
        this.userOverride = userOverride;
    }

    public TargetResolution(ShortcutTarget target, int consumedArgs) {
        this(target, consumedArgs, null);
    }

    public String nextArgument(Context ctx) {
        final String s = ctx.argumentCount() > consumedArgs ? ctx.argument(consumedArgs) : null;
        consumedArgs++;
        return s;
    }

    public boolean hasRemaining(Context ctx) {
        return ctx.argumentCount() > consumedArgs;
    }
}
