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
import com.shipyard.dock.domain.Berth;
import com.shipyard.dock.domain.BerthMoveLog;
import com.shipyard.dock.domain.Vessel;
import com.shipyard.dock.dto.BerthMoveRequest;
import com.shipyard.dock.dto.BerthRequest;
import com.shipyard.dock.repository.BerthMoveLogRepository;
import com.shipyard.dock.repository.BerthRepository;
import com.shipyard.dock.repository.VesselRepository;

/**
 * 模块一：船台与船坞。
 *
 * <p>规则：① 船台编号唯一；② 长度 / 宽度 / 承载能力必须 &gt; 0；
 * ③ 状态按 空闲 → 占用 → 检修 → 空闲 流转；④ 已占用的船台不允许再排入新船；
 * ⑤ 船台尺度与承载能力必须满足所排船舶。</p>
 *
 * <p>并发与一致性：排船 / 出坞 / 状态流转 / 移泊都先拿船台行级悲观锁再走业务校验；
 * 移泊把「原船台腾空 + 目标船台占用」放在同一事务里原子切换，任一校验失败整体回滚，
 * 操作前的原船台占位原样保留，且每一次移泊尝试都写入 berth_move_log 留痕。</p>
 */
@Service
public class BerthService {

    private final BerthRepository berthRepository;
    private final VesselRepository vesselRepository;
    private final BerthMoveLogRepository berthMoveLogRepository;

    public BerthService(BerthRepository berthRepository, VesselRepository vesselRepository,
            BerthMoveLogRepository berthMoveLogRepository) {
        this.berthRepository = berthRepository;
        this.vesselRepository = vesselRepository;
        this.berthMoveLogRepository = berthMoveLogRepository;
    }

    public List<Berth> list() {
        return berthRepository.findAllByOrderByIdAsc();
    }

    public Berth get(Long id) {
        return berthRepository.findById(id)
                .orElseThrow(() -> new BizException("船台不存在：" + id));
    }

    @Transactional
    public Berth create(BerthRequest request) {
        if (!StringUtils.hasText(request.getBerthNo())) {
            throw new BizException("船台编号不能为空");
        }
        if (berthRepository.existsByBerthNo(request.getBerthNo())) {
            throw new BizException("船台编号已存在：" + request.getBerthNo());
        }
        if (!Dict.isValidBerthType(request.getBerthType())) {
            throw new BizException("船台类型非法（船台 / 船坞 / 舾装码头 三选一）：" + request.getBerthType());
        }
        Berth berth = new Berth();
        apply(berth, request);
        // 默认值放在 create 里，实体不带默认值
        if (berth.getStatus() == null) {
            berth.setStatus("IDLE");
        }
        if (!Dict.isValidBerthStatus(berth.getStatus())) {
            throw new BizException("船台状态非法：" + berth.getStatus());
        }
        checkScale(berth);
        berth.setCreatedAt(LocalDateTime.now());
        return berthRepository.save(berth);
    }

    @Transactional
    public Berth update(Long id, BerthRequest request) {
        Berth berth = lock(id);
        if (StringUtils.hasText(request.getBerthNo()) && !request.getBerthNo().equals(berth.getBerthNo())
                && berthRepository.existsByBerthNo(request.getBerthNo())) {
            throw new BizException("船台编号已存在：" + request.getBerthNo());
        }
        if (request.getBerthType() != null && !Dict.isValidBerthType(request.getBerthType())) {
            throw new BizException("船台类型非法（船台 / 船坞 / 舾装码头 三选一）：" + request.getBerthType());
        }
        if (request.getStatus() != null && !request.getStatus().equals(berth.getStatus())
                && !Dict.canTransferBerth(berth.getStatus(), request.getStatus())) {
            throw new BizException("船台状态不能从 " + Dict.berthStatusName(berth.getStatus())
                    + " 直接变为 " + Dict.berthStatusName(request.getStatus())
                    + "（空闲 → 占用 → 检修 → 空闲）");
        }
        applyPartial(berth, request);
        checkScale(berth);
        return berthRepository.save(berth);
    }

