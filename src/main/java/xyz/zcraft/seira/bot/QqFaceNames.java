package xyz.zcraft.seira.bot;

import java.util.Map;

import static java.util.Map.entry;

/** Human-readable names for QQ's documented built-in face IDs. */
public final class QqFaceNames {
    private static final Map<String, String> NAMES = Map.<String, String>ofEntries(
            entry("0", "惊讶"), entry("1", "撇嘴"), entry("2", "色"), entry("3", "发呆"),
            entry("4", "得意"), entry("5", "流泪"), entry("6", "害羞"), entry("7", "闭嘴"),
            entry("8", "睡"), entry("9", "大哭"), entry("10", "尴尬"), entry("11", "发怒"),
            entry("12", "调皮"), entry("13", "呲牙"), entry("14", "微笑"), entry("15", "难过"),
            entry("16", "酷"), entry("18", "抓狂"), entry("19", "吐"), entry("20", "偷笑"),
            entry("21", "可爱"), entry("22", "白眼"), entry("23", "傲慢"), entry("24", "饥饿"),
            entry("25", "困"), entry("26", "惊恐"), entry("27", "流汗"), entry("28", "憨笑"),
            entry("29", "悠闲"), entry("30", "奋斗"), entry("31", "咒骂"), entry("32", "疑问"),
            entry("33", "嘘"), entry("34", "晕"), entry("35", "折磨"), entry("36", "衰"),
            entry("37", "骷髅"), entry("38", "敲打"), entry("39", "再见"), entry("41", "发抖"),
            entry("42", "爱情"), entry("43", "跳跳"), entry("46", "猪头"), entry("49", "拥抱"),
            entry("53", "蛋糕"), entry("54", "闪电"), entry("55", "炸弹"), entry("56", "刀"),
            entry("57", "足球"), entry("59", "便便"), entry("60", "咖啡"), entry("61", "饭"),
            entry("63", "玫瑰"), entry("64", "凋谢"), entry("66", "爱心"), entry("67", "心碎"),
            entry("69", "礼物"), entry("74", "太阳"), entry("75", "月亮"), entry("76", "赞"),
            entry("77", "踩"), entry("78", "握手"), entry("79", "胜利"), entry("85", "飞吻"),
            entry("86", "怄火"), entry("89", "西瓜"), entry("96", "冷汗"), entry("97", "擦汗"),
            entry("98", "抠鼻"), entry("99", "鼓掌"), entry("100", "糗大了"), entry("101", "坏笑"),
            entry("102", "左哼哼"), entry("103", "右哼哼"), entry("104", "哈欠"), entry("105", "鄙视"),
            entry("106", "委屈"), entry("107", "快哭了"), entry("108", "阴险"), entry("109", "左亲亲"),
            entry("110", "吓"), entry("111", "可怜"), entry("112", "菜刀"), entry("113", "啤酒"),
            entry("114", "篮球"), entry("115", "乒乓"), entry("116", "示爱"), entry("117", "瓢虫"),
            entry("118", "抱拳"), entry("119", "勾引"), entry("120", "拳头"), entry("121", "差劲"),
            entry("122", "爱你"), entry("123", "NO"), entry("124", "OK"), entry("125", "转圈"),
            entry("126", "磕头"), entry("127", "回头"), entry("128", "跳绳"), entry("129", "挥手"),
            entry("130", "激动"), entry("131", "街舞"), entry("132", "献吻"), entry("133", "左太极"),
            entry("134", "右太极"), entry("136", "双喜"), entry("137", "鞭炮"), entry("138", "灯笼"),
            entry("140", "K歌"), entry("144", "喝彩"), entry("145", "祈祷"), entry("146", "爆筋"),
            entry("147", "棒棒糖"), entry("148", "喝奶"), entry("151", "飞机"), entry("158", "钞票"),
            entry("168", "药"), entry("169", "手枪"), entry("171", "茶"), entry("172", "眨眼睛"),
            entry("173", "泪奔"), entry("174", "无奈"), entry("175", "卖萌"), entry("176", "小纠结"),
            entry("177", "喷血"), entry("178", "斜眼笑"), entry("179", "doge"), entry("180", "惊喜"),
            entry("181", "骚扰"), entry("182", "笑哭"), entry("183", "我最美"), entry("184", "河蟹"),
            entry("185", "羊驼"), entry("187", "幽灵"), entry("188", "蛋"), entry("190", "菊花"),
            entry("192", "红包"), entry("193", "大笑"), entry("194", "不开心"), entry("197", "冷漠"),
            entry("198", "呃"), entry("199", "好棒"), entry("200", "拜托"), entry("201", "点赞"),
            entry("202", "无聊"), entry("203", "托脸"), entry("204", "吃"), entry("205", "送花"),
            entry("206", "害怕"), entry("207", "花痴"), entry("208", "小样儿"), entry("210", "飙泪"),
            entry("211", "我不看"), entry("212", "托腮"), entry("214", "啵啵"), entry("215", "糊脸"),
            entry("216", "拍头"), entry("217", "扯一扯"), entry("218", "舔一舔"), entry("219", "蹭一蹭"),
            entry("220", "拽炸天"), entry("221", "顶呱呱"), entry("222", "抱抱"), entry("223", "暴击"),
            entry("224", "开枪"), entry("225", "撩一撩"), entry("226", "拍桌"), entry("227", "拍手"),
            entry("228", "恭喜"), entry("229", "干杯"), entry("230", "嘲讽"), entry("231", "哼"),
            entry("232", "佛系"), entry("233", "掐一掐"), entry("234", "惊呆"), entry("235", "颤抖"),
            entry("236", "啃头"), entry("237", "偷看"), entry("238", "扇脸"), entry("239", "原谅"),
            entry("240", "喷脸"), entry("241", "生日快乐"), entry("242", "头撞击"), entry("243", "甩头"),
            entry("244", "扔狗"), entry("245", "加油必胜"), entry("246", "加油抱抱"), entry("247", "口罩护体"),
            entry("260", "搬砖中"), entry("261", "忙到飞起"), entry("262", "脑阔疼"), entry("263", "沧桑"),
            entry("264", "捂脸"), entry("265", "辣眼睛"), entry("266", "哦哟"), entry("267", "头秃"),
            entry("268", "问号脸"), entry("269", "暗中观察"), entry("270", "emm"), entry("271", "吃瓜"),
            entry("272", "呵呵哒"), entry("273", "我酸了"), entry("274", "太南了"), entry("276", "辣椒酱"),
            entry("277", "汪汪"), entry("278", "汗"), entry("279", "打脸"), entry("280", "击掌"),
            entry("281", "无眼笑"), entry("282", "敬礼"), entry("283", "狂笑"), entry("284", "面无表情"),
            entry("285", "摸鱼"), entry("286", "魔鬼笑"), entry("287", "哦"), entry("288", "请"),
            entry("289", "睁眼"), entry("290", "敲开心"), entry("291", "震惊"), entry("292", "让我康康"),
            entry("293", "摸锦鲤"), entry("294", "期待"), entry("295", "拿到红包"), entry("296", "真好"),
            entry("297", "拜谢"), entry("298", "元宝"), entry("299", "牛啊"), entry("300", "胖三斤"),
            entry("301", "好闪"), entry("302", "左拜年"), entry("303", "右拜年"), entry("304", "红包包"),
            entry("305", "右亲亲"), entry("306", "牛气冲天"), entry("307", "喵喵"), entry("308", "求红包"),
            entry("309", "谢红包"), entry("310", "新年烟花"), entry("311", "打call"), entry("312", "变形"),
            entry("313", "嗑到了"), entry("314", "仔细分析"), entry("315", "加油"), entry("316", "我没事"),
            entry("317", "菜狗"), entry("318", "崇拜"), entry("319", "比心"), entry("320", "庆祝"),
            entry("321", "老色痞"), entry("322", "拒绝"), entry("323", "嫌弃"), entry("324", "吃糖"),
            entry("325", "惊吓"), entry("326", "生气"), entry("327", "加一"), entry("328", "错号"),
            entry("329", "对号"), entry("330", "完成"), entry("331", "明白"), entry("332", "举牌牌"),
            entry("333", "烟花"), entry("334", "虎虎生威"), entry("336", "豹富"), entry("337", "花朵脸"),
            entry("338", "我想开了"), entry("339", "舔屏"), entry("340", "热化了"), entry("341", "打招呼"),
            entry("342", "酸Q"), entry("343", "我方了"), entry("344", "大怨种"), entry("345", "红包多多"),
            entry("346", "你真棒棒"), entry("347", "大展宏兔"), entry("348", "福萝卜")
    );

    private QqFaceNames() {}

    public static String describe(String id) {
        if (id == null || id.isBlank()) return "[表情]";
        String name = NAMES.get(id);
        return name == null ? "[表情:未知(#" + id + ")]" : "[表情:" + name + "]";
    }
}
