package com.shipyard.dock.dto;

import java.math.BigDecimal;

import lombok.Data;

/** 吊次开始入参：记录司机、当前吊装阶段、风力与预估重量；不允许换设备 */
@Data
public class LiftStartRequest {

    private String operator;

    /** 当前吊装阶段：起钩 / 翻身 / 转运 / 落位 等 */
    private String stage;

    private Integer windLevel;

    private BigDecimal liftWeightT;
}
