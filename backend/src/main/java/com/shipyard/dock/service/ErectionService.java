package com.shipyard.dock.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.shipyard.dock.common.BizException;
import com.shipyard.dock.common.Dict;
import com.shipyard.dock.domain.Berth;
import com.shipyard.dock.domain.Block;
import com.shipyard.dock.domain.Crane;
import com.shipyard.dock.domain.Erection;
import com.shipyard.dock.domain.Lift;
import com.shipyard.dock.generation.CodeSequenceService;
import com.shipyard.dock.dto.ErectionRequest;
import com.shipyard.dock.dto.LiftCompleteRequest;
import com.shipyard.dock.dto.LiftRequest;
import com.shipyard.dock.dto.LiftStartRequest;
import com.shipyard.dock.repository.BerthRepository;
import com.shipyard.dock.repository.BlockRepository;
import com.shipyard.dock.repository.CraneRepository;
import com.shipyard.dock.repository.CraneScheduleLogRepository;
import com.shipyard.dock.repository.ErectionRepository;
import com.shipyard.dock.repository.LiftRepository;
import com.shipyard.dock.domain.CraneScheduleLog;

/**
 * 模块四：搭载与吊装。
 *
 * <p>规则：① 吊次号唯一；② 单次吊装重量必须 &gt; 0 且不得超过所用起重设备的额定起重量；
 * ③ 搭载顺序必须按分段编号顺序推进，跳序拦截；④ 同一吊次重复登记拦截；
 * ⑤ 吊次三阶段（未开始 → 进行中 → 已完成）与设备排程联动：未开始随排程改派，
 * 进行中归属冻结，已完成历史永不倒改。</p>
 */
@Service
public class ErectionService {

    /** 搭载任务号序号键 */
    private static final String ERECTION_SEQ_KEY = "ERECTION_NO";

    /** 吊次号序号键，scope 为日期 yyyyMMdd */
    private static final String LIFT_SEQ_KEY = "LIFT_NO";

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ErectionRepository erectionRepository;
    private final LiftRepository liftRepository;
    private final BlockRepository blockRepository;
    private final CraneRepository craneRepository;
    private final BerthRepository berthRepository;
    private final CraneScheduleLogRepository logRepository;
    private final ScheduleService scheduleService;

    public ErectionService(ErectionRepository erectionRepository, LiftRepository liftRepository,
            BlockRepository blockRepository, CraneRepository craneRepository, BerthRepository berthRepository,
            CraneScheduleLogRepository logRepository, ScheduleService scheduleService) {
        this.erectionRepository = erectionRepository;
        this.liftRepository = liftRepository;
        this.blockRepository = blockRepository;
        this.craneRepository = craneRepository;
        this.berthRepository = berthRepository;
        this.logRepository = logRepository;
        this.scheduleService = scheduleService;
    }

    public List<Erection> list() {
        return erectionRepository.findAllByOrderByIdAsc();
    }

    public Erection get(Long id) {
        return erectionRepository.findById(id)
                .orElseThrow(() -> new BizException("搭载任务不存在：" + id));
    }

    public List<Lift> lifts() {
        return liftRepository.findAllForBoard();
    }

