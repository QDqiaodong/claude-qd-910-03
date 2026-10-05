package com.shipyard.dock.dto;

import java.time.LocalDate;

import lombok.Data;

/** 搭载任务新建与局部更新入参 */
@Data
public class ErectionRequest {

    private Long vesselId;
    private Long blockId;
    private Integer segSeq;
    private Long berthId;
    private LocalDate planDate;
    private String status;

    /** 排程起重设备（可选）；指派对同样要过能力 / 区域 / 档期校验 */
    private Long craneId;

    private String note;
}
