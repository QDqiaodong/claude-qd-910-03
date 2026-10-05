package com.shipyard.dock.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

/**
 * 吊次入参。
 *
 * <p>register 登记吊次：status=SCHEDULED（默认，未开始排程）或 DONE（一次性补登历史吊次）。
 * start / complete 走各自的专用入参。</p>
 */
@Data
public class LiftRequest {

    private String liftNo;
    private Long erectionId;
    private Long craneId;
    private BigDecimal liftWeightT;
    private LocalDateTime liftTime;
    private String operator;
    private Integer windLevel;
    private String remark;

    /** SCHEDULED 未开始（默认） / DONE 补登历史吊次 */
    private String status;
}
