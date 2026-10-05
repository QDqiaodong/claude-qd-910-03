package com.shipyard.dock.dto;

import java.math.BigDecimal;

import lombok.Data;

/** 吊次完成入参：填写实际吊装重量 / 风力 / 备注，归属设备不可更改 */
@Data
public class LiftCompleteRequest {

    private BigDecimal liftWeightT;

    private Integer windLevel;

    private String remark;

    private String operator;
}
