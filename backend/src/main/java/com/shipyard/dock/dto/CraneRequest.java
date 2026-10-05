package com.shipyard.dock.dto;

import java.math.BigDecimal;

import lombok.Data;

/** 起重设备新建与局部更新入参 */
@Data
public class CraneRequest {

    private String craneNo;
    private String craneName;
    private String craneType;
    private BigDecimal ratedLoadT;
    private BigDecimal spanM;
    private BigDecimal liftHeightM;
    private String status;
    private String location;
    private String note;
}
