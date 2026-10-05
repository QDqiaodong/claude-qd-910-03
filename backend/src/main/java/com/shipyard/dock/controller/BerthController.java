package com.shipyard.dock.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.shipyard.dock.common.ApiResponse;
import com.shipyard.dock.domain.Berth;
import com.shipyard.dock.dto.BerthMoveRequest;
import com.shipyard.dock.dto.BerthRequest;
import com.shipyard.dock.service.BerthService;

/**
 * 模块一：船台与船坞。
 *
 * <p>页面形态为「船台平面占位图」：船台 / 船坞排成格子，格子里的船占位块可以拖来拖去改船位。</p>
 */
@RestController
@RequestMapping("/api/berth")
public class BerthController {

    private final BerthService service;

    public BerthController(BerthService service) {
        this.service = service;
    }

    @GetMapping("/board")
    public ApiResponse<Map<String, Object>> board() {
        return ApiResponse.success(service.board());
    }

    @GetMapping("/list")
    public ApiResponse<List<Berth>> list() {
        return ApiResponse.success(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Berth> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<Berth> create(@RequestBody BerthRequest request) {
        return ApiResponse.success("船台已建档", service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Berth> update(@PathVariable Long id, @RequestBody BerthRequest request) {
        return ApiResponse.success("船台已更新", service.update(id, request));
    }

    /** 排船入台（占位图上把船拖进格子） */
    @PostMapping("/{id}/occupy")
    public ApiResponse<Berth> occupy(@PathVariable Long id, @RequestParam Long vesselId) {
        return ApiResponse.success("船舶已排入船台", service.occupy(id, vesselId));
    }

    /**
     * 移泊（占位图上把在台船舶拖到另一座船台）：服务端原子切换——
     * 船仍在预期原船台、目标仍可用且尺寸 / 承载校验通过时，同时腾空原船台并占用目标船台；
     * 任一失败保留操作前的原船台占位，并留痕原船台、目标船台、操作时间与结果。
     */
    @PostMapping("/move")
    public ApiResponse<Map<String, Object>> move(@RequestBody BerthMoveRequest request) {
        Map<String, Object> result = service.move(request);
        String message = String.valueOf(result.get("message"));
        if (Boolean.TRUE.equals(result.get("success"))) {
            return ApiResponse.success(message, result);
        }
        return ApiResponse.fail(message);
    }

    /** 出坞 / 让位（把船拖出格子） */
    @PostMapping("/{id}/release")
    public ApiResponse<Berth> release(@PathVariable Long id) {
        return ApiResponse.success("船台已腾空", service.release(id));
    }

    /** 状态流转：空闲 → 占用 → 检修 → 空闲 */
    @PostMapping("/{id}/status")
    public ApiResponse<Berth> transfer(@PathVariable Long id, @RequestParam String status) {
        return ApiResponse.success("船台状态已更新", service.transfer(id, status));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.success("船台已删除", null);
    }
}
