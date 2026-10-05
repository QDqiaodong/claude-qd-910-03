package com.shipyard.dock.dto;

import lombok.Data;

/** 船东新建与局部更新入参 */
@Data
public class OwnerRequest {

    private String ownerNo;
    private String ownerName;
    private String ownerType;
    private String contactPerson;
    private String contactPhone;
    private String country;
    private String coopLevel;
    private String note;
}
