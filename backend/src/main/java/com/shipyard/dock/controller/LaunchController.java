package com.shipyard.dock.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.shipyard.dock.common.ApiResponse;
import com.shipyard.dock.domain.LaunchClearance;
import com.shipyard.dock.service.LaunchService;

/**
 * 模块六：下水放行台。
 *
 * <p>页面形态为「放行台 + 放行单」：在台船舶一览其放行条件达成情况，
 * 每条船可生成一张放行单；放行单保留生成时快照，整改期间可复核 / 签认，
 * 全部条件满足并经质量签认后才能确认下水放行。</p>
 */
@RestController
@RequestMapping("/api/launch")
public class LaunchController {

    private final LaunchService service;

    public LaunchController(LaunchService service) {
        this.service = service;
    }

    /** 放行台总览：在厂船舶 + 实时放行校验 + 全部放行单 */
    @GetMapping("/board")
    public ApiResponse<Map<String, Object>> board() {
        return ApiResponse.success(service.board());
    }

    @GetMapping("/list")
    public ApiResponse<List<LaunchClearance>> list() {
        return ApiResponse.success(service.list());
    }

    /** 某船的全部放行单（船舶详情页追溯用） */
    @GetMapping("/vessel/{vesselId}")
    public ApiResponse<List<LaunchClearance>> ofVessel(@PathVariable Long vesselId) {
        return ApiResponse.success(service.ofVessel(vesselId));
    }

    /** 放行单详情：单据 + 生成时快照 + 复核 / 操作记录 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.success(service.detail(id));
    }

    /** 生成放行单（同一船同时只允许一张待确认单） */
    @PostMapping("/generate")
    public ApiResponse<Map<String, Object>> generate(@RequestParam Long vesselId,
            @RequestParam String operator) {
        return ApiResponse.success("放行单已生成", service.generate(vesselId, operator));
    }

    /** 沿用原单整改后复核：追加复核记录，生成快照不变 */
    @PostMapping("/{id}/recheck")
    public ApiResponse<Map<String, Object>> recheck(@PathVariable Long id, @RequestParam String operator) {
        return ApiResponse.success("复核完成", service.recheck(id, operator));
    }

    /** 质量签认：签认时复核必须全部通过 */
    @PostMapping("/{id}/sign")
    public ApiResponse<Map<String, Object>> sign(@PathVariable Long id, @RequestParam String operator) {
        return ApiResponse.success("放行单已签认", service.sign(id, operator));
    }

    /** 最终确认：同一事务内船舶下水 + 船台释放 + 放行单确认 */
    @PostMapping("/{id}/confirm")
    public ApiResponse<Map<String, Object>> confirm(@PathVariable Long id, @RequestParam String operator) {
        return ApiResponse.success("已确认下水放行", service.confirm(id, operator));
    }

    /** 作废：单据留痕，签认随单失效 */
    @PostMapping("/{id}/void")
    public ApiResponse<Map<String, Object>> voidSheet(@PathVariable Long id, @RequestParam String operator,
            @RequestParam(required = false) String reason) {
        return ApiResponse.success("放行单已作废", service.voidSheet(id, operator, reason));
    }

    /** 作废后按最新数据重开：旧单留痕，新单重新校验、重新签认 */
    @PostMapping("/{id}/regenerate")
    public ApiResponse<Map<String, Object>> regenerate(@PathVariable Long id, @RequestParam String operator,
            @RequestParam(required = false) String reason) {
        return ApiResponse.success("已作废原单并按最新数据重开", service.regenerate(id, operator, reason));
    }
}
