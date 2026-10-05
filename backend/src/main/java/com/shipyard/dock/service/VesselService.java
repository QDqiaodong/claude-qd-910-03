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
import com.shipyard.dock.domain.Block;
import com.shipyard.dock.domain.Contract;
import com.shipyard.dock.domain.Owner;
import com.shipyard.dock.domain.Vessel;
import com.shipyard.dock.dto.VesselRequest;
import com.shipyard.dock.repository.BerthMoveLogRepository;
import com.shipyard.dock.repository.BerthRepository;
import com.shipyard.dock.repository.BlockRepository;
import com.shipyard.dock.repository.ContractRepository;
import com.shipyard.dock.repository.OwnerRepository;
import com.shipyard.dock.repository.VesselRepository;

/**
 * 模块二（上）：船舶。
 *
 * <p>规则：① 本厂船号与船检登记号都唯一；② 船长必须 &gt; 0；③ 船必须归属名录内的船东。</p>
 */
@Service
public class VesselService {

    private final VesselRepository vesselRepository;
    private final OwnerRepository ownerRepository;
    private final ContractRepository contractRepository;
    private final BlockRepository blockRepository;
    private final BerthRepository berthRepository;
    private final BerthMoveLogRepository berthMoveLogRepository;
    private final ContractPaymentService paymentService;

    public VesselService(VesselRepository vesselRepository, OwnerRepository ownerRepository,
            ContractRepository contractRepository, BlockRepository blockRepository,
            BerthRepository berthRepository, BerthMoveLogRepository berthMoveLogRepository,
            ContractPaymentService paymentService) {
        this.vesselRepository = vesselRepository;
        this.ownerRepository = ownerRepository;
        this.contractRepository = contractRepository;
        this.blockRepository = blockRepository;
        this.berthRepository = berthRepository;
        this.berthMoveLogRepository = berthMoveLogRepository;
        this.paymentService = paymentService;
    }

    public List<Vessel> list() {
        return vesselRepository.findAllByOrderByIdAsc();
    }

    public Vessel get(Long id) {
        return vesselRepository.findById(id)
                .orElseThrow(() -> new BizException("船舶不存在：" + id));
    }

    @Transactional
    public Vessel create(VesselRequest request) {
        if (!StringUtils.hasText(request.getVesselNo())) {
            throw new BizException("本厂船号不能为空");
        }
        if (vesselRepository.existsByVesselNo(request.getVesselNo())) {
            throw new BizException("本厂船号已存在：" + request.getVesselNo());
        }
        if (!StringUtils.hasText(request.getRegNo())) {
            throw new BizException("船检登记号不能为空");
        }
        if (vesselRepository.existsByRegNo(request.getRegNo())) {
            throw new BizException("船检登记号已存在：" + request.getRegNo());
        }
        if (!Dict.isValidVesselType(request.getVesselType())) {
            throw new BizException("船舶类型非法（散货船 / 油轮 / 集装箱船 / 客滚船 / 工程船 五选一）："
                    + request.getVesselType());
        }
        Vessel vessel = new Vessel();
        apply(vessel, request);
        bindOwner(vessel);
        if (vessel.getStatus() == null) {
            vessel.setStatus("IN_YARD");
        }
        if (!Dict.isValidVesselStatus(vessel.getStatus())) {
            throw new BizException("船舶状态非法：" + vessel.getStatus());
        }
        checkScale(vessel);
        vessel.setCreatedAt(LocalDateTime.now());
        return vesselRepository.save(vessel);
    }

    @Transactional
    public Vessel update(Long id, VesselRequest request) {
        Vessel vessel = get(id);
        if (StringUtils.hasText(request.getVesselNo()) && !request.getVesselNo().equals(vessel.getVesselNo())
                && vesselRepository.existsByVesselNo(request.getVesselNo())) {
            throw new BizException("本厂船号已存在：" + request.getVesselNo());
        }
        if (StringUtils.hasText(request.getRegNo()) && !request.getRegNo().equals(vessel.getRegNo())
                && vesselRepository.existsByRegNo(request.getRegNo())) {
            throw new BizException("船检登记号已存在：" + request.getRegNo());
        }
        if (request.getVesselType() != null && !Dict.isValidVesselType(request.getVesselType())) {
            throw new BizException("船舶类型非法（散货船 / 油轮 / 集装箱船 / 客滚船 / 工程船 五选一）："
                    + request.getVesselType());
        }
        if (request.getStatus() != null && !Dict.isValidVesselStatus(request.getStatus())) {
            throw new BizException("船舶状态非法：" + request.getStatus());
        }
        String oldStatus = vessel.getStatus();
        applyPartial(vessel, request);
        if (request.getOwnerId() != null) {
            bindOwner(vessel);
        }
        checkScale(vessel);
        Vessel saved = vesselRepository.save(vessel);
        // 船舶状态变化（在厂 / 已下水 / 已交付）：触发该船履行中合同的付款节点结算状态重算
        if (request.getStatus() != null && !request.getStatus().equals(oldStatus)) {
            paymentService.recalcForVessel(id, "船舶状态由「" + Dict.vesselStatusName(oldStatus)
                    + "」变更为「" + Dict.vesselStatusName(saved.getStatus()) + "」");
        }
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        Vessel vessel = get(id);
        List<Block> blocks = blockRepository.findByVesselIdOrderBySegSeqAsc(id);
        List<Contract> contracts = contractRepository.findByVesselIdOrderByIdAsc(id);
        if (!blocks.isEmpty() || !contracts.isEmpty()) {
            throw new BizException("船舶 " + vessel.getVesselNo() + " 还有 " + blocks.size() + " 个分段、"
                    + contracts.size() + " 份合同引用，不能删除");
        }
        vesselRepository.deleteById(id);
    }

