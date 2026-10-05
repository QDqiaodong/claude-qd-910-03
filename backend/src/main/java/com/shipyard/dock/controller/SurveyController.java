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
import com.shipyard.dock.domain.PunchItem;
import com.shipyard.dock.domain.Survey;
import com.shipyard.dock.dto.PunchItemRequest;
import com.shipyard.dock.dto.SurveyRequest;
import com.shipyard.dock.service.SurveyService;

/**
 * 模块五：报验与遗留项。
 *
 * <p>页面形态为「左右双栏对照」：左边报验项列表，右边遗留项清单，判定结论以印章形式盖在卡片上。
 * 报验单号由 {@code @SurveyNo} + Hibernate {@code @ValueGenerationType} 生成。</p>
 */
@RestController
@RequestMapping("/api/survey")
public class SurveyController {

    private final SurveyService service;

    public SurveyController(SurveyService service) {
        this.service = service;
    }

    @GetMapping("/board")
    public ApiResponse<Map<String, Object>> board() {
        return ApiResponse.success(service.board());
    }

    @GetMapping("/list")
    public ApiResponse<List<Survey>> list() {
        return ApiResponse.success(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Survey> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @GetMapping("/{id}/detail")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping
    public ApiResponse<Survey> create(@RequestBody SurveyRequest request) {
        Survey survey = service.create(request);
        return ApiResponse.success("报验单已登记，单号 " + survey.getSurveyNo(), survey);
    }

    @PutMapping("/{id}")
    public ApiResponse<Survey> update(@PathVariable Long id, @RequestBody SurveyRequest request) {
        return ApiResponse.success("报验单已更新", service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.success("报验单已删除", null);
    }

    // ---------------- 遗留项 ----------------

    @GetMapping("/punch-items")
    public ApiResponse<List<PunchItem>> punchList() {
        return ApiResponse.success(service.punchList());
    }

    @PostMapping("/punch-items")
    public ApiResponse<PunchItem> createPunch(@RequestBody PunchItemRequest request) {
        return ApiResponse.success("遗留项已开出", service.createPunch(request));
    }

    @PutMapping("/punch-items/{id}")
    public ApiResponse<PunchItem> updatePunch(@PathVariable Long id, @RequestBody PunchItemRequest request) {
        return ApiResponse.success("遗留项已更新", service.updatePunch(id, request));
    }

    /** 推进遗留项：开启 → 整改中 → 已闭环 */
    @PostMapping("/punch-items/{id}/advance")
    public ApiResponse<PunchItem> advancePunch(@PathVariable Long id) {
        return ApiResponse.success("遗留项已推进", service.advancePunch(id));
    }

    @DeleteMapping("/punch-items/{id}")
    public ApiResponse<Void> deletePunch(@PathVariable Long id) {
        service.deletePunch(id);
        return ApiResponse.success("遗留项已删除", null);
    }
}
