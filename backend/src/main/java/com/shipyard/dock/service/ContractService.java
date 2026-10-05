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
import com.shipyard.dock.domain.Vessel;
import com.shipyard.dock.dto.ContractRequest;
import com.shipyard.dock.repository.ContractRepository;
import com.shipyard.dock.repository.VesselRepository;

/**
 * 模块二（下）：修造合同。
 *
 * <p>规则：① 合同编号唯一；② 合同金额必须 &gt; 0，且是每个付款节点应付金额的计算基数；
 * ③ 状态按 草拟 → 已签 → 履行中 → 已结算 逐级流转；④ 推进到履行中即「进入结算流程」，
 * 按合同类型生成付款节点，节点能否付款由 {@link PaymentSettlementService} 依据
 * 合同金额、已完成报验结论、当前船舶状态与前序节点统一计算；⑤ 合同只有在全部付款节点
 * 已付款后才能置为已结算（不允许绕过节点直接结算）。</p>
 */
@Service
public class ContractService {

    private final ContractRepository contractRepository;
    private final VesselRepository vesselRepository;
    private final PaymentSettlementService settlementService;

    public ContractService(ContractRepository contractRepository, VesselRepository vesselRepository,
            PaymentSettlementService settlementService) {
        this.contractRepository = contractRepository;
        this.vesselRepository = vesselRepository;
        this.settlementService = settlementService;
    }

    /** 列表：附带实时结算状态与阻塞原因（与船舶合同列表、报验详情同源） */
    @Transactional(readOnly = true)
    public List<Contract> list() {
        return settlementService.decorateAll(contractRepository.findAllByOrderByIdAsc());
    }

    @Transactional(readOnly = true)
    public Contract get(Long id) {
        return settlementService.decorate(
                contractRepository.findById(id)
                        .orElseThrow(() -> new BizException("合同不存在：" + id)),
                true);
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
            throw new BizException("合同金额必须大于 0（付款节点的应付金额全部按合同金额 × 比例计算）");
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
        // 已结算不能直接登记出来：必须走过履行中、逐笔付款节点
        if ("SETTLED".equals(contract.getStatus())) {
            throw new BizException("新登记合同不能直接置为已结算：需先履行中并逐笔完成付款节点");
        }
        contract.setPaidAmount(BigDecimal.ZERO.setScale(2));
        contract.setCreatedAt(LocalDateTime.now());
        Contract saved = contractRepository.save(contract);
        if ("PERFORMING".equals(saved.getStatus())) {
            settlementService.ensureNodes(saved, request.getOperator());
        }
        return get(saved.getId());
    }

    @Transactional
    public Contract update(Long id, ContractRequest request) {
        Contract raw = contractRepository.findById(id)
                .orElseThrow(() -> new BizException("合同不存在：" + id));
        String oldStatus = raw.getStatus();
        if (StringUtils.hasText(request.getContractNo()) && !request.getContractNo().equals(raw.getContractNo())
                && contractRepository.existsByContractNo(request.getContractNo())) {
            throw new BizException("合同编号已存在：" + request.getContractNo());
        }
        if (request.getContractType() != null && !Dict.isValidContractType(request.getContractType())) {
            throw new BizException("合同类型非法（新船建造 / 坞检修理 / 改装加长 三选一）：" + request.getContractType());
        }
        // 已生成付款节点后不允许改绑船舶：已付款证据是按原船舶的报验 / 状态冻结的
        if (request.getVesselId() != null && !request.getVesselId().equals(raw.getVesselId())
                && ("PERFORMING".equals(oldStatus) || "SETTLED".equals(oldStatus))) {
            throw new BizException("合同 " + raw.getContractNo()
                    + " 已进入结算流程并生成付款节点，不能改绑船舶（历史付款证据按原船舶报验冻结）");
        }
        BigDecimal oldAmount = raw.getAmount();
        boolean jumpToSettled = false;
        if (request.getStatus() != null && !request.getStatus().equals(oldStatus)
                && "SETTLED".equals(request.getStatus())) {
            // 已结算只能由末笔付款确认驱动，不允许直接改状态绕过节点
            Contract decorated = settlementService.decorate(raw, true);
            if (!"PAID".equals(decorated.getPaymentState())) {
                throw new BizException("合同 " + raw.getContractNo()
                        + " 还有付款节点未完成，不能直接置为已结算；请逐笔确认付款节点，末笔确认后自动结算");
            }
            jumpToSettled = true;
        } else if (request.getStatus() != null && !request.getStatus().equals(oldStatus)
                && !Dict.canTransferContract(oldStatus, request.getStatus())) {
            throw new BizException("合同状态不能从 " + Dict.contractStatusName(oldStatus)
                    + " 直接变为 " + Dict.contractStatusName(request.getStatus())
                    + "（草拟 → 已签 → 履行中 → 已结算，逐级推进）");
        }
        applyPartial(raw, request);
        if (request.getVesselId() != null) {
            bindVessel(raw);
        }
        if (raw.getAmount() == null || raw.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("合同金额必须大于 0（付款节点的应付金额全部按合同金额 × 比例计算）");
        }
        Contract saved = contractRepository.save(raw);

        if (jumpToSettled && saved.getSettledAt() == null) {
            saved.setSettledAt(LocalDateTime.now());
            contractRepository.save(saved);
        }
        // 直接把状态推进到履行中（非 advance 入口）：补建付款节点，完成首次条件判定
        if ("PERFORMING".equals(request.getStatus()) && !"PERFORMING".equals(oldStatus)) {
            settlementService.ensureNodes(saved, request.getOperator());
        }
        // 合同金额调整：待结算节点的应付金额联动重算，已付款节点按冻结金额保留不动
        if (oldAmount != null && oldAmount.compareTo(saved.getAmount()) != 0
                && "PERFORMING".equals(saved.getStatus())) {
            settlementService.recalcByAmount(saved, request.getOperator());
        }
        return get(id);
    }

