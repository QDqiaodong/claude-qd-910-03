package com.shipyard.dock.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.shipyard.dock.common.BizException;
import com.shipyard.dock.common.Dict;
import com.shipyard.dock.domain.Block;
import com.shipyard.dock.domain.Berth;
import com.shipyard.dock.domain.Crane;
import com.shipyard.dock.domain.CraneScheduleLog;
import com.shipyard.dock.domain.Erection;
import com.shipyard.dock.domain.Lift;
import com.shipyard.dock.dto.AlternativeCraneDTO;
import com.shipyard.dock.dto.CraneUnavailableRequest;
import com.shipyard.dock.dto.ReassignRequest;
import com.shipyard.dock.dto.ScheduleReviewRequest;
import com.shipyard.dock.repository.BerthRepository;
import com.shipyard.dock.repository.BlockRepository;
import com.shipyard.dock.repository.CraneRepository;
import com.shipyard.dock.repository.CraneScheduleLogRepository;
import com.shipyard.dock.repository.ErectionRepository;
import com.shipyard.dock.repository.LiftRepository;

/**
 * 起重设备检修状态与吊装排程联动。
 *
 * <p>核心不变量：</p>
 * <ol>
 *   <li>检修 / 停用设备立即汇总「未开始任务、进行中吊次、历史吊次」，并禁止再接新吊次；</li>
 *   <li>未开始任务：管理员二选一 —— 自动改派到能力 / 作业区域 / 档期都匹配的替代设备，
 *       或保留原排程置为待复核；</li>
 *   <li>进行中吊次：绝不静默换机，保留原设备、当前阶段，只追加安全处置记录；</li>
 *   <li>历史吊次（DONE）：永远归属实际执行设备，检修与恢复都不得倒改；</li>
 *   <li>恢复时只允许对「未开始且仍待复核」的任务逐任务选择恢复原排程或保留替代，
 *       并重新检查设备状态、能力与档期冲突；</li>
 *   <li>检修、改派、吊次开始、恢复并发时，悲观锁 + 乐观锁（erection.version）
 *       保证同一任务只有一个有效设备归属；任一校验失败整体回滚，不留半完成状态。</li>
 * </ol>
 */
@Service
public class ScheduleService {

    private final CraneRepository craneRepository;
    private final ErectionRepository erectionRepository;
    private final LiftRepository liftRepository;
    private final BlockRepository blockRepository;
    private final BerthRepository berthRepository;
    private final CraneScheduleLogRepository logRepository;

    public ScheduleService(CraneRepository craneRepository, ErectionRepository erectionRepository,
            LiftRepository liftRepository, BlockRepository blockRepository, BerthRepository berthRepository,
            CraneScheduleLogRepository logRepository) {
        this.craneRepository = craneRepository;
        this.erectionRepository = erectionRepository;
        this.liftRepository = liftRepository;
        this.blockRepository = blockRepository;
        this.berthRepository = berthRepository;
        this.logRepository = logRepository;
    }

    // ============================= 影响面汇总 =============================

