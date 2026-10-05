package com.shipyard.dock.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.shipyard.dock.common.BizException;
import com.shipyard.dock.common.Dict;
import com.shipyard.dock.domain.Contract;
import com.shipyard.dock.domain.Survey;
import com.shipyard.dock.domain.Vessel;
import com.shipyard.dock.dto.ContractRequest;
import com.shipyard.dock.repository.ContractRepository;
import com.shipyard.dock.repository.SurveyRepository;
import com.shipyard.dock.repository.VesselRepository;

/**
 * 模块二（下）：修造合同。
 *
 * <p>规则：① 合同编号唯一；② 合同金额必须 &gt; 0（且不得低于已结算金额合计）；
 * ③ 状态按 草拟 → 已签 → 履行中 → 已结算 逐级流转；
 * ④ 进入履行中即进入结算流程：按模板生成分期付款节点，付款节点依据合同金额、
 * 已完成的报验结论与当前船舶状态计算；⑤ 全部付款节点确认结算后合同才能整体结算。</p>
 */
@Service
public class ContractService {

    private final ContractRepository contractRepository;
    private final VesselRepository vesselRepository;
    private final SurveyRepository surveyRepository;
    private final ContractPaymentService paymentService;

    public ContractService(ContractRepository contractRepository, VesselRepository vesselRepository,
            SurveyRepository surveyRepository, ContractPaymentService paymentService) {
        this.contractRepository = contractRepository;
        this.vesselRepository = vesselRepository;
        this.surveyRepository = surveyRepository;
        this.paymentService = paymentService;
    }

    public List<Contract> list() {
        List<Contract> contracts = contractRepository.findAllByOrderByIdAsc();
        paymentService.attachSummary(contracts);
        return contracts;
    }

    public Contract get(Long id) {
        Contract contract = contractRepository.findById(id)
                .orElseThrow(() -> new BizException("合同不存在：" + id));
        paymentService.attachSummary(List.of(contract));
        return contract;
    }

    @Transactional
    public Contract create(ContractRequest request) {
        if (!StringUtils.hasText(request.getContractNo())) {
            throw new BizException("合同编号不能为空");
        }
        if (contractRepository.existsByContractNo(request.getContractNo())) {
            throw new BizException("合同编号已存在：" + request.getContractNo());
        }
        if (!Dict.isValidContractType(request.getContractType())) {
            throw new BizException("合同类型非法（新船建造 / 坞检修理 / 改装加长 三选一）：" + request.getContractType());
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("合同金额必须大于 0");
        }
        Contract contract = new Contract();
        apply(contract, request);
        bindVessel(contract);
        if (contract.getStatus() == null) {
            contract.setStatus("DRAFT");
        }
        if (!Dict.isValidContractStatus(contract.getStatus())) {
            throw new BizException("合同状态非法：" + contract.getStatus());
        }
        contract.setPaidAmount(BigDecimal.ZERO);
        contract.setCreatedAt(LocalDateTime.now());
        Contract saved = contractRepository.save(contract);
        if ("PERFORMING".equals(saved.getStatus())) {
            paymentService.ensureNodes(saved);
        }
        if ("SETTLED".equals(saved.getStatus())) {
            completeSettlement(saved);
        }
        paymentService.attachSummary(List.of(saved));
        return saved;
    }

    @Transactional
    public Contract update(Long id, ContractRequest request) {
        Contract contract = get(id);
        if (StringUtils.hasText(request.getContractNo()) && !request.getContractNo().equals(contract.getContractNo())
                && contractRepository.existsByContractNo(request.getContractNo())) {
            throw new BizException("合同编号已存在：" + request.getContractNo());
        }
        if (request.getContractType() != null && !Dict.isValidContractType(request.getContractType())) {
            throw new BizException("合同类型非法（新船建造 / 坞检修理 / 改装加长 三选一）：" + request.getContractType());
        }
        if (request.getStatus() != null && !request.getStatus().equals(contract.getStatus())
                && !Dict.canTransferContract(contract.getStatus(), request.getStatus())) {
            throw new BizException("合同状态不能从 " + Dict.contractStatusName(contract.getStatus())
                    + " 直接变为 " + Dict.contractStatusName(request.getStatus())
                    + "（草拟 → 已签 → 履行中 → 已结算，逐级推进）");
        }
        BigDecimal oldAmount = contract.getAmount();
        applyPartial(contract, request);
        if (request.getVesselId() != null) {
            bindVessel(contract);
        }
        if (contract.getAmount() == null || contract.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("合同金额必须大于 0");
        }
        // 已完成的付款节点不可倒改：合同金额不得低于已结算金额合计
        BigDecimal settledSum = paymentService.settledSum(id);
        if (contract.getAmount().compareTo(settledSum) < 0) {
            throw new BizException("合同金额不能小于已结算金额合计 " + settledSum
                    + " 万元（已完成的付款节点不可倒改）");
        }
        if ("SETTLED".equals(contract.getStatus()) && contract.getSettledAt() == null) {
            completeSettlement(contract);
        }
        Contract saved = contractRepository.save(contract);
        if ("PERFORMING".equals(saved.getStatus())) {
            paymentService.ensureNodes(saved);
        }
        // 合同金额变更：未结算节点按新金额重算，已结算节点保持历史金额
        if (request.getAmount() != null
                && (oldAmount == null || saved.getAmount().compareTo(oldAmount) != 0)) {
            paymentService.recalcForContract(id, "合同金额由 " + oldAmount + " 万元变更为 " + saved.getAmount() + " 万元");
        }
        paymentService.attachSummary(List.of(saved));
        return saved;
    }

