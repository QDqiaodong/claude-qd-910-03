package com.shipyard.dock.dto;

import lombok.Data;

/** 设备恢复后，对单个待复核任务的处置：RESTORE 恢复原排程 / KEEP_ALTERNATIVE 保留替代设备 */
@Data
public class ScheduleReviewRequest {

    /** RESTORE 恢复原设备 / KEEP_ALTERNATIVE 保留当前替代设备 */
    private String decision;

    private String operator;

    private String note;
}
