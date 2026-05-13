package xyz.zcraft.data;

import lombok.Data;

@Data
public class RenderStat {
    private String jobId;
    private String status;
    private String progress;
    private String speed;
    private String eta;
}
