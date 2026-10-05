package com.shipyard.dock.dto;

import java.math.BigDecimal;

import lombok.Data;

/** 船舶新建与局部更新入参 */
@Data
public class VesselRequest {

    private String vesselNo;
    private String regNo;
    private String vesselName;
    private String vesselType;
    private Long ownerId;
    private BigDecimal lengthM;
    private BigDecimal widthM;
    private BigDecimal depthM;
    private BigDecimal grossTonnage;
    private BigDecimal displacementT;
    private Integer buildYear;
    private String status;
    private String note;
}