    /** 排船入台：船台必须空闲，且尺度与承载能力要吃得住这条船（先锁船舶再锁船台，与移泊同序防死锁） */
    @Transactional
    public Berth occupy(Long id, Long vesselId) {
        if (vesselId == null) {
            throw new BizException("请选择要排入的船舶");
        }
        Vessel vessel = vesselRepository.findByIdForUpdate(vesselId)
                .orElseThrow(() -> new BizException("船舶不存在：" + vesselId));
        Berth berth = lock(id);
        if (!"IDLE".equals(berth.getStatus())) {
            throw new BizException("船台 " + berth.getBerthNo() + " 当前为「"
                    + Dict.berthStatusName(berth.getStatus()) + "」，不允许再排入新船");
        }
        checkFit(berth, vessel);
        berth.setVesselId(vessel.getId());
        berth.setVesselName(vessel.getVesselName());
        berth.setStatus("OCCUPIED");
        berth.setOccupiedAt(LocalDateTime.now());
        return berthRepository.save(berth);
    }

    /** 出坞 / 让位：把船台腾空 */
    @Transactional
    public Berth release(Long id) {
        Berth berth = lock(id);
        if (!"OCCUPIED".equals(berth.getStatus())) {
            throw new BizException("船台 " + berth.getBerthNo() + " 当前没有占用船舶，无需出坞");
        }
        berth.setVesselId(null);
        berth.setVesselName(null);
        berth.setStatus("IDLE");
        berth.setOccupiedAt(null);
        return berthRepository.save(berth);
    }

    /** 状态流转：空闲 → 占用（走排船）/ 检修；占用 → 检修 / 空闲；检修 → 空闲 */
    @Transactional
    public Berth transfer(Long id, String status) {
        Berth berth = lock(id);
        if (!Dict.isValidBerthStatus(status)) {
            throw new BizException("船台状态非法：" + status);
        }
        if (!Dict.canTransferBerth(berth.getStatus(), status)) {
            throw new BizException("船台状态不能从 " + Dict.berthStatusName(berth.getStatus())
                    + " 变为 " + Dict.berthStatusName(status) + "（空闲 → 占用 → 检修 → 空闲）");
        }
        if ("IDLE".equals(status)) {
            berth.setVesselId(null);
            berth.setVesselName(null);
            berth.setOccupiedAt(null);
        }
        berth.setStatus(status);
        return berthRepository.save(berth);
    }

