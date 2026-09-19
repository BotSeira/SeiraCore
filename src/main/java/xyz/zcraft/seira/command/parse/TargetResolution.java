package xyz.zcraft.seira.command.parse;

/** 仅保存语法解析结果。target 为 null 表示省略目标；consumedArgs 是选项起点，不会随读取改变。 */
public record TargetResolution(ShortcutTarget target, int consumedArgs) {}
