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
 * <p>规则：① 合同编号唯一；② 合同金额必须 &gt; 0；③ 状态按 草拟 → 已签 → 履行中 → 已结算
 * 逐级流转；④ 结算前必须引用至少一次报验通过记录。</p>
 */
@Service
public class ContractService {

    private final ContractRepository contractRepository;
    private final VesselRepository vesselRepository;
    private final SurveyRepository surveyRepository;

    public ContractService(ContractRepository contractRepository, VesselRepository vesselRepository,
            SurveyRepository surveyRepository) {
        this.contractRepository = contractRepository;
        this.vesselRepository = vesselRepository;
        this.surveyRepository = surveyRepository;
    }

    public List<Contract> list() {
        return contractRepository.findAllByOrderByIdAsc();
    }

    public Contract get(Long id) {
        return contractRepository.findById(id)
                .orElseThrow(() -> new BizException("合同不存在：" + id));
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
        if ("SETTLED".equals(contract.getStatus())) {
            checkSettle(contract);
        }
        contract.setCreatedAt(LocalDateTime.now());
        return contractRepository.save(contract);
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
        applyPartial(contract, request);
        if (request.getVesselId() != null) {
            bindVessel(contract);
        }
        if (contract.getAmount() == null || contract.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("合同金额必须大于 0");
        }
        if ("SETTLED".equals(contract.getStatus()) && contract.getSettledAt() == null) {
            checkSettle(contract);
        }
        return contractRepository.save(contract);
    }

    /** 逐级推进合同状态；推进到已结算时要校验报验通过记录 */
    @Transactional
    public Contract advance(Long id) {
        Contract contract = get(id);
        int index = Dict.CONTRACT_FLOW.indexOf(contract.getStatus());
        if (index < 0 || index + 1 >= Dict.CONTRACT_FLOW.size()) {
            throw new BizException("合同 " + contract.getContractNo() + " 已处于终态「"
                    + Dict.contractStatusName(contract.getStatus()) + "」，无需再推进");
        }
        String next = Dict.CONTRACT_FLOW.get(index + 1);
        contract.setStatus(next);
        if ("SETTLED".equals(next)) {
            checkSettle(contract);
        }
        return contractRepository.save(contract);
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

    /** 结算前置校验：必须引用至少一次报验通过记录 */
    private void checkSettle(Contract contract) {
        if (contract.getVesselId() == null) {
            throw new BizException("合同未绑定船舶，不能结算");
        }
        List<Survey> passed = surveyRepository.findPassedOfVessel(contract.getVesselId());
        if (passed.isEmpty()) {
            Vessel vessel = vesselRepository.findById(contract.getVesselId()).orElse(null);
            throw new BizException("合同 " + contract.getContractNo() + " 结算失败："
                    + (vessel == null ? "该船" : vessel.getVesselName())
                    + " 还没有任何一条报验通过记录，结算前必须引用至少一次报验通过");
        }
        contract.setSettledAt(LocalDateTime.now());
        contract.setSettleRef(passed.get(0).getSurveyNo());
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