    /**
     * 设备检修影响面：未开始任务、进行中吊次、历史吊次，以及每个未开始任务的替代候选。
     * 停用设备前先调它给管理员看，页面据此说明对分段搭载进度与责任追溯的影响。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> impact(Long craneId) {
        Crane crane = getCrane(craneId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("crane", crane);

        List<Erection> notStarted = erectionRepository
                .findByCraneIdAndStatusInOrderByPlanDateAscIdAsc(craneId, List.of("PLANNED", "READY"));
        List<Lift> inProgress = liftRepository.findByCraneIdAndStatusOrderByLiftTimeAsc(craneId, "IN_PROGRESS");
        List<Lift> history = liftRepository.findByCraneIdAndStatusInOrderByLiftTimeAsc(craneId, List.of("DONE"));
        List<Lift> scheduledLifts = liftRepository
                .findByCraneIdAndStatusInOrderByLiftTimeAsc(craneId, List.of("SCHEDULED"));

        data.put("notStartedTasks", notStarted);
        data.put("inProgressLifts", inProgress);
        data.put("historyLifts", history);
        data.put("scheduledLifts", scheduledLifts);

        // 每个未开始任务给出全部候选及三重匹配结论
        List<Map<String, Object>> taskImpacts = new ArrayList<>();
        for (Erection e : notStarted) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("task", e);
            item.put("alternatives", findAlternatives(crane, e, null));
            taskImpacts.add(item);
        }
        data.put("taskImpacts", taskImpacts);

        // 对搭载进度的影响汇总
        List<String> affectedVessels = notStarted.stream()
                .map(Erection::getVesselNo).filter(StringUtils::hasText).distinct().collect(Collectors.toList());
        data.put("affectedVessels", affectedVessels);
        data.put("notStartedCount", notStarted.size());
        data.put("inProgressCount", inProgress.size());
        data.put("historyCount", history.size());
        return data;
    }

    // ============================= 设备停用 / 检修 =============================

    /**
     * 设备进入检修 / 停用。整个处置在同一事务内：
     * 设备状态、排程归属、待复核标记、吊次安全记录、操作日志、负荷视图要么全部生效，要么全部不动。
     */
    @Transactional
    public Map<String, Object> markUnavailable(Long craneId, CraneUnavailableRequest request) {
        if (request == null || !Dict.isValidCraneStatus(request.getStatus())
                || "AVAILABLE".equals(request.getStatus())) {
            throw new BizException("请选择检修（MAINTAINING）或停用（RETIRED）");
        }
        if (!StringUtils.hasText(request.getReason())) {
            throw new BizException("必须填写检修 / 停用原因，供搭载排程复核与责任追溯");
        }
        boolean auto = "AUTO".equals(request.getDecision());
        boolean hold = "HOLD".equals(request.getDecision());
        if (!auto && !hold) {
            throw new BizException("请选择处置方式：自动筛选替代设备改派（AUTO）或保留原排程待复核（HOLD）");
        }

        Crane crane = craneRepository.findByIdForUpdate(craneId)
                .orElseThrow(() -> new BizException("起重设备不存在：" + craneId));
        if (!crane.isAvailable()) {
            throw new BizException("设备 " + crane.getCraneNo() + " 当前已是「"
                    + Dict.craneStatusName(crane.getStatus()) + "」，无需重复停用");
        }

        List<Lift> inProgress = liftRepository.findByCraneIdAndStatusOrderByLiftTimeAsc(craneId, "IN_PROGRESS");
        if (!inProgress.isEmpty() && !StringUtils.hasText(request.getSafetyHandling())) {
            throw new BizException("设备上有 " + inProgress.size()
                    + " 个进行中吊次，不得换机；必须填写安全处置记录（落位 / 摘钩停电 / 现场监护等）");
        }

        List<Erection> tasks = erectionRepository
                .findByCraneIdAndStatusInOrderByPlanDateAscIdAsc(craneId, List.of("PLANNED", "READY"));

        // —— 以下全部在同一事务内提交，任一异常都会回滚（含设备状态本身） ——
        crane.setStatus(request.getStatus());
        crane.setUnavailableReason(request.getReason());
        crane.setMaintenanceFrom(LocalDateTime.now());
        crane.setMaintenanceOperator(request.getOperator());
        craneRepository.save(crane);
        log(crane, "UNAVAILABLE", null, null, null,
                Dict.craneStatusName(request.getStatus()) + "：" + request.getReason(),
                request.getOperator());

        List<String> reassignedNos = new ArrayList<>();
        List<String> heldNos = new ArrayList<>();

        for (Erection e : tasks) {
            if (auto) {
                // 串行分配：每改派一个任务立即落库，后续任务的档期检查才能看到本批次已占用，
                // 避免两个同日任务被同时改派到同一台替代设备（超订）。任一分派失败 → 整个停用回滚。
                AlternativeCraneDTO best = findAlternatives(crane, e, null).stream()
                        .filter(AlternativeCraneDTO::isEligible).findFirst().orElse(null);
                if (best == null) {
                    throw new BizException("任务 " + e.getErectionNo() + "（" + e.getBlockCode()
                            + "）找不到能力、作业区域、档期都匹配的替代设备（已计入本次其它任务的占用），"
                            + "本次停用已整体回滚，请改用「保留原排程待复核」");
                }
                Crane alt = craneRepository.findByIdForUpdate(best.getCraneId()).orElseThrow();
                applyReassign(e, alt, "AUTO_REASSIGN",
                        "原设备 " + crane.getCraneNo() + " 进入" + Dict.craneStatusName(request.getStatus())
                                + "，自动改派（能力/区域/档期匹配）",
                        request.getOperator());
                reassignedNos.add(e.getErectionNo());
            } else {
                holdForReview(e, crane, "原设备 " + crane.getCraneNo() + " 进入"
                        + Dict.craneStatusName(request.getStatus()) + "，原排程保留待复核",
                        request.getOperator());
                heldNos.add(e.getErectionNo());
            }
        }

        // 进行中吊次：归属、阶段全部冻结，仅追加安全处置记录
        for (Lift l : inProgress) {
            Lift locked = liftRepository.findByIdForUpdate(l.getId()).orElseThrow();
            if (!"IN_PROGRESS".equals(locked.getStatus())) {
                // 并发：吊次刚好在停用瞬间完成 / 换机，按最新状态直接失败回滚，由调用方重试
                throw new BizException("吊次 " + locked.getLiftNo() + " 状态在处置期间发生变化，本次停用已回滚，请重试");
            }
            locked.setSafetyHandling(request.getSafetyHandling());
            liftRepository.save(locked);
            log(crane, "SAFETY_HANDLING", null, null, locked,
                    "进行中吊次保留原设备与阶段「" + Optional.ofNullable(locked.getCurrentStage()).orElse("-")
                            + "」，安全处置：" + request.getSafetyHandling(),
                    request.getOperator());
        }

        // 历史吊次什么都不做 —— 永远归属实际执行设备，只在结果里提示数量
        long historyCount = liftRepository.findByCraneIdAndStatusInOrderByLiftTimeAsc(craneId, List.of("DONE")).size();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("crane", crane);
        result.put("reassignedTasks", reassignedNos);
        result.put("heldTasks", heldNos);
        result.put("inProgressLifts", inProgress.stream().map(Lift::getLiftNo).collect(Collectors.toList()));
        result.put("historyCount", historyCount);
        return result;
    }

