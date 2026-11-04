package xyz.zcraft.seira.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class BrowserSessions {
    private final Map<String, Browser> browsers = new HashMap<>();

    synchronized void check(String operation, JsonObject arguments, String group) {
        if (operation.equals("/browsers/create")) return;
        String browserId = arguments.get("browserUuid").getAsString();
        Browser browser = browsers.get(browserId);
        if (browser == null || !browser.group().equals(group))
            throw new IllegalArgumentException("Browser does not belong to this group");
        if (arguments.has("pageUuid") && !browser.pages().contains(arguments.get("pageUuid").getAsString()))
            throw new IllegalArgumentException("Page does not belong to this browser");
    }

    synchronized void record(String operation, JsonObject arguments, String group, JsonElement result) {
        if (!result.isJsonObject()) return;
        JsonObject object = result.getAsJsonObject();
        if (!object.has("success") || !object.get("success").getAsBoolean()) return;
        JsonObject data = object.has("data") && object.get("data").isJsonObject() ? object.getAsJsonObject("data") : new JsonObject();
        if (operation.equals("/browsers/create")) {
            browsers.put(data.get("browser_uuid").getAsString(), new Browser(group, new HashSet<>()));
            return;
        }
        String browserId = arguments.get("browserUuid").getAsString();
        Browser browser = browsers.get(browserId);
        if (browser == null || !browser.group().equals(group)) return;
        if (operation.equals("/browsers/{browserUuid}/pages/create"))
            browser.pages().add(data.get("page_uuid").getAsString());
        else if (operation.equals("/browsers/{browserUuid}/close")) browsers.remove(browserId);
        else if (operation.endsWith("/pages/{pageUuid}/close"))
            browser.pages().remove(arguments.get("pageUuid").getAsString());
    }

    private record Browser(String group, Set<String> pages) {
    }
}
