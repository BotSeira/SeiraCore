package xyz.zcraft.seira.challenge;

import xyz.zcraft.seira.bot.data.Button;
import xyz.zcraft.seira.bot.data.PendingMessage;

import java.util.*;

import static xyz.zcraft.seira.challenge.ChallengeModels.Draft;

/**
 * Native QQ keyboards, with ordinary command equivalents for clients without keyboard support.
 */
public final class ChallengeSetupMessages {
    private ChallengeSetupMessages() {
    }

    public static PendingMessage render(Draft draft, String permittedUser, String panel, boolean mayStart) {
        var settings = draft.settings();
        String fixed = draft.beatmapset() == null ? "未选择" : draft.beatmapset().maps().stream()
                .filter(map -> Objects.equals(map.id(), settings.fixedMapId())).map(map -> ChallengeMessages.clean(map.name())
                        + String.format(Locale.ROOT, " %.2f★ (ID %d)", map.stars(), map.id())).findFirst().orElse("未选择");
        String map = draft.beatmapset() == null ? "未选择" : "[" + ChallengeMessages.clean(draft.beatmapset().title())
                + "](https://osu.ppy.sh/beatmapsets/" + draft.beatmapset().id() + ")";
        String content = "群挑战 · 配置\n> 谱面：" + map + "\n> 来源：" + (draft.randomMap() ? "随机抽取（可重抽）" : "手动指定")
                + "\n> 不同难度：" + (settings.differentDifficulties() ? "允许" : "不允许") + "\n> 固定/参考难度：" + fixed
                + "\n> 必选 Mod：" + ChallengeSettings.displayMods(settings.requiredMods())
                + "\n> 禁用 Mod：" + ChallengeSettings.displayMods(settings.bannedMods())
                + String.format(Locale.ROOT, "\n> 基础星数范围：%.1f–%.1f★", settings.minStars(), settings.maxStars())
                + "\n> 时长：" + draft.hours() + " 小时\n> 水平奖励/惩罚：" + (settings.skillAdjustment() ? "开启" : "关闭")
                + "\n\n" + (ChallengeService.ready(draft) ? "$\\boxed{配置已就绪，点击开始挑战即可开始}$" : "__请先选择星数范围内的谱面__");
        List<List<Button>> rows = new ArrayList<>();
        String edit = "/gch edit " + draft.id() + " " + draft.revision() + " ";
        String menu = "/gch menu " + draft.id() + " " + draft.revision() + " ";
        int id = 1;
        if (panel.equals("required") || panel.equals("banned")) {
            Set<String> selected = panel.equals("required") ? settings.requiredMods() : settings.bannedMods();
            List<String> mods = new ArrayList<>(new TreeSet<>(ChallengeSettings.SUPPORTED_MODS));
            if (panel.equals("required")) mods.addFirst("NM");
            content += "\n\n正在设置：" + (panel.equals("required") ? "必选 Mod" : "禁用 Mod") + "。点击切换；冲突设置会提示原因。";
            List<Button> row = new ArrayList<>();
            for (String mod : mods) {
                Set<String> next = new TreeSet<>(selected);
                if (!next.remove(mod)) {
                    if (mod.equals("NM")) next.clear();
                    else next.remove("NM");
                    next.add(mod);
                }
                String value = next.isEmpty() ? "none" : String.join(",", next);
                row.add(button(id++, (selected.contains(mod) ? "✓ " : "") + mod, edit + panel + " " + value, permittedUser));
                if (row.size() == 3) {
                    rows.add(List.copyOf(row));
                    row.clear();
                }
            }
            if (!row.isEmpty()) rows.add(List.copyOf(row));
            rows.add(Button.row(button(id++, "清空", edit + panel + " none", permittedUser),
                    button(id, "返回配置", "/gch configure", permittedUser)));
        } else if (panel.equals("mods")) {
            rows.add(Button.row(button(id++, "设置必选 Mod", menu + "required", permittedUser),
                    button(id++, "设置禁用 Mod", menu + "banned", permittedUser)));
            rows.add(Button.row(button(id, "返回配置", "/gch configure", permittedUser)));
        } else {
            rows.add(Button.row(input(id++, "指定谱面集", edit + "set ", permittedUser),
                    input(id++, "指定谱面", edit + "map ", permittedUser), button(id++, "随机谱面", edit + "random", permittedUser)));
            rows.add(Button.row(button(id++, settings.differentDifficulties() ? "改为固定难度" : "允许不同难度",
                            edit + "difficulty " + (settings.differentDifficulties() ? "fixed" : "any"), permittedUser),
                    button(id++, "必选/禁用 Mod", menu + "mods", permittedUser)));
            rows.add(Button.row(input(id++, "设置时长", edit + "hours ", permittedUser),
                    input(id++, "设置星数范围", edit + "stars ", permittedUser)));
            rows.add(Button.row(button(id++, settings.skillAdjustment() ? "关闭水平调整" : "开启水平调整",
                            edit + "skill " + (settings.skillAdjustment() ? "off" : "on"), permittedUser),
                    button(id++, "重新抽取", edit + "reroll", permittedUser)));
            Button start = button(id++, "开始挑战", "/gch start " + draft.id() + " " + draft.revision(), permittedUser);
            if (!mayStart || !ChallengeService.ready(draft)) start.disable();
            rows.add(Button.row(start, button(id, "计分规则", "/gch rules", permittedUser)));
        }
        return PendingMessage.ofMarkdownRaw(content.trim(), List.copyOf(rows));
    }

    private static Button button(int id, String label, String command, String user) {
        return Button.command(id, label, command).permit(user);
    }

    private static Button input(int id, String label, String command, String user) {
        Button button = button(id, label, command, user);
        button.getAction().setEnter(false);
        return button;
    }
}