    // ============================= 设备恢复 =============================

    /**
     * 设备恢复可用。仅清理设备状态；待复核任务由管理员逐任务选择恢复 / 保留，
     * 不自动批量回改排程。
     */
    @Transactional
    public Crane recover(Long craneId, String operator) {
        Crane crane = craneRepository.findByIdForUpdate(craneId)
                .orElseThrow(() -> new BizException("起重设备不存在：" + craneId));
        if (crane.isAvailable()) {
            throw new BizException("设备 " + crane.getCraneNo() + " 本就可用，无需恢复");
        }
        if ("RETIRED".equals(crane.getStatus())) {
            throw new BizException("设备 " + crane.getCraneNo()
                    + " 已停用报废，不能恢复；如需启用请先在设备档案中改为可用状态");
        }
        crane.setStatus("AVAILABLE");
        crane.setUnavailableReason(null);
        crane.setMaintenanceFrom(null);
        crane.setMaintenanceOperator(null);
        Crane saved = craneRepository.save(crane);
        log(saved, "RECOVER", null, null, null, "设备检修完成，恢复可用", operator);
        return saved;
    }

    /**
     * 恢复时对单个待复核任务的处置。重新检查设备状态、能力、档期：
     * RESTORE 恢复原排程（原机仍可用、能力 / 档期仍满足才允许）；
     * KEEP_ALTERNATIVE 保留当前替代设备（同样要重新校验替代机）。
     */
    @Transactional
    public Erection reviewTask(Long erectionId, ScheduleReviewRequest request) {
        if (request == null || !("RESTORE".equals(request.getDecision())
                || "KEEP_ALTERNATIVE".equals(request.getDecision()))) {
            throw new BizException("请选择：恢复原排程（RESTORE）或保留替代设备（KEEP_ALTERNATIVE）");
        }
        Erection e = erectionRepository.findByIdForUpdate(erectionId)
                .orElseThrow(() -> new BizException("搭载任务不存在：" + erectionId));
        if (!e.isReviewPending()) {
            throw new BizException("任务 " + e.getErectionNo() + " 不处于待复核状态，不能执行恢复确认");
        }
        if (!e.isNotStarted()) {
            throw new BizException("任务 " + e.getErectionNo() + " 已开始 / 完成，归属已冻结，不能恢复改派");
        }
        if (e.getOriginalCraneId() == null) {
            throw new BizException("任务 " + e.getErectionNo() + " 缺少原排程设备记录，无法恢复");
        }

        if ("RESTORE".equals(request.getDecision())) {
            Crane original = craneRepository.findByIdForUpdate(e.getOriginalCraneId()).orElseThrow();
            if (!original.isAvailable()) {
                throw new BizException("原设备 " + original.getCraneNo() + " 当前为「"
                        + Dict.craneStatusName(original.getStatus()) + "」，不能恢复原排程");
            }
            checkEligible(original, e, e.getCraneId());
            e.setCraneId(original.getId());
            e.setCraneNo(original.getCraneNo());
            e.setCraneName(original.getCraneName());
            moveScheduledLifts(e, original);
            e.setReviewPending(false);
            e.setOriginalCraneId(null);
            e.setOriginalCraneNo(null);
            e.setOriginalCraneName(null);
            e.setScheduleNote("检修恢复，已恢复原排程 " + original.getCraneNo()
                    + (StringUtils.hasText(request.getNote()) ? "；" + request.getNote() : ""));
            Erection saved = erectionRepository.save(e);
            log(original, "RESTORE_ORIGINAL", null, saved, null,
                    "任务 " + saved.getErectionNo() + " 恢复原排程（设备状态/能力/档期复核通过）",
                    request.getOperator());
            return saved;
        }

        // KEEP_ALTERNATIVE：当前排程上的替代设备必须仍满足状态 / 能力 / 档期
        Crane current = craneRepository.findByIdForUpdate(e.getCraneId())
                .orElseThrow(() -> new BizException("当前替代设备不存在"));
        if (!current.isAvailable()) {
            throw new BizException("替代设备 " + current.getCraneNo() + " 当前不可用，不能保留，应恢复原排程");
        }
        checkEligible(current, e, e.getOriginalCraneId());
        e.setReviewPending(false);
        e.setScheduleNote("检修恢复，保留替代设备 " + current.getCraneNo()
                + "（原设备 " + e.getOriginalCraneNo() + " 不回切）"
                + (StringUtils.hasText(request.getNote()) ? "；" + request.getNote() : ""));
        String originalNo = e.getOriginalCraneNo();
        e.setOriginalCraneId(null);
        e.setOriginalCraneNo(null);
        e.setOriginalCraneName(null);
        Erection saved = erectionRepository.save(e);
        log(current, "KEEP_ALTERNATIVE", null, saved, null,
                "任务 " + saved.getErectionNo() + " 保留替代设备，原排程 " + originalNo + " 不回切",
                request.getOperator());
        return saved;
    }

