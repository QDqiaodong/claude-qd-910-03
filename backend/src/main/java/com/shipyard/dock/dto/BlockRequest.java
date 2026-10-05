package com.shipyard.dock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;

/**
 * 分段新建与局部更新入参。
 *
 * <p>注意：blockCode 由 Hibernate 生成器自动生成，入参里不收。</p>
 */
@Data
public class BlockRequest {

    private Long vesselId;
    private String blockName;
    private String blockType;
    private BigDecimal weightT;
    private Integer segSeq;
    private String status;
    private Boolean painted;
    private LocalDate paintFinishDate;
    private String workshop;
    private LocalDate planStartDate;
    private LocalDate actualFinishDate;
    private String note;
}
