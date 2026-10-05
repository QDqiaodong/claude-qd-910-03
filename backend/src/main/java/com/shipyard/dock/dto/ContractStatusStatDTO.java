package com.shipyard.dock.dto;

import java.math.BigDecimal;

import com.shipyard.dock.common.Dict;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 手写 JPQL ⑤：按合同状态统计份数、合同金额合计与平均金额（万元）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContractStatusStatDTO {

    private String status;
    private String statusName;
    private long count;
    private double amountSum;
    private double avgAmount;

    public ContractStatusStatDTO(String status, Long count, BigDecimal amountSum, Double avgAmount) {
        this(status, Dict.contractStatusName(status),
                count == null ? 0L : count,
                amountSum == null ? 0.0 : round1(amountSum.doubleValue()),
                avgAmount == null ? 0.0 : round1(avgAmount));
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
