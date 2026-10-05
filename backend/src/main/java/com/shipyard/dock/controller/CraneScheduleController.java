package com.shipyard.dock.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.shipyard.dock.common.ApiResponse;
import com.shipyard.dock.domain.Crane;
import com.shipyard.dock.domain.Erection;
import com.shipyard.dock.dto.AlternativeCraneDTO;
import com.shipyard.dock.dto.CraneUnavailableRequest;
import com.shipyard.dock.dto.ReassignRequest;
import com.shipyard.dock.dto.ScheduleReviewRequest;
import com.shipyard.dock.service.CraneService;
import com.shipyard.dock.service.ScheduleService;

/**
 * 起重设备检修状态 ↔ 吊装排程联动接口。
 *
 * <p>停用前先取影响面（未开始任务 / 进行中吊次 / 历史吊次 + 替代候选），
 * 停用处置二选一（自动改派 / 保留待复核），恢复后逐任务复核。</p>
 */
@RestController
@RequestMapping("/api/crane-schedule")
public class CraneScheduleController {

    private final ScheduleService scheduleService;
    private final CraneService craneService;

    public CraneScheduleController(ScheduleService scheduleService, CraneService craneService) {
        this.scheduleService = scheduleService;
        this.craneService = craneService;
    }

    /** 停用影响面汇总：未开始任务、进行中吊次、历史吊次、每个任务的替代候选 */
    @GetMapping("/cranes/{id}/impact")
    public ApiResponse<Map<String, Object>> impact(@PathVariable Long id) {
        return ApiResponse.success(scheduleService.impact(id));
    }

    /** 单个任务的替代设备候选（能力 / 区域 / 档期逐项给出结论） */
    @GetMapping("/tasks/{id}/alternatives")
    public ApiResponse<List<AlternativeCraneDTO>> alternatives(@PathVariable Long id) {
        return ApiResponse.success(scheduleService.alternativesForTask(id));
    }

    /** 设为检修 / 停用：AUTO 自动改派 / HOLD 保留待复核，同事务完成 */
    @PostMapping("/cranes/{id}/unavailable")
    public ApiResponse<Map<String, Object>> unavailable(@PathVariable Long id,
            @RequestBody CraneUnavailableRequest request) {
        return ApiResponse.success("设备状态与吊装排程已联动处置", scheduleService.markUnavailable(id, request));
    }

    /** 检修恢复：设备回到可用（待复核任务仍需逐任务确认，不自动回改排程） */
    @PostMapping("/cranes/{id}/recover")
    public ApiResponse<Crane> recover(@PathVariable Long id,
            @RequestParam(required = false) String operator) {
        return ApiResponse.success("设备已恢复可用，待复核任务请逐任务确认排程",
                scheduleService.recover(id, operator));
    }

    /** 当前全部待复核任务 */
    @GetMapping("/review-pending")
    public ApiResponse<List<Erection>> reviewPending() {
        return ApiResponse.success(scheduleService.reviewPendingTasks());
    }

    /** 恢复时对单个待复核任务：RESTORE 恢复原排程 / KEEP_ALTERNATIVE 保留替代 */
    @PostMapping("/tasks/{id}/review")
    public ApiResponse<Erection> review(@PathVariable Long id, @RequestBody ScheduleReviewRequest request) {
        return ApiResponse.success("排程复核完成", scheduleService.reviewTask(id, request));
    }

    /** 人工改派单个未开始任务到指定设备（三重校验） */
    @PostMapping("/tasks/{id}/reassign")
    public ApiResponse<Erection> reassign(@PathVariable Long id, @RequestBody ReassignRequest request) {
        return ApiResponse.success("任务已改派", scheduleService.reassign(id, request));
    }

    /** 设备详情（含三类吊次与排程日志） */
    @GetMapping("/cranes/{id}/detail")
    public ApiResponse<Map<String, Object>> craneDetail(@PathVariable Long id) {
        return ApiResponse.success(craneService.detail(id));
    }
}
