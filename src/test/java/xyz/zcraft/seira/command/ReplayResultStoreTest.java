package xyz.zcraft.seira.command;

import org.junit.jupiter.api.Test;
import xyz.zcraft.seira.api.APIHelper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayResultStoreTest {
    @Test
    void storesAndRemovesReplayResultsByTaskId() {
        ReplayResultStore store = new ReplayResultStore();
        APIHelper.ReplayRenderResult result = new APIHelper.ReplayRenderResult("video-url", "task-1");

        store.put("task-1", result);
        assertEquals(result, store.get("task-1"));

        store.remove("task-1");
        assertNull(store.get("task-1"));
    }

    @Test
    void rejectsBlankTaskIds() {
        ReplayResultStore store = new ReplayResultStore();
        APIHelper.ReplayRenderResult result = new APIHelper.ReplayRenderResult("video-url", "task-1");

        assertThrows(IllegalArgumentException.class, () -> store.put(" ", result));
        assertThrows(IllegalArgumentException.class, () -> store.get(" "));
    }
}
