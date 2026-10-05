package com.shipyard.dock.dto;

import lombok.Data;

/**
 * 设备进入检修 / 停用的入参。
 *
 * <p>decision：AUTO 自动筛选能力 / 作业区域 / 档期都匹配的替代设备并改派；
 * HOLD 保留原排程，全部置为待复核。两种处置在同一个事务内完成，不留半成品。</p>
 */
@Data
public class CraneUnavailableRequest {

    /** MAINTAINING 检修 / RETIRED 停用 */
    private String status;

    /** 停用 / 检修原因 */
    private String reason;

    /** 操作人（责任追溯） */
    private String operator;

    /** AUTO 自动改派 / HOLD 保留待复核 */
    private String decision;

    /** 设备上若有进行中吊次，必须填写安全处置记录（原地落位 / 摘钩停电 / 监护等） */
    private String safetyHandling;
}
