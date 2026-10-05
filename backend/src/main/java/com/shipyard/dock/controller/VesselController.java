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
import com.shipyard.dock.domain.Contract;
import com.shipyard.dock.domain.Owner;
import com.shipyard.dock.domain.Vessel;
import com.shipyard.dock.dto.ContractRequest;
import com.shipyard.dock.dto.OwnerRequest;
import com.shipyard.dock.dto.VesselRequest;
import com.shipyard.dock.service.ContractService;
import com.shipyard.dock.service.OwnerService;
import com.shipyard.dock.service.PaymentSettlementService;
import com.shipyard.dock.service.VesselService;

/**
 * 模块二：船舶与修造合同（船东名录也挂在这里）。
 *
 * <p>页面形态为「时间轴 + 合同信息卡」：一条船一条时间轴，右侧挂合同信息卡。</p>
 */
@RestController
@RequestMapping("/api/vessel")
public class VesselController {

    private final VesselService vesselService;
    private final ContractService contractService;
    private final OwnerService ownerService;
    private final PaymentSettlementService paymentSettlementService;

    public VesselController(VesselService vesselService, ContractService contractService,
            OwnerService ownerService, PaymentSettlementService paymentSettlementService) {
        this.vesselService = vesselService;
        this.contractService = contractService;
        this.ownerService = ownerService;
        this.paymentSettlementService = paymentSettlementService;
    }

    // ---------------- 船舶 ----------------

    @GetMapping("/board")
    public ApiResponse<Map<String, Object>> board() {
        return ApiResponse.success(vesselService.board());
    }

    @GetMapping("/list")
    public ApiResponse<List<Vessel>> list() {
        return ApiResponse.success(vesselService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Vessel> get(@PathVariable Long id) {
        return ApiResponse.success(vesselService.get(id));
    }

    @GetMapping("/{id}/detail")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.success(vesselService.detail(id));
    }

    @PostMapping
    public ApiResponse<Vessel> create(@RequestBody VesselRequest request) {
        return ApiResponse.success("船舶已建档", vesselService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Vessel> update(@PathVariable Long id, @RequestBody VesselRequest request) {
        return ApiResponse.success("船舶已更新", vesselService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        vesselService.delete(id);
        return ApiResponse.success("船舶已删除", null);
    }

    // ---------------- 修造合同 ----------------

    @GetMapping("/contracts/board")
    public ApiResponse<Map<String, Object>> contractBoard() {
        return ApiResponse.success(contractService.board());
    }

    @GetMapping("/contracts/list")
    public ApiResponse<List<Contract>> contractList() {
        return ApiResponse.success(contractService.list());
    }

    @GetMapping("/contracts/{id}")
    public ApiResponse<Contract> contractGet(@PathVariable Long id) {
        return ApiResponse.success(contractService.get(id));
    }

    @PostMapping("/contracts")
    public ApiResponse<Contract> contractCreate(@RequestBody ContractRequest request) {
        return ApiResponse.success("合同已登记", contractService.create(request));
    }

    @PutMapping("/contracts/{id}")
    public ApiResponse<Contract> contractUpdate(@PathVariable Long id, @RequestBody ContractRequest request) {
        return ApiResponse.success("合同已更新", contractService.update(id, request));
    }

    /** 推进一级合同状态：草拟 → 已签 → 履行中（生成付款节点）→ 已结算（末笔付款自动完成） */
    @PostMapping("/contracts/{id}/advance")
    public ApiResponse<Contract> contractAdvance(@PathVariable Long id,
            @RequestParam(required = false) String operator) {
        return ApiResponse.success("合同状态已推进", contractService.advance(id, operator));
    }

    @DeleteMapping("/contracts/{id}")
    public ApiResponse<Void> contractDelete(@PathVariable Long id) {
        contractService.delete(id);
        return ApiResponse.success("合同已删除", null);
    }

    // ---------------- 合同付款节点（结算联动） ----------------

    /** 合同结算详情：合同 + 全部付款节点（实时状态 / 阻塞 / 冻结证据）+ 只追加的操作记录 */
    @GetMapping("/contracts/{id}/settlement")
    public ApiResponse<Map<String, Object>> contractSettlement(@PathVariable Long id) {
        return ApiResponse.success(paymentSettlementService.contractSettlement(id));
    }

    /** 手工复核：按当前合同金额 / 报验结论 / 船舶状态重算待结算节点（已付款节点不动） */
    @PostMapping("/contracts/{id}/recheck")
    public ApiResponse<Map<String, Object>> contractRecheck(@PathVariable Long id,
            @RequestParam String operator) {
        return ApiResponse.success("已按最新数据复核付款节点",
                paymentSettlementService.recheckContract(id, operator));
    }

    /**
     * 确认付款节点：后端按合同金额 × 比例、有效报验结论、当前船舶状态、前序节点重新校验，
     * 通过才扣减合同已付金额并把节点置为已付款；并发 / 重试只有一笔有效结果，
     * 失败整体回滚但保留操作者驳回痕迹。
     */
    @PostMapping("/payment-nodes/{nodeId}/confirm")
    public ApiResponse<Map<String, Object>> confirmPayment(@PathVariable Long nodeId,
            @RequestParam String operator) {
        return ApiResponse.success("付款节点已确认", paymentSettlementService.confirm(nodeId, operator));
    }

    // ---------------- 船东名录 ----------------

    @GetMapping("/owners")
    public ApiResponse<List<Owner>> owners() {
        return ApiResponse.success(ownerService.list());
    }

    @PostMapping("/owners")
    public ApiResponse<Owner> ownerCreate(@RequestBody OwnerRequest request) {
        return ApiResponse.success("船东已建档", ownerService.create(request));
    }

    @PutMapping("/owners/{id}")
    public ApiResponse<Owner> ownerUpdate(@PathVariable Long id, @RequestBody OwnerRequest request) {
        return ApiResponse.success("船东已更新", ownerService.update(id, request));
    }

    @DeleteMapping("/owners/{id}")
    public ApiResponse<Void> ownerDelete(@PathVariable Long id) {
        ownerService.delete(id);
        return ApiResponse.success("船东已删除", null);
    }
}
