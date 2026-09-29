package xyz.zcraft.seira.ai.data;


import com.google.gson.annotations.SerializedName;

import java.util.List;

/*
{
    "total_tokens": 993,
    "event": "message",
    "task_id": "01K0P6NXPHD62CXY80J3HPJCZC",
    "id": "01K0P6NXPHD62CXY80J3HPJCZC",
    "conversation_id": "01K0BXVWX8CV3HX81V2SPCRP8K",
    "answer": "有点遗憾知识库没找到相关内容。不过我很想知道你说“还可以”是在评价什么呀，是一部电影、一顿美食，还是其他方面呢？能多给我些提示，这样我们就能更畅快地交流啦。 ",
    "created_at": 1753091868,
    "latency": 4.05,
    "input_tokens": 937,
    "output_tokens": 56,
    "start_time_first_resp": 1753091868324,
    "latency_first_resp": 4050，
    "think_messages": ["推理过程消息内容"],
    "tool_messages": ["工具消息输出内容"]
}
 */
public record ChatQueryResponse (
        @SerializedName("total_tokens") Long totalTokens,
        @SerializedName("event") String event,
        @SerializedName("task_id") String taskId,
        @SerializedName("id") String id,
        @SerializedName("conversation_id") String conversationId,
        @SerializedName("answer") String answer,
        @SerializedName("created_at") Long createdAt,
        @SerializedName("latency") Double latency,
        @SerializedName("input_tokens") Long inputTokens,
        @SerializedName("output_tokens") Long outputTokens,
        @SerializedName("start_time_first_resp") Long startTimeFirstResp,
        @SerializedName("latency_first_resp") Long latencyFirstResp,
        @SerializedName("think_messages") List<String> thinkMessages,
        @SerializedName("tool_messages") List<String> toolMessages
){
}