    /** 逐级推进合同状态；推进到履行中生成付款节点；履行中 → 已结算必须全部节点已付款 */
    @Transactional
    public Contract advance(Long id) {
        return advance(id, null);
    }

    @Transactional
    public Contract advance(Long id, String operator) {
        Contract contract = contractRepository.findById(id)
                .orElseThrow(() -> new BizException("合同不存在：" + id));
        int index = Dict.CONTRACT_FLOW.indexOf(contract.getStatus());
        if (index < 0 || index + 1 >= Dict.CONTRACT_FLOW.size()) {
            throw new BizException("合同 " + contract.getContractNo() + " 已处于终态「"
                    + Dict.contractStatusName(contract.getStatus()) + "」，无需再推进");
        }
        String next = Dict.CONTRACT_FLOW.get(index + 1);
        if ("SETTLED".equals(next)) {
            // 结算由付款节点驱动：必须全部节点已付款（末笔确认时会自动置已结算）
            settlementService.decorate(contract, true);
            if (!"PAID".equals(contract.getPaymentState())) {
                List<String> pending = new ArrayList<>();
                for (Map<String, Object> node : contract.getPaymentNodes()) {
                    if ("PENDING".equals(node.get("status"))) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> blockers =
                                (List<Map<String, Object>>) node.get("blockers");
                        pending.add(node.get("nodeName") + "（" + joinMessages(blockers) + "）");
                    }
                }
                throw new BizException("合同 " + contract.getContractNo()
                        + " 的付款节点尚未全部完成，不能结算：" + String.join("；", pending)
                        + "。请在付款节点条件满足后逐笔确认，末笔确认将自动结算");
            }
            // 全部已付但状态未翻转的兜底（正常不会走到）
            contract.setStatus("SETTLED");
            if (contract.getSettledAt() == null) {
                contract.setSettledAt(LocalDateTime.now());
            }
            return contractRepository.save(contract);
        }
        contract.setStatus(next);
        Contract saved = contractRepository.save(contract);
        if ("PERFORMING".equals(next)) {
            // 进入结算流程：生成付款节点并按当前金额 / 报验 / 船舶状态完成首次判定
            settlementService.ensureNodes(saved, operator);
        }
        return get(id);
    }

    private String joinMessages(List<Map<String, Object>> blockers) {
        if (blockers == null || blockers.isEmpty()) {
            return "待结算";
        }
        List<String> messages = new ArrayList<>();
        for (Map<String, Object> b : blockers) {
            messages.add(String.valueOf(b.get("message")));
        }
        return String.join("；", messages);
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

    /** 合同信息卡视图：合同（带实时结算状态）+ 按状态统计 */
    @Transactional(readOnly = true)
    public Map<String, Object> board() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Contract> contracts = contractRepository.findAllByOrderByIdAsc();
        settlementService.decorateAll(contracts);
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