    /** 待复核任务列表（恢复页使用） */
    @Transactional(readOnly = true)
    public List<Erection> reviewPendingTasks() {
        return erectionRepository.findByReviewPendingTrueOrderByPlanDateAscIdAsc();
    }

    // ============================= 人工改派 =============================

    /** 人工把单个未开始任务改派到指定设备（能力 / 区域 / 档期同样三重校验）。 */
    @Transactional
    public Erection reassign(Long erectionId, ReassignRequest request) {
        if (request == null || request.getTargetCraneId() == null) {
            throw new BizException("请选择目标替代设备");
        }
        Erection e = erectionRepository.findByIdForUpdate(erectionId)
                .orElseThrow(() -> new BizException("搭载任务不存在：" + erectionId));
        if (!e.isNotStarted()) {
            throw new BizException("任务 " + e.getErectionNo() + " 已开始吊装，设备归属冻结，禁止换机");
        }
        Crane target = craneRepository.findByIdForUpdate(request.getTargetCraneId())
                .orElseThrow(() -> new BizException("起重设备不存在：" + request.getTargetCraneId()));
        if (e.getCraneId() != null && e.getCraneId().equals(target.getId())) {
            throw new BizException("任务 " + e.getErectionNo() + " 当前排程就是 " + target.getCraneNo() + "，无需改派");
        }
        checkEligible(target, e, e.getCraneId());
        return applyReassign(e, target, "REASSIGN",
                StringUtils.hasText(request.getNote()) ? request.getNote()
                        : (e.isReviewPending() ? "待复核期间人工改派替代设备" : "管理员人工改派"),
                request.getOperator());
    }