    /** 时间轴视图：船舶 + 船东 + 合同（带付款结算摘要） + 分段进度 */
    public Map<String, Object> board() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Vessel> vessels = vesselRepository.findAllByOrderByIdAsc();
        data.put("vessels", vessels);
        data.put("owners", ownerRepository.findAllByOrderByIdAsc());
        List<Contract> contracts = contractRepository.findAllByOrderByIdAsc();
        paymentService.attachSummary(contracts);
        data.put("contracts", contracts);
        data.put("types", vesselRepository.statByVesselType());
        List<Map<String, Object>> typeMeta = new ArrayList<>();
        for (String type : Dict.VESSEL_TYPES) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", type);
            item.put("name", Dict.vesselTypeName(type));
            item.put("count", vessels.stream().filter(v -> type.equals(v.getVesselType())).count());
            typeMeta.add(item);
        }
        data.put("typeMeta", typeMeta);
        return data;
    }

    /** 单船台账：船 + 当前船台 + 合同（带付款结算摘要） + 分段 + 移泊记录（与船台总览同源，移泊后三处一致） */
    public Map<String, Object> detail(Long id) {
        Vessel vessel = get(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vessel", vessel);
        data.put("berth", berthRepository.findFirstByVesselIdAndStatusOrderByIdAsc(id, "OCCUPIED").orElse(null));
        List<Contract> contracts = contractRepository.findByVesselIdOrderByIdAsc(id);
        paymentService.attachSummary(contracts);
        data.put("contracts", contracts);
        data.put("blocks", blockRepository.findByVesselIdOrderBySegSeqAsc(id));
        data.put("moveLogs", berthMoveLogRepository.findByVesselIdOrderByIdDesc(id));
        return data;
    }

    private void apply(Vessel vessel, VesselRequest request) {
        vessel.setVesselNo(request.getVesselNo());
        vessel.setRegNo(request.getRegNo());
        vessel.setVesselName(request.getVesselName());
        vessel.setVesselType(request.getVesselType());
        vessel.setOwnerId(request.getOwnerId());
        vessel.setLengthM(request.getLengthM());
        vessel.setWidthM(request.getWidthM());
        vessel.setDepthM(request.getDepthM());
        vessel.setGrossTonnage(request.getGrossTonnage());
        vessel.setDisplacementT(request.getDisplacementT());
        vessel.setBuildYear(request.getBuildYear());
        vessel.setStatus(request.getStatus());
        vessel.setNote(request.getNote());
    }

    private void applyPartial(Vessel vessel, VesselRequest request) {
        ifNotNull(request.getVesselNo(), vessel::setVesselNo);
        ifNotNull(request.getRegNo(), vessel::setRegNo);
        ifNotNull(request.getVesselName(), vessel::setVesselName);
        ifNotNull(request.getVesselType(), vessel::setVesselType);
        ifNotNull(request.getOwnerId(), vessel::setOwnerId);
        ifNotNull(request.getLengthM(), vessel::setLengthM);
        ifNotNull(request.getWidthM(), vessel::setWidthM);
        ifNotNull(request.getDepthM(), vessel::setDepthM);
        ifNotNull(request.getGrossTonnage(), vessel::setGrossTonnage);
        ifNotNull(request.getDisplacementT(), vessel::setDisplacementT);
        ifNotNull(request.getBuildYear(), vessel::setBuildYear);
        ifNotNull(request.getStatus(), vessel::setStatus);
        ifNotNull(request.getNote(), vessel::setNote);
    }

    /** 船必须归属名录内的船东 */
    private void bindOwner(Vessel vessel) {
        if (vessel.getOwnerId() == null) {
            throw new BizException("船舶必须归属一位船东");
        }
        Owner owner = ownerRepository.findById(vessel.getOwnerId())
                .orElseThrow(() -> new BizException("船东不存在，请先在船东名录建档：" + vessel.getOwnerId()));
        vessel.setOwnerName(owner.getOwnerName());
    }

    private void checkScale(Vessel vessel) {
        if (vessel.getLengthM() == null || vessel.getLengthM().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("船长必须大于 0");
        }
        if (vessel.getWidthM() != null && vessel.getWidthM().signum() < 0) {
            throw new BizException("型宽不能为负数");
        }
        if (vessel.getDisplacementT() != null && vessel.getDisplacementT().signum() < 0) {
            throw new BizException("排水量不能为负数");
        }
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
