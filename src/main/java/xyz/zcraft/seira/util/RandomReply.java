package xyz.zcraft.seira.util;

import java.util.concurrent.ThreadLocalRandom;

public class RandomReply {
    public static String loading() {
        return roll(RandomReplyTexts.LOADING_PREFIXES)
                + roll(RandomReplyTexts.LOADING_TEXTS)
                + roll(RandomReplyTexts.LOADING_SUFFIXES);
    }

    private static <T> T roll(T[] arr) {
        return arr[ThreadLocalRandom.current().nextInt(arr.length)];
    }
}

class RandomReplyTexts {
    public static final String[] LOADING_PREFIXES = new String[]{
            "正在", "即将", "马上", "准备", "开始"
    };
    public static final String[] LOADING_TEXTS = new String[]{
            "刷PP", "处理", "干活", "修BP", "打舞萌", "打中二", "联系ppy", "给你擦皮鞋", "插U盘",
            "氛围编程", "给群友炒菜", "看百合漫画", "进行rework", "享受notelock", "摸鱼", "寻找mp", "偷偷当de",
            "大调查群友"
    };
    public static final String[] LOADING_SUFFIXES = new String[]{
            "...", "!", "~", "喵", "喵...", "喵!", "喵~"
    };
}