    @Transactional
    public Erection create(ErectionRequest request) {
        if (request.getVesselId() == null) {
            throw new BizException("搭载任务必须指定船舶");
        }
        if (request.getBlockId() == null) {
            throw new BizException("搭载任务必须指定分段");
        }
        Block block = blockRepository.findById(request.getBlockId())
                .orElseThrow(() -> new BizException("分段不存在：" + request.getBlockId()));
        if (!request.getVesselId().equals(block.getVesselId())) {
            throw new BizException("分段 " + block.getBlockCode() + " 不属于该船，不能编入这条船的搭载计划");
        }
        if (!Boolean.TRUE.equals(block.getPainted())) {
            throw new BizException("分段 " + block.getBlockCode() + " 尚未完成涂装，不允许排搭载任务");
        }
        Erection erection = new Erection();
        erection.setVesselId(block.getVesselId());
        erection.setVesselNo(block.getVesselNo());
        erection.setVesselName(block.getVesselName());
        erection.setBlockId(block.getId());
        erection.setBlockCode(block.getBlockCode());
        erection.setSegSeq(block.getSegSeq());
        erection.setBerthId(request.getBerthId());
        erection.setPlanDate(request.getPlanDate());
        erection.setNote(request.getNote());
        erection.setStatus(request.getStatus() == null ? "PLANNED" : request.getStatus());
        if (!Dict.isValidErectionStatus(erection.getStatus())) {
            throw new BizException("搭载状态非法：" + erection.getStatus());
        }
        // 搭载顺序必须按分段编号顺序推进，跳序拦截
        checkSeq(erection);
        bindBerth(erection);
        erection.setErectionNo(nextErectionNo(erection.getVesselNo()));
        erection.setCreatedAt(LocalDateTime.now());
        erection.setReviewPending(false);
        erection.setScheduledAt(LocalDateTime.now());

        // 排程设备：指派即过能力 / 区域 / 档期校验，检修设备禁止承接
        if (request.getCraneId() != null) {
            Crane crane = craneRepository.findByIdForUpdate(request.getCraneId())
                    .orElseThrow(() -> new BizException("起重设备不存在：" + request.getCraneId()));
            scheduleService.checkEligible(crane, erection, null);
            assignCrane(erection, crane);
        }
        Erection saved = erectionRepository.save(erection);
        if (saved.getCraneId() != null) {
            scheduleLog(saved.getCraneId(), "SCHEDULE", saved, null,
                    "排程指派 " + saved.getCraneNo() + "，计划 " + saved.getPlanDate(), null);
        }
        return saved;
    }

    @Transactional
    public Erection update(Long id, ErectionRequest request) {
        Erection erection = get(id);
        if (request.getStatus() != null && !request.getStatus().equals(erection.getStatus())
                && !Dict.canTransferErection(erection.getStatus(), request.getStatus())) {
            throw new BizException("搭载状态不能从 " + Dict.erectionStatusName(erection.getStatus())
                    + " 变为 " + Dict.erectionStatusName(request.getStatus()));
        }
        applyPartial(erection, request);
        if (request.getBerthId() != null) {
            bindBerth(erection);
        }
        return erectionRepository.save(erection);
    }

    /** 推进搭载任务：已排程 → 待吊装 → 已完成 */
    @Transactional
    public Erection advance(Long id) {
        Erection erection = get(id);
        if ("DONE".equals(erection.getStatus())) {
            throw new BizException("搭载任务 " + erection.getErectionNo() + " 已完成，无需再推进");
        }
        String next = "PLANNED".equals(erection.getStatus()) ? "READY" : "DONE";
        if ("DONE".equals(next) && liftRepository.findByErectionIdOrderByLiftTimeAsc(id).stream()
                .noneMatch(Lift::isDone)) {
            throw new BizException("搭载任务 " + erection.getErectionNo() + " 还没有已完成吊次，不能标记完成");
        }
        erection.setStatus(next);
        return erectionRepository.save(erection);
    }

    @Transactional
    public void delete(Long id) {
        Erection erection = get(id);
        if (!liftRepository.findByErectionIdOrderByLiftTimeAsc(id).isEmpty()) {
            throw new BizException("搭载任务 " + erection.getErectionNo() + " 已有吊次记录，不能删除");
        }
        erectionRepository.deleteById(id);
    }

    // ============================= 吊次三阶段 =============================

