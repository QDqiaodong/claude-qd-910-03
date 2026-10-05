package com.shipyard.dock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;

/** 修造合同新建与局部更新入参 */
@Data
public class ContractRequest {

    private String contractNo;
    private Long vesselId;
    private Long ownerId;
    private String contractType;
    private BigDecimal amount;
    private LocalDate signDate;
    private LocalDate deliveryDate;
    private String status;
    private String note;
}