    // ============================= 替代设备自动筛选 =============================

    /**
     * 自动筛选：能力（额定起重量 ≥ 分段重量）、作业区域（固定设备同区 / 履带吊浮吊机动）、
     * 档期（计划日无其它未开始任务、无进行中吊次）三者全过才可作为替代。
     *
     * @param excludeCraneId 额外排除的设备（如批量改派时已被本批占用的候选，null 表示不排除）
     */
    @Transactional(readOnly = true)
    public List<AlternativeCraneDTO> findAlternatives(Crane original, Erection e, Long excludeCraneId) {
        BigDecimal required = requiredWeight(e);
        String area = berthArea(e.getBerthId());
        List<AlternativeCraneDTO> result = new ArrayList<>();
        for (Crane c : craneRepository.findAllByOrderByIdAsc()) {
            if (c.getId().equals(original.getId())) {
                continue;
            }
            if (excludeCraneId != null && c.getId().equals(excludeCraneId)) {
                continue;
            }
            AlternativeCraneDTO dto = new AlternativeCraneDTO();
            dto.setCraneId(c.getId());
            dto.setCraneNo(c.getCraneNo());
            dto.setCraneName(c.getCraneName());
            dto.setCraneType(c.getCraneType());
            dto.setCraneTypeName(c.getCraneTypeName());
            dto.setRatedLoadT(c.getRatedLoadT());
            dto.setLocation(c.getLocation());

            // 能力
            boolean capacity = c.getRatedLoadT() != null && c.getRatedLoadT().compareTo(required) >= 0;
            dto.setCapacityMatch(capacity);
            dto.setCapacityText("额定 " + c.getRatedLoadT() + " t / 需 " + required + " t"
                    + (capacity ? " ✓" : " ✗ 能力不足"));

            // 作业区域：龙门吊 / 塔吊固定，必须与船台区域一致；履带吊 / 浮吊可机动跨区
            boolean mobile = "CRAWLER".equals(c.getCraneType()) || "FLOATING".equals(c.getCraneType());
            boolean areaOk = c.isAvailable() && (mobile
                    || (area != null && area.equals(c.getLocation())));
            dto.setAreaMatch(areaOk);
            dto.setAreaText(mobile ? (Dict.craneTypeName(c.getCraneType()) + "可机动跨区 ✓")
                    : ("驻位「" + c.getLocation() + "」/ 任务区域「" + area + "」" + (areaOk ? " ✓" : " ✗ 区域不符")));

            // 档期：计划日没有其它未开始任务，也没有进行中吊次
            boolean busy = isBusyOn(c, e.getPlanDate(), e.getId());
            boolean scheduleOk = c.isAvailable() && !busy;
            dto.setScheduleMatch(scheduleOk);
            dto.setScheduleText(!c.isAvailable() ? "设备" + Dict.craneStatusName(c.getStatus()) + " ✗"
                    : (busy ? ("档期 " + e.getPlanDate() + " 已被占用 ✗")
                            : ("档期 " + e.getPlanDate() + " 空闲 ✓")));

            if (!c.isAvailable()) {
                dto.setRejectReason("设备" + Dict.craneStatusName(c.getStatus()));
            } else if (!capacity) {
                dto.setRejectReason("额定起重量不足");
            } else if (!areaOk) {
                dto.setRejectReason("作业区域不匹配");
            } else if (busy) {
                dto.setRejectReason("计划档期冲突");
            }
            result.add(dto);
        }
        // 合格的排前面，合格者中额定起重量越接近需求（越经济）越靠前
        result.sort((a, b) -> {
            if (a.isEligible() != b.isEligible()) {
                return a.isEligible() ? -1 : 1;
            }
            return a.getRatedLoadT().compareTo(b.getRatedLoadT());
        });
        return result;
    }