    /**
     * 登记吊次：默认登记为「未开始」排程吊次（随任务排程，可被检修改派）；
     * 传 status=DONE 则为一次性补登历史吊次（沿用原有重量 / 风力 / 重复登记校验）。
     */
    @Transactional
    public Lift registerLift(Long erectionId, LiftRequest request) {
        Erection erection = lockErection(erectionId);
        boolean historical = "DONE".equals(request.getStatus());
        if (request.getStatus() != null && !"SCHEDULED".equals(request.getStatus()) && !historical) {
            throw new BizException("吊次登记状态只能是未开始（SCHEDULED）或已完成（DONE，补登历史）");
        }
        Crane crane = resolveCraneForRegistration(erection, request.getCraneId());

        // 重量：补登历史必须给正数；未开始排程可先带预估值（取分段重量兜底）
        BigDecimal weight = request.getLiftWeightT();
        if (historical) {
            requireValidWeight(weight);
        }
        if (weight == null || weight.signum() <= 0) {
            weight = blockRepository.findById(erection.getBlockId())
                    .map(Block::getWeightT).orElse(null);
        }
        if (weight != null && weight.signum() > 0) {
            requireWithinCapacity(weight, crane);
        }
        if (historical && request.getWindLevel() != null && request.getWindLevel() > Dict.MAX_WIND_LEVEL) {
            throw new BizException("风力 " + request.getWindLevel() + " 级超过 " + Dict.MAX_WIND_LEVEL
                    + " 级，禁止吊装作业");
        }
        // 同一任务只允许一个有效吊次归属：已存在未开始 / 进行中吊次时，禁止再登记
        // （不能只按设备判重 —— 检修改派后设备编号会变，按设备判会让同一任务挂出两个未完成吊次）
        List<Lift> existing = liftRepository.findByErectionIdOrderByLiftTimeAsc(erectionId);
        boolean hasOpenLift = existing.stream().anyMatch(l -> !l.isDone());
        if (hasOpenLift) {
            throw new BizException("搭载任务 " + erection.getErectionNo() + " 已有未开始 / 进行中吊次，"
                    + "同一任务同一时间只允许一个有效吊次，不能重复登记");
        }
        // 补登历史吊次：同设备 + 同分段的历史吊次也不允许重复
        if (historical) {
            boolean duplicatedHistory = existing.stream().anyMatch(l -> l.isDone()
                    && crane.getId().equals(l.getCraneId())
                    && erection.getBlockId().equals(l.getBlockId()));
            if (duplicatedHistory) {
                throw new BizException("吊次重复登记：搭载任务 " + erection.getErectionNo() + " 的分段 "
                        + erection.getBlockCode() + " 已由 " + crane.getCraneNo() + " 登记过历史吊次");
            }
        }

        String liftNo = StringUtils.hasText(request.getLiftNo()) ? request.getLiftNo() : nextLiftNo(LocalDate.now());
        if (liftRepository.existsByLiftNo(liftNo)) {
            throw new BizException("吊次号已存在：" + liftNo);
        }

        Lift lift = new Lift();
        lift.setLiftNo(liftNo);
        fillFromErection(lift, erection);
        lift.setCraneId(crane.getId());
        lift.setCraneNo(crane.getCraneNo());
        lift.setCraneName(crane.getCraneName());
        lift.setRatedLoadT(crane.getRatedLoadT());
        lift.setLiftWeightT(weight);
        lift.setOperator(request.getOperator());
        lift.setWindLevel(request.getWindLevel());
        lift.setRemark(request.getRemark());
        lift.setCreatedAt(LocalDateTime.now());
        if (historical) {
            lift.setStatus("DONE");
            lift.setLiftTime(request.getLiftTime() == null ? LocalDateTime.now() : request.getLiftTime());
        } else {
            lift.setStatus("SCHEDULED");
            lift.setLiftTime(request.getLiftTime() == null
                    ? (erection.getPlanDate() == null ? null : erection.getPlanDate().atStartOfDay())
                    : request.getLiftTime());
        }
        Lift saved = liftRepository.save(lift);

        if (historical) {
            // 补登历史吊次：沿用原规则，任务完成、分段推进到搭载阶段；历史归属落定不可改
            erection.setStatus("DONE");
            erectionRepository.save(erection);
            blockRepository.findById(erection.getBlockId()).ifPresent(block -> {
                if ("PAINT".equals(block.getStatus())) {
                    block.setStatus("ERECTION");
                    blockRepository.save(block);
                }
            });
        } else {
            scheduleLog(crane.getId(), "SCHEDULE", erection, saved,
                    "吊次排程，计划设备 " + crane.getCraneNo(), request.getOperator());
        }
        return saved;
    }