    /**
     * 移泊（原子操作）：把船从原船台 / 待排区搬到目标船台。
     *
     * <p>执行顺序：① 锁船舶行（同一艘船的并发移泊在此串行）→ ② 按 id 升序锁涉及的
     * 船台行（交叉移泊 A→B 与 B→A 不会死锁）→ ③ 校验「船仍在预期原船台、目标船台
     * 仍空闲、尺度与承载吃得住」→ ④ 同一事务内同时完成原船台腾空与目标船台占用。
     * 任一校验失败都不改动任何占位，操作前的原船台占位原样保留。</p>
     *
     * <p>重复提交 / 网络重试：船已在目标船台时按已成功幂等返回，不重复切换；
     * 船已不在预期原船台时拒绝并提示当前真实船位，后到请求不会覆盖已成功的移泊。
     * 每一次移泊尝试（无论成败）都写入 berth_move_log 留痕。</p>
     *
     * <p>返回 Map：success 是否成功、moved 本次是否真正切换、message 中文结果说明、log 留痕记录。
     * 业务性失败不抛异常——失败留痕要随事务一起落库。</p>
     */
    @Transactional
    public Map<String, Object> move(BerthMoveRequest request) {
        if (request.getVesselId() == null) {
            throw new BizException("请选择要移泊的船舶");
        }
        if (request.getToBerthId() == null) {
            throw new BizException("请选择目标船台");
        }
        if (request.getToBerthId().equals(request.getFromBerthId())) {
            throw new BizException("目标船台与原船台相同，无需移泊");
        }
        // ① 先锁船舶行：并发拖动同一艘船时，后到请求等前者提交后再校验
        Vessel vessel = vesselRepository.findByIdForUpdate(request.getVesselId())
                .orElseThrow(() -> new BizException("船舶不存在：" + request.getVesselId()));
        // ② 再按 id 升序锁原船台与目标船台：与排船 / 出坞 / 转检修互斥，交叉移泊不死锁
        List<Long> berthIds = new ArrayList<>();
        if (request.getFromBerthId() != null) {
            berthIds.add(request.getFromBerthId());
        }
        berthIds.add(request.getToBerthId());
        berthIds.sort(Long::compareTo);
        Map<Long, Berth> locked = new LinkedHashMap<>();
        for (Long berthId : berthIds) {
            Berth berth = berthRepository.findByIdForUpdate(berthId)
                    .orElseThrow(() -> new BizException("船台不存在：" + berthId));
            locked.put(berthId, berth);
        }
        Berth from = request.getFromBerthId() == null ? null : locked.get(request.getFromBerthId());
        Berth to = locked.get(request.getToBerthId());
        // 船舶当前真实船位：后到请求的提示以它为准
        Berth current = berthRepository.findFirstByVesselIdAndStatusOrderByIdAsc(vessel.getId(), "OCCUPIED")
                .orElse(null);

        // ③ 幂等：重复提交 / 网络重试时船已在目标船台 → 按已成功处理，不再切换、不覆盖
        if (current != null && current.getId().equals(to.getId())) {
            String message = vessel.getVesselName() + " 已在目标船台「" + to.getBerthNo()
                    + "」，本次为重复请求，未重复切换";
            BerthMoveLog log = saveMoveLog(vessel, from, to, "SUCCESS", message, request.getOperator());
            return moveResult(true, false, message, log);
        }

        // ④ 船舶必须仍在预期的原船台 / 待排区，否则提示当前真实船位
        if (from != null) {
            boolean stillThere = "OCCUPIED".equals(from.getStatus()) && vessel.getId().equals(from.getVesselId());
            if (!stillThere) {
                String message = "移泊未执行：" + vessel.getVesselName() + " 当前在" + locationName(current)
                        + "，不在预期船台「" + from.getBerthNo() + "」（可能刚被他人移泊或出坞），原占位未改动";
                BerthMoveLog log = saveMoveLog(vessel, from, to, "FAIL", message, request.getOperator());
                return moveResult(false, false, message, log);
            }
        } else if (current != null) {
            String message = "移泊未执行：" + vessel.getVesselName() + " 当前在" + locationName(current)
                    + "，不在待排区，不能按待排船舶排入「" + to.getBerthNo() + "」";
            BerthMoveLog log = saveMoveLog(vessel, from, to, "FAIL", message, request.getOperator());
            return moveResult(false, false, message, log);
        }

        // ⑤ 目标船台必须仍然可用（被抢占 / 转入检修都在此拦下，原船台占位保留）
        if (!"IDLE".equals(to.getStatus())) {
            String message = "移泊未执行：目标船台「" + to.getBerthNo() + "」当前为「"
                    + Dict.berthStatusName(to.getStatus()) + "」"
                    + ("OCCUPIED".equals(to.getStatus()) ? "（刚被 " + to.getVesselName() + " 占用）" : "")
                    + "，" + vessel.getVesselName() + " 仍留在" + stayPlace(from);
            BerthMoveLog log = saveMoveLog(vessel, from, to, "FAIL", message, request.getOperator());
            return moveResult(false, false, message, log);
        }

        // ⑥ 尺度与承载校验：不通过同样保留原船台占位
        String fitError = fitError(to, vessel);
        if (fitError != null) {
            String message = "移泊未执行：" + fitError + "，" + vessel.getVesselName() + " 仍留在" + stayPlace(from);
            BerthMoveLog log = saveMoveLog(vessel, from, to, "FAIL", message, request.getOperator());
            return moveResult(false, false, message, log);
        }

        // ⑦ 原子切换：原船台腾空与目标船台占用在同一事务里同时生效
        LocalDateTime now = LocalDateTime.now();
        if (from != null) {
            from.setVesselId(null);
            from.setVesselName(null);
            from.setStatus("IDLE");
            from.setOccupiedAt(null);
            berthRepository.save(from);
        }
        to.setVesselId(vessel.getId());
        to.setVesselName(vessel.getVesselName());
        to.setStatus("OCCUPIED");
        to.setOccupiedAt(now);
        berthRepository.save(to);

        String message = vessel.getVesselName() + " 已从" + stayPlace(from) + "移泊至船台「" + to.getBerthNo() + "」";
        BerthMoveLog log = saveMoveLog(vessel, from, to, "SUCCESS", message, request.getOperator());
        return moveResult(true, true, message, log);
    }

