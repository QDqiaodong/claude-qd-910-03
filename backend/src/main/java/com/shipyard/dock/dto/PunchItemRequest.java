package com.shipyard.dock.dto;

import java.time.LocalDate;

import lombok.Data;

/** 遗留项新建与局部更新入参 */
@Data
public class PunchItemRequest {

    private Long surveyId;
    private Long blockId;
    private String title;
    private String description;
    private String severity;
    private LocalDate dueDate;
    private String owner;
    private String status;
}
