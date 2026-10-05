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
import org.springframework.web.bind.annotation.RestController;

import com.shipyard.dock.common.ApiResponse;
import com.shipyard.dock.domain.Crane;
import com.shipyard.dock.domain.Erection;
import com.shipyard.dock.domain.Lift;
import com.shipyard.dock.dto.CraneRequest;
import com.shipyard.dock.dto.ErectionRequest;
import com.shipyard.dock.dto.LiftCompleteRequest;
import com.shipyard.dock.dto.LiftRequest;
import com.shipyard.dock.dto.LiftStartRequest;
import com.shipyard.dock.service.CraneService;
import com.shipyard.dock.service.ErectionService;

/**
 * 模块四：搭载与吊装。
 *
 * <p>页面形态为「吊装时间戳序列 + 吊次借记卡」：左边一条按时间排的吊装流水，
 * 右边一张吊次卡，超额定起重量直接拦在后端。</p>
 */
@RestController
@RequestMapping("/api/erection")
public class ErectionController {

    private final ErectionService service;
    private final CraneService craneService;

    public ErectionController(ErectionService service, CraneService craneService) {
        this.service = service;
        this.craneService = craneService;
    }

    @GetMapping("/board")
    public ApiResponse<Map<String, Object>> board() {
        return ApiResponse.success(service.board());
    }

    @GetMapping("/list")
    public ApiResponse<List<Erection>> list() {
        return ApiResponse.success(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Erection> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @GetMapping("/{id}/detail")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping
    public ApiResponse<Erection> create(@RequestBody ErectionRequest request) {
        return ApiResponse.success("搭载任务已排程", service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Erection> update(@PathVariable Long id, @RequestBody ErectionRequest request) {
        return ApiResponse.success("搭载任务已更新", service.update(id, request));
    }

    /** 推进搭载状态：已排程 → 待吊装 → 已完成 */
    @PostMapping("/{id}/advance")
    public ApiResponse<Erection> advance(@PathVariable Long id) {
        return ApiResponse.success("搭载状态已推进", service.advance(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.success("搭载任务已删除", null);
    }

    // ---------------- 吊次 ----------------

    @GetMapping("/lifts")
    public ApiResponse<List<Lift>> lifts() {
        return ApiResponse.success(service.lifts());
    }

    /**
     * 登记吊次：默认未开始（SCHEDULED，随排程可改派）；
     * 传 status=DONE 为补登历史吊次（超额定起重量 / 重复登记仍在后端拦下）。
     */
    @PostMapping("/{id}/lifts")
    public ApiResponse<Lift> registerLift(@PathVariable Long id, @RequestBody LiftRequest request) {
        return ApiResponse.success("吊次已登记", service.registerLift(id, request));
    }

    /** 吊次开始：未开始 → 进行中，归属设备锁定，禁止换机 */
    @PostMapping("/lifts/{liftId}/start")
    public ApiResponse<Lift> startLift(@PathVariable Long liftId, @RequestBody LiftStartRequest request) {
        return ApiResponse.success("吊次已开始，设备归属锁定", service.startLift(liftId, request));
    }

    /** 吊次完成：进行中 → 已完成，成为不可倒改的历史吊次 */
    @PostMapping("/lifts/{liftId}/complete")
    public ApiResponse<Lift> completeLift(@PathVariable Long liftId, @RequestBody LiftCompleteRequest request) {
        return ApiResponse.success("吊次已完成，归属设备落定", service.completeLift(liftId, request));
    }

    // ---------------- 起重设备 ----------------

    @GetMapping("/cranes")
    public ApiResponse<List<Crane>> cranes() {
        return ApiResponse.success(craneService.list());
    }

    @PostMapping("/cranes")
    public ApiResponse<Crane> createCrane(@RequestBody CraneRequest request) {
        return ApiResponse.success("起重设备已建档", craneService.create(request));
    }

    @PutMapping("/cranes/{id}")
    public ApiResponse<Crane> updateCrane(@PathVariable Long id, @RequestBody CraneRequest request) {
        return ApiResponse.success("起重设备已更新", craneService.update(id, request));
    }

    @DeleteMapping("/cranes/{id}")
    public ApiResponse<Void> deleteCrane(@PathVariable Long id) {
        craneService.delete(id);
        return ApiResponse.success("起重设备已删除", null);
    }
}
