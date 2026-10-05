package com.shipyard.dock.dto;

import lombok.Data;

/** 人工改派单个未开始任务（同样要过能力 / 区域 / 档期三重校验） */
@Data
public class ReassignRequest {

    private Long targetCraneId;

    private String operator;

    private String note;
}
