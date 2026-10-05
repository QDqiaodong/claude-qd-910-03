package com.shipyard.dock.dto;

import java.math.BigDecimal;

import lombok.Data;

/** 船台 / 船坞新建与局部更新入参（PUT 时为空的字段表示不改） */
@Data
public class BerthRequest {

    private String berthNo;
    private String berthName;
    private String berthType;
    private BigDecimal lengthM;
    private BigDecimal widthM;
    private BigDecimal capacityT;
    private Long vesselId;
    private String status;
    private String location;
    private String note;
}
