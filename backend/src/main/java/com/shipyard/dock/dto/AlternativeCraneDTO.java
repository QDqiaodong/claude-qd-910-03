package com.shipyard.dock.dto;

import java.math.BigDecimal;

import lombok.Data;

/**
 * 替代设备候选：自动筛选结果同时给出能力、作业区域、档期三项匹配结论与不可选原因，
 * 前端可以直接展示「为什么这台能替 / 不能替」。
 */
@Data
public class AlternativeCraneDTO {

    private Long craneId;
    private String craneNo;
    private String craneName;
    private String craneType;
    private String craneTypeName;
    private BigDecimal ratedLoadT;
    private String location;

    /** 能力匹配：额定起重量 >= 分段重量 */
    private boolean capacityMatch;
    /** 作业区域匹配：固定设备需与船台同区，履带吊 / 浮吊可机动 */
    private boolean areaMatch;
    /** 档期匹配：计划日期没有其它未开始任务或进行中吊次占用 */
    private boolean scheduleMatch;

    private String capacityText;
    private String areaText;
    private String scheduleText;
    private String rejectReason;

    public boolean isEligible() {
        return capacityMatch && areaMatch && scheduleMatch;
    }
}