    /** 单任务的替代候选（供前端候选下拉 / 复核面板调用） */
    @Transactional(readOnly = true)
    public List<AlternativeCraneDTO> alternativesForTask(Long erectionId) {
        Erection e = erectionRepository.findById(erectionId)
                .orElseThrow(() -> new BizException("搭载任务不存在：" + erectionId));
        Crane original = e.getCraneId() == null ? null : craneRepository.findById(e.getCraneId()).orElse(null);
        if (original == null) {
            // 没有原设备时用一个空壳参与筛选（findAlternatives 只拿它的 id 做排除）
            original = new Crane();
            original.setId(-1L);
        }
        return findAlternatives(original, e, null);
    }

    // ============================= 内部方法 =============================

    /** 改派：任务 + 其未开始吊次一起切到替代设备，写审计日志。调用方已持锁。 */
    private Erection applyReassign(Erection e, Crane target, String action, String note, String operator) {
        e.setCraneId(target.getId());
        e.setCraneNo(target.getCraneNo());
        e.setCraneName(target.getCraneName());
        e.setScheduleNote(note);
        e.setScheduledAt(LocalDateTime.now());
        Erection saved = erectionRepository.save(e);
        moveScheduledLifts(saved, target);
        log(target, action, null, saved, null,
                note + "：任务 " + saved.getErectionNo() + "（" + saved.getBlockCode() + "）改派至 "
                        + target.getCraneNo(),
                operator);
        return saved;
    }

    /** 保留原排程待复核：把原设备快照到 original_*，review_pending 置 true。 */
    private void holdForReview(Erection e, Crane original, String note, String operator) {
        e.setOriginalCraneId(original.getId());
        e.setOriginalCraneNo(original.getCraneNo());
        e.setOriginalCraneName(original.getCraneName());
        e.setReviewPending(true);
        e.setScheduleNote(note);
        erectionRepository.save(e);
        log(original, "HOLD_REVIEW", null, e, null,
                note + "：任务 " + e.getErectionNo() + " 保留 " + original.getCraneNo() + " 待复核",
                operator);
    }

    /** 未开始吊次跟随排程一起改派（只动 SCHEDULED 吊次；进行中 / 历史吊次永不改）。 */
    private void moveScheduledLifts(Erection e, Crane target) {
        for (Lift l : liftRepository.findByErectionIdAndStatus(e.getId(), "SCHEDULED")) {
            l.setCraneId(target.getId());
            l.setCraneNo(target.getCraneNo());
            l.setCraneName(target.getCraneName());
            l.setRatedLoadT(target.getRatedLoadT());
            liftRepository.save(l);
        }
    }

