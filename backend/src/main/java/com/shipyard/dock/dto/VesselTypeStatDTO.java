package com.shipyard.dock.dto;

import java.math.BigDecimal;

import com.shipyard.dock.common.Dict;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 手写 JPQL ⑥：按船舶类型统计条数、平均船长与排水量合计。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VesselTypeStatDTO {

    private String vesselType;
    private String vesselTypeName;
    private long count;
    private double avgLength;
    private double displacementSum;

    public VesselTypeStatDTO(String vesselType, Long count, Double avgLength, BigDecimal displacementSum) {
        this(vesselType, Dict.vesselTypeName(vesselType),
                count == null ? 0L : count,
                avgLength == null ? 0.0 : round1(avgLength),
                displacementSum == null ? 0.0 : round1(displacementSum.doubleValue()));
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