    /** 逐级推进合同状态；进入履行中生成付款节点，推进到已结算要求全部付款节点已结算 */
    @Transactional
    public Contract advance(Long id) {
        Contract contract = contractRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("合同不存在：" + id));
        int index = Dict.CONTRACT_FLOW.indexOf(contract.getStatus());
        if (index < 0 || index + 1 >= Dict.CONTRACT_FLOW.size()) {
            throw new BizException("合同 " + contract.getContractNo() + " 已处于终态「"
                    + Dict.contractStatusName(contract.getStatus()) + "」，无需再推进");
        }
        String next = Dict.CONTRACT_FLOW.get(index + 1);
        contract.setStatus(next);
        Contract saved = contractRepository.save(contract);
        if ("PERFORMING".equals(next)) {
            paymentService.ensureNodes(saved);
        }
        if ("SETTLED".equals(next)) {
            completeSettlement(saved);
        }
        paymentService.attachSummary(List.of(saved));
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        Contract contract = get(id);
        if (!"DRAFT".equals(contract.getStatus())) {
            throw new BizException("合同 " + contract.getContractNo() + " 已处于「"
                    + Dict.contractStatusName(contract.getStatus()) + "」，只有草拟状态才能删除");
        }
        contractRepository.deleteById(id);
    }

    /** 合同信息卡视图：合同 + 按状态统计 */
    public Map<String, Object> board() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Contract> contracts = contractRepository.findAllByOrderByIdAsc();
        paymentService.attachSummary(contracts);
        data.put("contracts", contracts);
        data.put("statuses", contractRepository.statByStatus());
        List<Map<String, Object>> statusMeta = new ArrayList<>();
        for (String status : Dict.CONTRACT_FLOW) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", status);
            item.put("name", Dict.contractStatusName(status));
            item.put("count", contracts.stream().filter(c -> status.equals(c.getStatus())).count());
            statusMeta.add(item);
        }
        data.put("statusMeta", statusMeta);
        data.put("types", Dict.CONTRACT_TYPES);
        return data;
    }

    /**
     * 合同整体结算：全部付款节点必须已确认结算（结算前必须引用至少一次报验通过的规则
     * 由交验进度款节点承载），结算合计必须等于合同金额；通过后记录结算时间与依据单号。
     */
    private void completeSettlement(Contract contract) {
        paymentService.ensureNodes(contract);
        paymentService.requireAllSettled(contract);
        contract.setSettledAt(LocalDateTime.now());
        List<Survey> passed = contract.getVesselId() == null
                ? List.of()
                : surveyRepository.findPassedOfVessel(contract.getVesselId());
        contract.setSettleRef(passed.isEmpty() ? null : passed.get(0).getSurveyNo());
    }

    private void apply(Contract contract, ContractRequest request) {
        contract.setContractNo(request.getContractNo());
        contract.setVesselId(request.getVesselId());
        contract.setOwnerId(request.getOwnerId());
        contract.setContractType(request.getContractType());
        contract.setAmount(request.getAmount());
        contract.setSignDate(request.getSignDate());
        contract.setDeliveryDate(request.getDeliveryDate());
        contract.setStatus(request.getStatus());
        contract.setNote(request.getNote());
    }

    private void applyPartial(Contract contract, ContractRequest request) {
        ifNotNull(request.getContractNo(), contract::setContractNo);
        ifNotNull(request.getVesselId(), contract::setVesselId);
        ifNotNull(request.getOwnerId(), contract::setOwnerId);
        ifNotNull(request.getContractType(), contract::setContractType);
        ifNotNull(request.getAmount(), contract::setAmount);
        ifNotNull(request.getSignDate(), contract::setSignDate);
        ifNotNull(request.getDeliveryDate(), contract::setDeliveryDate);
        ifNotNull(request.getStatus(), contract::setStatus);
        ifNotNull(request.getNote(), contract::setNote);
    }

    private void bindVessel(Contract contract) {
        if (contract.getVesselId() == null) {
            throw new BizException("合同必须绑定到一艘本厂的船舶");
        }
        Vessel vessel = vesselRepository.findById(contract.getVesselId())
                .orElseThrow(() -> new BizException("船舶不存在：" + contract.getVesselId()));
        contract.setVesselNo(vessel.getVesselNo());
        contract.setVesselName(vessel.getVesselName());
        if (contract.getOwnerId() == null) {
            contract.setOwnerId(vessel.getOwnerId());
            contract.setOwnerName(vessel.getOwnerName());
        }
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
