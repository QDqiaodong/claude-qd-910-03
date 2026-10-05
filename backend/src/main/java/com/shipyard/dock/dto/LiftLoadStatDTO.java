package com.shipyard.dock.dto;

import java.math.BigDecimal;

import com.shipyard.dock.common.Dict;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 手写 JPQL ③：按起重设备统计吊次数、吊装总吨位与平均载荷率。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LiftLoadStatDTO {

    private String craneType;
    private String craneTypeName;
    private long liftCount;
    private double weightSum;
    private double avgLoadRate;

    public LiftLoadStatDTO(String craneType, Long liftCount, BigDecimal weightSum, Double avgLoadRate) {
        this(craneType, Dict.craneTypeName(craneType),
                liftCount == null ? 0L : liftCount,
                weightSum == null ? 0.0 : round1(weightSum.doubleValue()),
                avgLoadRate == null ? 0.0 : round1(avgLoadRate));
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
