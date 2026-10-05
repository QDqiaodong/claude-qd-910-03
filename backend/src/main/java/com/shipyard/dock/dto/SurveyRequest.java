package com.shipyard.dock.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 报验单登记入参：结论为不合格时必须至少带一条遗留项，否则服务层拦截。
 */
@Data
public class SurveyRequest {

    private Long blockId;
    private String surveyItem;
    private LocalDateTime surveyDate;
    private String authority;
    private String inspector;
    /** 结论：PASS / CONCESSION / FAIL */
    private String result;
    private String remark;

    /** 操作人：报验补录 / 改判 / 撤回触发结算重算时的操作者痕迹（不填记为报验台账） */
    private String operator;

    /** 判不合格时随单开出的遗留项 */
    private List<PunchItemRequest> punchItems = new ArrayList<>();
}
