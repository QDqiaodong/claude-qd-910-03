package com.shipyard.dock.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shipyard.dock.common.ApiResponse;
import com.shipyard.dock.service.StatsService;

/**
 * 统计：总览数字、词典与五个模块的分组统计（全部走手写 JPQL）。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService service;

    public StatsController(StatsService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.success(service.overview());
    }

    @GetMapping("/dict")
    public ApiResponse<Map<String, Object>> dict() {
        return ApiResponse.success(service.dict());
    }

    @GetMapping("/berth")
    public ApiResponse<Map<String, Object>> berth() {
        return ApiResponse.success(service.berthStat());
    }

    @GetMapping("/block")
    public ApiResponse<Map<String, Object>> block() {
        return ApiResponse.success(service.blockStat());
    }

    @GetMapping("/lift")
    public ApiResponse<Map<String, Object>> lift() {
        return ApiResponse.success(service.liftStat());
    }

    @GetMapping("/survey")
    public ApiResponse<Map<String, Object>> survey() {
        return ApiResponse.success(service.surveyStat());
    }

    @GetMapping("/contract")
    public ApiResponse<Map<String, Object>> contract() {
        return ApiResponse.success(service.contractStat());
    }

    @GetMapping("/vessel")
    public ApiResponse<Map<String, Object>> vessel() {
        return ApiResponse.success(service.vesselStat());
    }
}