    /** 吊次开始：只允许由任务排程设备执行，禁止指派别的设备（不得静默换机）。 */
    @Transactional
    public Lift startLift(Long liftId, LiftStartRequest request) {
        Lift lift = liftRepository.findByIdForUpdate(liftId)
                .orElseThrow(() -> new BizException("吊次不存在：" + liftId));
        if (!"SCHEDULED".equals(lift.getStatus())) {
            throw new BizException("吊次 " + lift.getLiftNo() + " 当前为「"
                    + Dict.liftStatusName(lift.getStatus()) + "」，只有未开始吊次能开始");
        }
        // 锁任务行 + 乐观版本：与检修改派互斥，保证开始瞬间归属唯一
        Erection erection = lockErection(lift.getErectionId());
        if (erection.getCraneId() == null || !erection.getCraneId().equals(lift.getCraneId())) {
            throw new BizException("吊次 " + lift.getLiftNo() + " 的设备与任务最新排程不一致，"
                    + "可能刚被检修联动改派，请刷新后按新排程重新指派，不能在旧设备上直接起吊");
        }
        Crane crane = craneRepository.findByIdForUpdate(lift.getCraneId())
                .orElseThrow(() -> new BizException("起重设备不存在：" + lift.getCraneId()));
        if (!crane.isAvailable()) {
            throw new BizException("设备 " + crane.getCraneNo() + " 当前为「"
                    + Dict.craneStatusName(crane.getStatus()) + "」，禁止起吊");
        }
        if (request != null && request.getWindLevel() != null && request.getWindLevel() > Dict.MAX_WIND_LEVEL) {
            throw new BizException("风力 " + request.getWindLevel() + " 级超过 " + Dict.MAX_WIND_LEVEL
                    + " 级，禁止吊装作业");
        }
        BigDecimal weight = request == null ? null : request.getLiftWeightT();
        if (weight != null && weight.signum() > 0) {
            requireWithinCapacity(weight, crane);
            lift.setLiftWeightT(weight);
        }
        lift.setStatus("IN_PROGRESS");
        lift.setStartedAt(LocalDateTime.now());
        lift.setCurrentStage(StringUtils.hasText(request == null ? null : request.getStage())
                ? request.getStage() : "起钩");
        if (request != null) {
            if (StringUtils.hasText(request.getOperator())) {
                lift.setOperator(request.getOperator());
            }
            if (request.getWindLevel() != null) {
                lift.setWindLevel(request.getWindLevel());
            }
        }
        Lift saved = liftRepository.save(lift);
        scheduleLog(crane.getId(), "LIFT_START", erection, saved,
                "吊次开始，当前阶段：" + saved.getCurrentStage(), saved.getOperator());
        return saved;
    }

    /** 吊次完成：归属设备不变；实际重量 / 风力再次校验，完成后成为不可倒改的历史吊次。 */
    @Transactional
    public Lift completeLift(Long liftId, LiftCompleteRequest request) {
        Lift lift = liftRepository.findByIdForUpdate(liftId)
                .orElseThrow(() -> new BizException("吊次不存在：" + liftId));
        if (!"IN_PROGRESS".equals(lift.getStatus())) {
            throw new BizException("吊次 " + lift.getLiftNo() + " 当前为「"
                    + Dict.liftStatusName(lift.getStatus()) + "」，只有进行中吊次能完成");
        }
        Crane crane = craneRepository.findById(lift.getCraneId())
                .orElseThrow(() -> new BizException("起重设备不存在：" + lift.getCraneId()));
        BigDecimal weight = request == null ? lift.getLiftWeightT() : request.getLiftWeightT();
        requireValidWeight(weight);
        requireWithinCapacity(weight, crane);
        if (request != null && request.getWindLevel() != null && request.getWindLevel() > Dict.MAX_WIND_LEVEL) {
            throw new BizException("风力 " + request.getWindLevel() + " 级超过 " + Dict.MAX_WIND_LEVEL
                    + " 级，禁止吊装作业");
        }
        lift.setLiftWeightT(weight);
        if (request != null) {
            if (request.getWindLevel() != null) {
                lift.setWindLevel(request.getWindLevel());
            }
            if (StringUtils.hasText(request.getRemark())) {
                lift.setRemark(request.getRemark());
            }
        }
        lift.setStatus("DONE");
        lift.setCurrentStage(null);
        lift.setSafetyHandling(null);
        lift.setLiftTime(LocalDateTime.now());
        Lift saved = liftRepository.save(lift);

        Erection erection = erectionRepository.findById(lift.getErectionId()).orElse(null);
        if (erection != null) {
            erection.setStatus("DONE");
            erectionRepository.save(erection);
            blockRepository.findById(erection.getBlockId()).ifPresent(block -> {
                if ("PAINT".equals(block.getStatus())) {
                    block.setStatus("ERECTION");
                    blockRepository.save(block);
                }
            });
        }
        return saved;
    }

    // ============================= 视图聚合 =============================