    @Transactional
    public void delete(Long id) {
        Berth berth = lock(id);
        if (berth.isOccupied()) {
            throw new BizException("船台 " + berth.getBerthNo() + " 正在占用，出坞后才能删除");
        }
        berthRepository.deleteById(id);
    }

    /** 船台平面占位图：全部船台 + 未上台的船 + 类型 / 状态元数据 + 最近移泊记录 */
    public Map<String, Object> board() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Berth> berths = berthRepository.findAllByOrderByIdAsc();
        List<Vessel> vessels = vesselRepository.findAllByOrderByIdAsc();
        List<Vessel> freeVessels = new ArrayList<>();
        for (Vessel vessel : vessels) {
            boolean onBerth = berths.stream().anyMatch(b -> vessel.getId().equals(b.getVesselId()));
            if (!onBerth) {
                freeVessels.add(vessel);
            }
        }
        data.put("berths", berths);
        data.put("vessels", vessels);
        data.put("freeVessels", freeVessels);
        data.put("types", berthRepository.statByBerthType());
        data.put("moveLogs", berthMoveLogRepository.findTop20ByOrderByIdDesc());

        List<Map<String, Object>> typeMeta = new ArrayList<>();
        for (String type : Dict.BERTH_TYPES) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", type);
            item.put("name", Dict.berthTypeName(type));
            item.put("count", berths.stream().filter(b -> type.equals(b.getBerthType())).count());
            typeMeta.add(item);
        }
        data.put("typeMeta", typeMeta);

        List<Map<String, Object>> statusMeta = new ArrayList<>();
        for (String status : Dict.BERTH_STATUSES) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", status);
            item.put("name", Dict.berthStatusName(status));
            item.put("count", berths.stream().filter(b -> status.equals(b.getStatus())).count());
            statusMeta.add(item);
        }
        data.put("statusMeta", statusMeta);
        return data;
    }

    private void apply(Berth berth, BerthRequest request) {
        berth.setBerthNo(request.getBerthNo());
        berth.setBerthName(request.getBerthName());
        berth.setBerthType(request.getBerthType());
        berth.setLengthM(request.getLengthM());
        berth.setWidthM(request.getWidthM());
        berth.setCapacityT(request.getCapacityT());
        berth.setStatus(request.getStatus());
        berth.setLocation(request.getLocation());
        berth.setNote(request.getNote());
    }

    private void applyPartial(Berth berth, BerthRequest request) {
        ifNotNull(request.getBerthNo(), berth::setBerthNo);
        ifNotNull(request.getBerthName(), berth::setBerthName);
        ifNotNull(request.getBerthType(), berth::setBerthType);
        ifNotNull(request.getLengthM(), berth::setLengthM);
        ifNotNull(request.getWidthM(), berth::setWidthM);
        ifNotNull(request.getCapacityT(), berth::setCapacityT);
        ifNotNull(request.getStatus(), berth::setStatus);
        ifNotNull(request.getLocation(), berth::setLocation);
        ifNotNull(request.getNote(), berth::setNote);
    }

    /** 长度 / 宽度 / 承载能力都必须 > 0 */
    private void checkScale(Berth berth) {
        if (berth.getLengthM() == null || berth.getLengthM().signum() <= 0) {
            throw new BizException("船台 " + berth.getBerthNo() + " 有效长度必须大于 0");
        }
        if (berth.getWidthM() == null || berth.getWidthM().signum() <= 0) {
            throw new BizException("船台 " + berth.getBerthNo() + " 有效宽度必须大于 0");
        }
        if (berth.getCapacityT() == null || berth.getCapacityT().signum() <= 0) {
            throw new BizException("船台 " + berth.getBerthNo() + " 承载能力必须大于 0");
        }
    }

    /** 船台尺度与承载能力要吃得住这条船（排船入台用，不通过直接抛异常） */
    private void checkFit(Berth berth, Vessel vessel) {
        String error = fitError(berth, vessel);
        if (error != null) {
            throw new BizException(error);
        }
    }

    /** 尺度与承载校验：通过返回 null，不通过返回中文原因（移泊用它保留原占位而不是抛异常） */
    private String fitError(Berth berth, Vessel vessel) {
        BigDecimal shipLength = vessel.getLengthM() == null ? BigDecimal.ZERO : vessel.getLengthM();
        BigDecimal shipWidth = vessel.getWidthM() == null ? BigDecimal.ZERO : vessel.getWidthM();
        BigDecimal shipWeight = vessel.getDisplacementT() == null ? BigDecimal.ZERO : vessel.getDisplacementT();
        if (berth.getLengthM() == null || berth.getLengthM().compareTo(shipLength) < 0) {
            return "船台 " + berth.getBerthNo() + " 有效长度 " + berth.getLengthM()
                    + " m 容不下船长 " + shipLength + " m 的 " + vessel.getVesselName();
        }
        if (berth.getWidthM() == null || berth.getWidthM().compareTo(shipWidth) < 0) {
            return "船台 " + berth.getBerthNo() + " 有效宽度 " + berth.getWidthM()
                    + " m 容不下型宽 " + shipWidth + " m 的 " + vessel.getVesselName();
        }
        if (berth.getCapacityT() == null || berth.getCapacityT().compareTo(shipWeight) < 0) {
            return "船台 " + berth.getBerthNo() + " 承载能力 " + berth.getCapacityT()
                    + " t 低于 " + vessel.getVesselName() + " 排水量 " + shipWeight + " t";
        }
        return null;
    }

    /** 拿船台的行级悲观锁：排船 / 出坞 / 状态流转 / 移泊互斥，校验与改写之间不会被并发插入 */
    private Berth lock(Long id) {
        return berthRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("船台不存在：" + id));
    }

    /** 船舶当前所在位置的中文说法（待排区 / 船台「X」） */
    private static String locationName(Berth berth) {
        return berth == null ? "待排区" : "船台「" + berth.getBerthNo() + "」";
    }

    /** 移泊前船舶停留位置的中文说法（原船台「X」/ 待排区） */
    private static String stayPlace(Berth from) {
        return from == null ? "待排区" : "原船台「" + from.getBerthNo() + "」";
    }

    /** 移泊留痕：原船台、目标船台、操作时间、结果与原因，只追加 */
    private BerthMoveLog saveMoveLog(Vessel vessel, Berth from, Berth to, String result,
            String reason, String operator) {
        BerthMoveLog log = new BerthMoveLog();
        log.setVesselId(vessel.getId());
        log.setVesselNo(vessel.getVesselNo());
        log.setVesselName(vessel.getVesselName());
        if (from != null) {
            log.setFromBerthId(from.getId());
            log.setFromBerthNo(from.getBerthNo());
        }
        log.setToBerthId(to.getId());
        log.setToBerthNo(to.getBerthNo());
        log.setResult(result);
        log.setReason(reason);
        log.setOperator(StringUtils.hasText(operator) ? operator : null);
        log.setCreatedAt(LocalDateTime.now());
        return berthMoveLogRepository.save(log);
    }

    private static Map<String, Object> moveResult(boolean success, boolean moved, String message, BerthMoveLog log) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("moved", moved);
        result.put("message", message);
        result.put("log", log);
        return result;
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
