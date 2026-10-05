package com.shipyard.dock.dto;

import java.math.BigDecimal;

import com.shipyard.dock.common.Dict;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 手写 JPQL ②：按分段状态统计段数、总重量与平均重量。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BlockStatusStatDTO {

    private String status;
    private String statusName;
    private long count;
    private double weightSum;
    private double avgWeight;

    /** sum(weightT) 出来是 BigDecimal，avg(weightT) 出来是 Double，构造器签名必须和 JPQL 一一对应 */
    public BlockStatusStatDTO(String status, Long count, BigDecimal weightSum, Double avgWeight) {
        this(status, Dict.blockStatusName(status),
                count == null ? 0L : count,
                weightSum == null ? 0.0 : round1(weightSum.doubleValue()),
                avgWeight == null ? 0.0 : round1(avgWeight));
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
