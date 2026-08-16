package xyz.zcraft.seira.bot.data;

import java.util.List;

public record Panel(
        List<PanelItem> items,
        String remark,
        Integer version
) {
}