    /** 能力 / 区域 / 档期三重校验，任一不过直接抛错（事务回滚）。 */
    void checkEligible(Crane crane, Erection e, Long excludeCraneId) {
        if (!crane.isAvailable()) {
            throw new BizException("设备 " + crane.getCraneNo() + " 当前为「"
                    + Dict.craneStatusName(crane.getStatus()) + "」，不能承接任务");
        }
        BigDecimal required = requiredWeight(e);
        if (crane.getRatedLoadT() == null || crane.getRatedLoadT().compareTo(required) < 0) {
            throw new BizException("设备 " + crane.getCraneNo() + " 额定起重量 " + crane.getRatedLoadT()
                    + " t 小于分段重量 " + required + " t，能力不匹配");
        }
        String area = berthArea(e.getBerthId());
        boolean mobile = "CRAWLER".equals(crane.getCraneType()) || "FLOATING".equals(crane.getCraneType());
        if (!mobile && (area == null || !area.equals(crane.getLocation()))) {
            throw new BizException("设备 " + crane.getCraneNo() + " 驻位「" + crane.getLocation()
                    + "」，任务作业区域为「" + area + "」，固定设备不能跨区作业");
        }
        if (isBusyOn(crane, e.getPlanDate(), e.getId())) {
            throw new BizException("设备 " + crane.getCraneNo() + " 在 " + e.getPlanDate()
                    + " 已有吊装档期冲突");
        }
    }

    /** 档期冲突：该设备在计划日还有其它未开始任务，或当天有进行中吊次。 */
    private boolean isBusyOn(Crane crane, LocalDate date, Long ignoreErectionId) {
        if (date == null) {
            return false;
        }
        for (Erection other : erectionRepository
                .findByCraneIdAndStatusInOrderByPlanDateAscIdAsc(crane.getId(), List.of("PLANNED", "READY"))) {
            if (!other.getId().equals(ignoreErectionId) && date.equals(other.getPlanDate())) {
                return true;
            }
        }
        for (Lift l : liftRepository.findByCraneIdAndStatusOrderByLiftTimeAsc(crane.getId(), "IN_PROGRESS")) {
            if (l.getLiftTime() != null && date.equals(l.getLiftTime().toLocalDate())) {
                return true;
            }
        }
        return false;
    }

    /** 任务所需起重量：取分段重量；取不到时按 0 处理（只要求设备额定值为正）。 */
    private BigDecimal requiredWeight(Erection e) {
        if (e.getBlockId() == null) {
            return BigDecimal.ZERO;
        }
        Optional<Block> block = blockRepository.findById(e.getBlockId());
        return block.map(Block::getWeightT).orElse(BigDecimal.ZERO);
    }

    private String berthArea(Long berthId) {
        if (berthId == null) {
            return null;
        }
        return berthRepository.findById(berthId).map(Berth::getLocation).orElse(null);
    }

    private Crane getCrane(Long id) {
        return craneRepository.findById(id)
                .orElseThrow(() -> new BizException("起重设备不存在：" + id));
    }

    private CraneScheduleLog log(Crane crane, String action, Crane other, Erection erection,
            Lift lift, String detail, String operator) {
        CraneScheduleLog l = new CraneScheduleLog();
        l.setActionType(action);
        if (crane != null) {
            l.setCraneId(crane.getId());
            l.setCraneNo(crane.getCraneNo());
            l.setCraneName(crane.getCraneName());
        }
        if (other != null) {
            l.setOtherCraneId(other.getId());
            l.setOtherCraneNo(other.getCraneNo());
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
            if (l.getErectionId() == null) {
                l.setErectionId(lift.getErectionId());
                l.setErectionNo(lift.getErectionNo());
            }
        }
        l.setDetail(detail);
        l.setOperator(operator);
        l.setCreatedAt(LocalDateTime.now());
        return logRepository.save(l);
    }
}