    /** 吊装时间戳序列视图：搭载任务 + 吊次 + 设备 + 分段 + 待复核任务 */
    public Map<String, Object> board() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Erection> erections = erectionRepository.findAllByOrderByIdAsc();
        List<Lift> lifts = liftRepository.findAllForBoard();
        data.put("erections", erections);
        data.put("lifts", lifts);
        data.put("cranes", craneRepository.findAllByOrderByIdAsc());
        data.put("blocks", blockRepository.findAllByOrderByIdAsc());
        data.put("berths", berthRepository.findAllByOrderByIdAsc());
        data.put("craneStats", liftRepository.statByCraneType());
        data.put("reviewPending", erections.stream().filter(Erection::isReviewPending).toList());
        data.put("maxWindLevel", Dict.MAX_WIND_LEVEL);

        List<Map<String, Object>> statusMeta = new ArrayList<>();
        for (String status : Dict.ERECTION_STATUSES) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", status);
            item.put("name", Dict.erectionStatusName(status));
            item.put("count", erections.stream().filter(e -> status.equals(e.getStatus())).count());
            statusMeta.add(item);
        }
        data.put("statusMeta", statusMeta);
        return data;
    }

    /** 搭载任务详情：任务 + 各阶段吊次 + 分段 + 船台 + 排程操作日志 */
    public Map<String, Object> detail(Long id) {
        Erection erection = get(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("erection", erection);
        data.put("lifts", liftRepository.findByErectionIdOrderByLiftTimeAsc(id));
        data.put("scheduleLogs", logRepository.findByErectionIdOrderByIdDesc(id));
        blockRepository.findById(erection.getBlockId()).ifPresent(block -> data.put("block", block));
        if (erection.getCraneId() != null) {
            craneRepository.findById(erection.getCraneId()).ifPresent(crane -> data.put("crane", crane));
        }
        if (erection.getOriginalCraneId() != null) {
            craneRepository.findById(erection.getOriginalCraneId())
                    .ifPresent(crane -> data.put("originalCrane", crane));
        }
        if (erection.getBerthId() != null) {
            berthRepository.findById(erection.getBerthId()).ifPresent(berth -> data.put("berth", berth));
        }
        return data;
    }

    // ============================= 私有方法 =============================

    /** 登记吊次时选设备：显式指定优先；否则用任务排程设备；两者都没有则报错。 */
    private Crane resolveCraneForRegistration(Erection erection, Long requestedCraneId) {
        Long craneId = requestedCraneId != null ? requestedCraneId : erection.getCraneId();
        if (craneId == null) {
            throw new BizException("请选择本次吊装使用的起重设备（任务尚未排程设备）");
        }
        Crane crane = craneRepository.findByIdForUpdate(craneId)
                .orElseThrow(() -> new BizException("起重设备不存在：" + craneId));
        if (erection.isReviewPending()) {
            throw new BizException("任务 " + erection.getErectionNo()
                    + " 的排程正待复核（原设备检修），请先完成恢复 / 保留替代的复核决定，再登记吊次");
        }
        if (!crane.isAvailable()) {
            throw new BizException("设备 " + crane.getCraneNo() + " 当前为「"
                    + Dict.craneStatusName(crane.getStatus()) + "」，不能接吊次");
        }
        // 显式选了与排程不同的设备登记未开始吊次也不行：同一任务只允许一个有效设备归属
        if (erection.getCraneId() != null && !erection.getCraneId().equals(craneId)) {
            throw new BizException("任务 " + erection.getErectionNo() + " 已排程 " + erection.getCraneNo()
                    + "，不能直接给其它设备登记吊次；如需更换请走改派流程");
        }
        return crane;
    }

    private void requireValidWeight(BigDecimal weight) {
        if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("单次吊装重量必须大于 0");
        }
        if (weight.compareTo(Dict.MAX_LIFT_WEIGHT) > 0) {
            throw new BizException("吊装重量 " + weight + " t 超过单吊次上限 "
                    + Dict.MAX_LIFT_WEIGHT + " t");
        }
    }

    private void requireWithinCapacity(BigDecimal weight, Crane crane) {
        if (crane.getRatedLoadT() != null && weight.compareTo(crane.getRatedLoadT()) > 0) {
            throw new BizException("吊装重量 " + weight + " t 超过 "
                    + crane.getCraneName() + " 额定起重量 " + crane.getRatedLoadT() + " t，禁止起吊");
        }
    }

    private void fillFromErection(Lift lift, Erection erection) {
        lift.setErectionId(erection.getId());
        lift.setErectionNo(erection.getErectionNo());
        lift.setBlockId(erection.getBlockId());
        lift.setBlockCode(erection.getBlockCode());
        lift.setSegSeq(erection.getSegSeq());
    }

    private void assignCrane(Erection erection, Crane crane) {
        erection.setCraneId(crane.getId());
        erection.setCraneNo(crane.getCraneNo());
        erection.setCraneName(crane.getCraneName());
    }

    /** 行锁 + 乐观锁并发兜底：版本冲突转成业务提示，不留半完成状态。 */
    private Erection lockErection(Long id) {
        try {
            return erectionRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new BizException("搭载任务不存在：" + id));
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BizException("任务排程刚被其他操作更新（检修 / 改派 / 吊次开始并发），请刷新后重试");
        }
    }

    private void scheduleLog(Long craneId, String action, Erection erection, Lift lift, String detail,
            String operator) {
        Crane crane = craneRepository.findById(craneId).orElse(null);
        CraneScheduleLog l = new CraneScheduleLog();
        l.setActionType(action);
        l.setCraneId(craneId);
        if (crane != null) {
            l.setCraneNo(crane.getCraneNo());
            l.setCraneName(crane.getCraneName());
        }
        if (erection != null) {
            l.setErectionId(erection.getId());
            l.setErectionNo(erection.getErectionNo());
            l.setBlockCode(erection.getBlockCode());
        }
        if (lift != null) {
            l.setLiftId(lift.getId());
            l.setLiftNo(lift.getLiftNo());
            if (l.getBlockCode() == null) {
                l.setBlockCode(lift.getBlockCode());
            }
        }
        l.setDetail(detail);
        l.setOperator(operator);
        l.setCreatedAt(LocalDateTime.now());
        logRepository.save(l);
    }

    /** 搭载任务号：ERE-船号-序号 */
    private String nextErectionNo(String vesselNo) {
        String scope = StringUtils.hasText(vesselNo) ? vesselNo : "COMMON";
        long seq = CodeSequenceService.next(ERECTION_SEQ_KEY, scope);
        return "ERE-" + scope + "-" + String.format("%03d", seq);
    }

    /** 吊次号：LF-yyyyMMdd-序号 */
    private String nextLiftNo(LocalDate date) {
        String scope = date.format(DAY_FMT);
        long seq = CodeSequenceService.next(LIFT_SEQ_KEY, scope);
        return "LF-" + scope + "-" + String.format("%03d", seq);
    }

    /** 搭载顺序必须按分段编号顺序推进：新任务的顺序号 = 该船已排最大顺序号 + 1 */
    private void checkSeq(Erection erection) {
        Optional<Erection> last = erectionRepository.findFirstByVesselIdOrderBySegSeqDesc(erection.getVesselId());
        int expected = last.map(Erection::getSegSeq).orElse(0) + 1;
        if (erection.getSegSeq() == null || erection.getSegSeq() != expected) {
            throw new BizException("搭载顺序跳序：船号 " + erection.getVesselNo() + " 下一段应排顺序号 "
                    + expected + "，当前提交的是 " + erection.getSegSeq()
                    + "（必须按分段编号顺序推进）");
        }
    }

    /** 搭载任务只能挂在该船占用的船台上 */
    private void bindBerth(Erection erection) {
        if (erection.getBerthId() == null) {
            return;
        }
        Berth berth = berthRepository.findById(erection.getBerthId())
                .orElseThrow(() -> new BizException("船台不存在：" + erection.getBerthId()));
        if (!erection.getVesselId().equals(berth.getVesselId())) {
            throw new BizException("船台 " + berth.getBerthNo() + " 上没有船号 " + erection.getVesselNo()
                    + " 的船，不能把它的分段排到这个船台上");
        }
        erection.setBerthNo(berth.getBerthNo());
    }

    private void applyPartial(Erection erection, ErectionRequest request) {
        ifNotNull(request.getSegSeq(), erection::setSegSeq);
        ifNotNull(request.getBerthId(), erection::setBerthId);
        ifNotNull(request.getPlanDate(), erection::setPlanDate);
        ifNotNull(request.getStatus(), erection::setStatus);
        ifNotNull(request.getNote(), erection::setNote);
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
