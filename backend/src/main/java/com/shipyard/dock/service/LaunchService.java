package com.shipyard.dock.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipyard.dock.common.BizException;
import com.shipyard.dock.common.Dict;
import com.shipyard.dock.domain.Berth;
import com.shipyard.dock.domain.Block;
import com.shipyard.dock.domain.Contract;
import com.shipyard.dock.domain.LaunchClearance;
import com.shipyard.dock.domain.LaunchClearanceCheck;
import com.shipyard.dock.domain.PunchItem;
import com.shipyard.dock.domain.Survey;
import com.shipyard.dock.domain.Vessel;
import com.shipyard.dock.generation.CodeSequenceService;
import com.shipyard.dock.repository.BerthRepository;
import com.shipyard.dock.repository.BlockRepository;
import com.shipyard.dock.repository.ContractRepository;
import com.shipyard.dock.repository.LaunchClearanceCheckRepository;
import com.shipyard.dock.repository.LaunchClearanceRepository;
import com.shipyard.dock.repository.PunchItemRepository;
import com.shipyard.dock.repository.SurveyRepository;
import com.shipyard.dock.repository.VesselRepository;

/**
 * 模块六：下水放行台。
 *
 * <p>放行条件（全部满足才允许确认下水，任一不满足都逐条指出阻塞对象）：
 * ① 全部分段完成搭载（到达搭载 / 合拢 / 交验阶段）；
 * ② 全部分段取得有效的合格或让步结论（最新报验结论为准）；
 * ③ 严重遗留项全部闭环；
 * ④ 所属合同不是草拟状态；
 * ⑤ 船舶在厂且当前占用船台。</p>
 *
 * <p>证据链：放行单生成时的快照落库后不再改写；整改期间的每次复核、签认、确认、作废
 * 都追加一条操作记录，旧结论不会被新数据悄悄覆盖。</p>
 *
 * <p>并发与一致性：生成 / 签认 / 复核 / 作废 / 确认都先拿行级悲观锁再走业务校验；
 * 确认放行把「船舶已下水 + 船台释放 + 放行单确认 + 确认记录」放在同一事务里，
 * 任一失败整体回滚，不留半完成状态。</p>
 */
@Service
public class LaunchService {

    /** 放行单号序号键 */
    private static final String CLEARANCE_SEQ_KEY = "LAUNCH_CLEARANCE";

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_VOID = "VOID";
    private static final String VERDICT_READY = "READY";
    private static final String VERDICT_BLOCKED = "BLOCKED";

    private final LaunchClearanceRepository clearanceRepository;
    private final LaunchClearanceCheckRepository checkRepository;
    private final VesselRepository vesselRepository;
    private final BerthRepository berthRepository;
    private final BlockRepository blockRepository;
    private final ContractRepository contractRepository;
    private final SurveyRepository surveyRepository;
    private final PunchItemRepository punchItemRepository;
    private final ContractPaymentService paymentService;
    private final ObjectMapper objectMapper;

    public LaunchService(LaunchClearanceRepository clearanceRepository,
            LaunchClearanceCheckRepository checkRepository, VesselRepository vesselRepository,
            BerthRepository berthRepository, BlockRepository blockRepository,
            ContractRepository contractRepository, SurveyRepository surveyRepository,
            PunchItemRepository punchItemRepository, ContractPaymentService paymentService,
            ObjectMapper objectMapper) {
        this.clearanceRepository = clearanceRepository;
        this.checkRepository = checkRepository;
        this.vesselRepository = vesselRepository;
        this.berthRepository = berthRepository;
        this.blockRepository = blockRepository;
        this.contractRepository = contractRepository;
        this.surveyRepository = surveyRepository;
        this.punchItemRepository = punchItemRepository;
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
    }

    public LaunchClearance get(Long id) {
        return clearanceRepository.findById(id)
                .orElseThrow(() -> new BizException("放行单不存在：" + id));
    }

    public List<LaunchClearance> list() {
        return clearanceRepository.findAllByOrderByIdDesc();
    }

    /** 某船的全部放行单（新的在前），船舶详情页追溯用 */
    public List<LaunchClearance> ofVessel(Long vesselId) {
        return clearanceRepository.findByVesselIdOrderByIdDesc(vesselId);
    }

    /**
     * 放行单详情：单据 + 生成时快照（原样解析）+ 全部复核 / 操作记录 + 作废重开的后续单。
     */
    public Map<String, Object> detail(Long id) {
        LaunchClearance sheet = get(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("clearance", sheet);
        data.put("snapshot", parseMap(sheet.getSnapshotJson()));
        List<Map<String, Object>> checks = new ArrayList<>();
        for (LaunchClearanceCheck check : checkRepository.findByClearanceIdOrderByIdAsc(id)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", check.getId());
            view.put("checkType", check.getCheckType());
            view.put("checkTypeName", check.getCheckTypeName());
            view.put("verdict", check.getVerdict());
            view.put("verdictName", check.getVerdictName());
            view.put("operator", check.getOperator());
            view.put("note", check.getNote());
            view.put("createdAt", check.getCreatedAt());
            view.put("blockers", parseList(check.getBlockersJson()));
            view.put("summary", parseMap(check.getSummaryJson()));
            checks.add(view);
        }
        data.put("checks", checks);
        clearanceRepository.findFirstByPreviousNoOrderByIdDesc(sheet.getClearanceNo())
                .ifPresent(next -> data.put("supersededBy", next));
        return data;
    }

    /**
     * 放行台总览：全部在厂船舶（在台的排前面）+ 每船实时放行校验 + 待确认 / 最近放行单 + 全部放行单。
     */
    public Map<String, Object> board() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> onBerth = new ArrayList<>();
        List<Map<String, Object>> notOnBerth = new ArrayList<>();
        for (Vessel vessel : vesselRepository.findAllByOrderByIdAsc()) {
            if (!"IN_YARD".equals(vessel.getStatus())) {
                continue;
            }
            ValidationResult vr = validate(vessel);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("vessel", vessel);
            item.put("berth", vr.berth);
            item.put("contract", vr.contract);
            item.put("ready", vr.ready());
            item.put("blockers", vr.blockers);
            item.put("warnings", vr.warnings);
            item.put("summary", vr.summary);
            clearanceRepository.findFirstByVesselIdAndStatusOrderByIdDesc(vessel.getId(), STATUS_PENDING)
                    .ifPresent(sheet -> item.put("pendingClearance", sheet));
            List<LaunchClearance> sheets = clearanceRepository.findByVesselIdOrderByIdDesc(vessel.getId());
            if (!sheets.isEmpty()) {
                item.put("latestClearance", sheets.get(0));
            }
            (vr.berth == null ? notOnBerth : onBerth).add(item);
        }
        onBerth.addAll(notOnBerth);
        data.put("vessels", onBerth);
        data.put("clearances", clearanceRepository.findAllByOrderByIdDesc());
        return data;
    }

    // ---------------- 生成 ----------------

    /** 生成放行单：同一船同时只允许一张待确认单（可沿用整改，或作废后重开） */
    @Transactional
    public Map<String, Object> generate(Long vesselId, String operator) {
        requireOperator(operator);
        LaunchClearance sheet = doGenerate(vesselId, operator, null);
        return detail(sheet.getId());
    }

    private LaunchClearance doGenerate(Long vesselId, String operator, String previousNo) {
        Vessel vessel = vesselRepository.findByIdForUpdate(vesselId)
                .orElseThrow(() -> new BizException("船舶不存在：" + vesselId));
        if (!"IN_YARD".equals(vessel.getStatus())) {
            throw new BizException("船舶 " + vessel.getVesselNo() + " 当前为「"
                    + Dict.vesselStatusName(vessel.getStatus()) + "」，无需生成下水放行单");
        }
        if (clearanceRepository.existsByVesselIdAndStatus(vesselId, STATUS_PENDING)) {
            throw new BizException("船舶 " + vessel.getVesselNo()
                    + " 已存在待确认的放行单：可沿用原单整改后复核，或作废后按最新数据重开");
        }
        ValidationResult vr = validate(vessel);
        LocalDateTime now = LocalDateTime.now();

        LaunchClearance sheet = new LaunchClearance();
        sheet.setClearanceNo(nextClearanceNo());
        sheet.setVesselId(vessel.getId());
        sheet.setVesselNo(vessel.getVesselNo());
        sheet.setVesselName(vessel.getVesselName());
        if (vr.berth != null) {
            sheet.setBerthId(vr.berth.getId());
            sheet.setBerthNo(vr.berth.getBerthNo());
            sheet.setBerthName(vr.berth.getBerthName());
        }
        if (vr.contract != null) {
            sheet.setContractId(vr.contract.getId());
            sheet.setContractNo(vr.contract.getContractNo());
            sheet.setContractStatus(vr.contract.getStatus());
        }
        sheet.setStatus(STATUS_PENDING);
        sheet.setVerdict(vr.verdict);
        sheet.setLatestVerdict(vr.verdict);
        sheet.setLatestCheckedAt(now);
        sheet.setBlockCount((Integer) vr.summary.get("blockCount"));
        sheet.setErectedCount((Integer) vr.summary.get("erectedCount"));
        sheet.setSurveyValidCount((Integer) vr.summary.get("surveyValidCount"));
        sheet.setCriticalOpenCount((Integer) vr.summary.get("criticalOpenCount"));
        sheet.setPunchOpenCount((Integer) vr.summary.get("punchOpenCount"));
        sheet.setSnapshotJson(toJson(buildSnapshot(vr)));
        sheet.setBlockersJson(toJson(vr.blockers));
        sheet.setPreviousNo(previousNo);
        sheet.setGeneratedBy(operator);
        sheet.setGeneratedAt(now);
        sheet.setCreatedAt(now);
        clearanceRepository.save(sheet);
        appendCheck(sheet, "GENERATE", vr, operator,
                previousNo == null ? "生成放行单，快照已留存" : "作废 " + previousNo + " 后按最新数据重开，快照已留存");
        return sheet;
    }

    // ---------------- 沿用整改：复核 ----------------

    /**
     * 沿用已生成的放行单继续整改：复核只追加一条复核记录并刷新「最近复核结论」，
     * 生成时的快照与阻塞明细原样保留。复核发现条件回退时撤销原签认，避免旧签认被新数据静默沿用。
     */
    @Transactional
    public Map<String, Object> recheck(Long id, String operator) {
        requireOperator(operator);
        LaunchClearance sheet = lockPending(id);
        ValidationResult vr = validate(getVessel(sheet.getVesselId()));
        LocalDateTime now = LocalDateTime.now();
        sheet.setLatestVerdict(vr.verdict);
        sheet.setLatestCheckedAt(now);
        String note = "沿用原放行单整改后复核";
        if (sheet.isSigned() && !vr.ready()) {
            note += "；复核发现放行条件回退，已撤销 " + sheet.getSignedBy() + " 的签认，需整改后重新签认";
            sheet.setSignedBy(null);
            sheet.setSignedAt(null);
        }
        clearanceRepository.save(sheet);
        appendCheck(sheet, "RECHECK", vr, operator, note);
        return detail(id);
    }

    // ---------------- 质量签认 ----------------

    /** 签认：签认时以当前数据复核，条件不满足不允许签；确认放行前必须先签认 */
    @Transactional
    public Map<String, Object> sign(Long id, String operator) {
        requireOperator(operator);
        LaunchClearance sheet = lockPending(id);
        if (sheet.isSigned()) {
            throw new BizException("放行单 " + sheet.getClearanceNo() + " 已由 " + sheet.getSignedBy()
                    + " 签认，无需重复签认");
        }
        ValidationResult vr = validate(getVessel(sheet.getVesselId()));
        if (!vr.ready()) {
            throw new BizException("当前放行条件未满足，不能签认：" + summarize(vr.blockers));
        }
        LocalDateTime now = LocalDateTime.now();
        sheet.setSignedBy(operator);
        sheet.setSignedAt(now);
        sheet.setLatestVerdict(VERDICT_READY);
        sheet.setLatestCheckedAt(now);
        clearanceRepository.save(sheet);
        appendCheck(sheet, "SIGN", vr, operator, "质量签认：签认时复核通过");
        return detail(id);
    }

    // ---------------- 作废 / 作废重开 ----------------

    /** 作废：单据与生成快照保留为历史证据，签认随单失效；已放行（船已下水）的单不能作废 */
    @Transactional
    public Map<String, Object> voidSheet(Long id, String operator, String reason) {
        requireOperator(operator);
        LaunchClearance sheet = lockPending(id);
        applyVoid(sheet, operator, StringUtils.hasText(reason) ? reason : "质量人员作废");
        return detail(id);
    }

    /**
     * 作废后按最新数据重开（同一事务）：
     * 旧单作废留痕（快照不改、签认失效），新单重新校验、重新快照、重新走签认，
     * 待确认事项以新单为准，新单回引被作废单号。
     */
    @Transactional
    public Map<String, Object> regenerate(Long id, String operator, String reason) {
        requireOperator(operator);
        LaunchClearance sheet = lockPending(id);
        applyVoid(sheet, operator, StringUtils.hasText(reason) ? reason : "作废后按最新数据重开");
        LaunchClearance fresh = doGenerate(sheet.getVesselId(), operator, sheet.getClearanceNo());
        return detail(fresh.getId());
    }

    private void applyVoid(LaunchClearance sheet, String operator, String reason) {
        sheet.setStatus(STATUS_VOID);
        sheet.setVoidedBy(operator);
        sheet.setVoidedAt(LocalDateTime.now());
        sheet.setVoidReason(reason);
        clearanceRepository.save(sheet);
        appendCheck(sheet, "VOID", null, operator, "作废放行单：" + reason + "（原签认随单失效，快照保留备查）");
    }

    // ---------------- 最终确认 ----------------

    /**
     * 确认下水放行。
     *
     * <p>同一事务内依次完成：行级锁定放行单与船舶 → 以当前数据整链复核 →
     * 船舶置为已下水 → 占用船台释放 → 放行单置为已放行并记录操作者与时间 → 追加确认记录。
     * 任一环节失败整体回滚；多人重复确认时后到者在锁释放后读到「已放行」被拦下，
     * 全链路只有一个基于同一有效快照的结果。</p>
     */
    @Transactional
    public Map<String, Object> confirm(Long id, String operator) {
        requireOperator(operator);
        LaunchClearance sheet = lockPending(id);
        if (!sheet.isSigned()) {
            throw new BizException("放行单 " + sheet.getClearanceNo() + " 尚未经质量人员签认，请先签认再确认放行");
        }
        Vessel vessel = vesselRepository.findByIdForUpdate(sheet.getVesselId())
                .orElseThrow(() -> new BizException("船舶不存在：" + sheet.getVesselId()));
        // 确认前以最新数据整链复核：确认期间分段 / 遗留项 / 合同状态有变化且不再满足条件时，整体失败
        ValidationResult vr = validate(vessel);
        if (!vr.ready()) {
            throw new BizException("确认前复核未通过，放行条件已不满足：" + summarize(vr.blockers));
        }
        Berth berth = berthRepository.findByIdForUpdate(vr.berth.getId())
                .orElseThrow(() -> new BizException("船台不存在：" + vr.berth.getId()));
        if (!vessel.getId().equals(berth.getVesselId())) {
            throw new BizException("船台 " + berth.getBerthNo() + " 的占用船舶已变化，请复核后再确认");
        }
        LocalDateTime now = LocalDateTime.now();
        vessel.setStatus("LAUNCHED");
        vesselRepository.save(vessel);
        berth.setVesselId(null);
        berth.setVesselName(null);
        berth.setStatus("IDLE");
        berth.setOccupiedAt(null);
        berthRepository.save(berth);
        sheet.setStatus(STATUS_CONFIRMED);
        sheet.setConfirmedBy(operator);
        sheet.setConfirmedAt(now);
        sheet.setLatestVerdict(VERDICT_READY);
        sheet.setLatestCheckedAt(now);
        clearanceRepository.save(sheet);
        appendCheck(sheet, "CONFIRM", vr, operator,
                "确认下水放行：船舶置为已下水，船台 " + berth.getBerthNo() + " 已释放");
        // 船舶已下水：同一事务内触发该船履行中合同的付款节点结算状态重算（下水进度款等）
        paymentService.recalcForVessel(vessel.getId(), "下水放行 " + sheet.getClearanceNo());
        return detail(id);
    }

    // ---------------- 放行校验 ----------------

    /**
     * 放行条件整链校验：返回结论、逐条阻塞对象、提示项与快照素材。
     * 生成 / 签认 / 复核 / 确认共用同一份校验逻辑，保证各节点口径一致。
     */
    private ValidationResult validate(Vessel vessel) {
        ValidationResult vr = new ValidationResult();
        vr.vessel = vessel;

        if (!"IN_YARD".equals(vessel.getStatus())) {
            vr.addBlocker("VESSEL_STATUS", "船舶状态", vessel.getVesselNo(),
                    "船舶当前状态为「" + Dict.vesselStatusName(vessel.getStatus()) + "」，不在厂，不能放行下水");
        }
        vr.berth = berthRepository.findFirstByVesselIdAndStatusOrderByIdAsc(vessel.getId(), "OCCUPIED")
                .orElse(null);
        if (vr.berth == null) {
            vr.addBlocker("NO_BERTH", "未在台", vessel.getVesselNo(),
                    "船舶当前未占用任何船台 / 船坞，无台位可释放");
        }

        List<Contract> contracts = contractRepository.findByVesselIdOrderByIdAsc(vessel.getId());
        vr.contract = governingContract(contracts);
        if (vr.contract == null) {
            vr.addBlocker("NO_CONTRACT", "无合同", vessel.getVesselNo(),
                    "该船尚未登记修造合同，不能放行下水");
        } else if ("DRAFT".equals(vr.contract.getStatus())) {
            vr.addBlocker("CONTRACT_DRAFT", "合同草拟", vr.contract.getContractNo(),
                    "所属合同 " + vr.contract.getContractNo() + " 仍为草拟状态，需至少完成签订");
        }
        for (Contract contract : contracts) {
            if (vr.contract != null && !contract.getId().equals(vr.contract.getId())
                    && "DRAFT".equals(contract.getStatus())) {
                vr.addWarning("CONTRACT_DRAFT_OTHER", contract.getContractNo(),
                        "另有合同 " + contract.getContractNo() + " 处于草拟状态（非所属主合同，不阻塞放行）");
            }
        }

        int erectedCount = 0;
        int surveyValidCount = 0;
        List<Block> blocks = blockRepository.findByVesselIdOrderBySegSeqAsc(vessel.getId());
        if (blocks.isEmpty()) {
            vr.addBlocker("NO_BLOCK", "无分段", vessel.getVesselNo(),
                    "该船还没有任何分段档案，无法核对搭载与交验进度");
        }
        for (Block block : blocks) {
            boolean erected = Dict.blockStatusIndex(block.getStatus()) >= Dict.blockStatusIndex("ERECTION");
            Survey latest = surveyRepository.findFirstByBlockIdOrderBySurveyDateDescIdDesc(block.getId())
                    .orElse(null);
            boolean surveyValid = latest != null
                    && ("PASS".equals(latest.getResult()) || "CONCESSION".equals(latest.getResult()));
            if (erected) {
                erectedCount++;
            }
            if (surveyValid) {
                surveyValidCount++;
            }
            vr.addBlockRow(block, latest, erected, surveyValid);
            if (!erected) {
                vr.addBlocker("BLOCK_NOT_ERECTED", "搭载未完", block.getBlockCode(),
                        "分段 " + block.getBlockCode() + "（" + block.getBlockName() + "）尚未完成搭载，当前阶段："
                                + Dict.blockStatusName(block.getStatus()));
            }
            if (latest == null) {
                vr.addBlocker("BLOCK_NO_SURVEY", "未交验", block.getBlockCode(),
                        "分段 " + block.getBlockCode() + "（" + block.getBlockName()
                                + "）尚无报验记录，未取得合格或让步结论");
            } else if (!surveyValid) {
                vr.addBlocker("BLOCK_SURVEY_FAIL", "交验不合格", latest.getSurveyNo(),
                        "分段 " + block.getBlockCode() + "（" + block.getBlockName() + "）最新报验 "
                                + latest.getSurveyNo() + " 结论为「不合格」，需复验取得合格或让步结论");
            }
        }

        int criticalOpen = 0;
        int majorOpen = 0;
        int minorOpen = 0;
        for (PunchItem punch : punchItemRepository.findByVesselId(vessel.getId())) {
            boolean closed = "CLOSED".equals(punch.getStatus());
            vr.addPunchRow(punch, closed);
            if (closed) {
                continue;
            }
            if ("CRITICAL".equals(punch.getSeverity())) {
                criticalOpen++;
                vr.addBlocker("PUNCH_CRITICAL", "严重遗留项", punch.getPunchNo(),
                        "严重遗留项 " + punch.getPunchNo() + "（" + punch.getTitle() + "，分段 "
                                + punch.getBlockCode() + "）未闭环，当前状态：" + Dict.punchStatusName(punch.getStatus()));
            } else {
                if ("MAJOR".equals(punch.getSeverity())) {
                    majorOpen++;
                } else {
                    minorOpen++;
                }
                vr.addWarning("PUNCH_OPEN", punch.getPunchNo(),
                        "遗留项 " + punch.getPunchNo() + "（" + Dict.punchSeverityName(punch.getSeverity())
                                + "，" + punch.getTitle() + "）未闭环，不阻塞放行但需跟踪");
            }
        }

        vr.summary.put("blockCount", blocks.size());
        vr.summary.put("erectedCount", erectedCount);
        vr.summary.put("surveyValidCount", surveyValidCount);
        vr.summary.put("criticalOpenCount", criticalOpen);
        vr.summary.put("majorOpenCount", majorOpen);
        vr.summary.put("minorOpenCount", minorOpen);
        vr.summary.put("punchOpenCount", criticalOpen + majorOpen + minorOpen);
        vr.verdict = vr.blockers.isEmpty() ? VERDICT_READY : VERDICT_BLOCKED;
        return vr;
    }

    /** 所属合同：未结算合同里状态推进最深、最新登记的一份；全部已结算则取最新一份 */
    private Contract governingContract(List<Contract> contracts) {
        if (contracts.isEmpty()) {
            return null;
        }
        Comparator<Contract> byRankThenId = Comparator
                .comparingInt((Contract c) -> Dict.CONTRACT_FLOW.indexOf(c.getStatus()))
                .thenComparing(Contract::getId);
        return contracts.stream()
                .filter(c -> !"SETTLED".equals(c.getStatus()))
                .max(byRankThenId)
                .orElseGet(() -> contracts.stream().max(Comparator.comparing(Contract::getId)).orElse(null));
    }

    /** 生成时关键快照：船舶 / 船台 / 合同 / 分段 / 遗留项 / 阻塞明细，落库后不再改写 */
    private Map<String, Object> buildSnapshot(ValidationResult vr) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        Map<String, Object> vesselMap = new LinkedHashMap<>();
        vesselMap.put("id", vr.vessel.getId());
        vesselMap.put("vesselNo", vr.vessel.getVesselNo());
        vesselMap.put("vesselName", vr.vessel.getVesselName());
        vesselMap.put("status", vr.vessel.getStatus());
        vesselMap.put("statusName", Dict.vesselStatusName(vr.vessel.getStatus()));
        snapshot.put("vessel", vesselMap);
        if (vr.berth != null) {
            Map<String, Object> berthMap = new LinkedHashMap<>();
            berthMap.put("id", vr.berth.getId());
            berthMap.put("berthNo", vr.berth.getBerthNo());
            berthMap.put("berthName", vr.berth.getBerthName());
            berthMap.put("berthTypeName", Dict.berthTypeName(vr.berth.getBerthType()));
            snapshot.put("berth", berthMap);
        }
        if (vr.contract != null) {
            Map<String, Object> contractMap = new LinkedHashMap<>();
            contractMap.put("id", vr.contract.getId());
            contractMap.put("contractNo", vr.contract.getContractNo());
            contractMap.put("contractTypeName", Dict.contractTypeName(vr.contract.getContractType()));
            contractMap.put("status", vr.contract.getStatus());
            contractMap.put("statusName", Dict.contractStatusName(vr.contract.getStatus()));
            snapshot.put("contract", contractMap);
        }
        snapshot.put("blocks", vr.blockRows);
        snapshot.put("punchItems", vr.punchRows);
        snapshot.put("summary", vr.summary);
        snapshot.put("blockers", vr.blockers);
        snapshot.put("warnings", vr.warnings);
        snapshot.put("verdict", vr.verdict);
        snapshot.put("verdictName", Dict.clearanceVerdictName(vr.verdict));
        return snapshot;
    }

    // ---------------- 私有工具 ----------------

    private Vessel getVessel(Long vesselId) {
        return vesselRepository.findById(vesselId)
                .orElseThrow(() -> new BizException("船舶不存在：" + vesselId));
    }

    /** 拿放行单的行级悲观锁，并要求处于待确认状态（并发下到者在这里被拦） */
    private LaunchClearance lockPending(Long id) {
        LaunchClearance sheet = clearanceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("放行单不存在：" + id));
        if (!STATUS_PENDING.equals(sheet.getStatus())) {
            throw new BizException("放行单 " + sheet.getClearanceNo() + " 当前为「"
                    + Dict.clearanceStatusName(sheet.getStatus()) + "」，仅「待确认」状态可执行该操作");
        }
        return sheet;
    }

    private void requireOperator(String operator) {
        if (!StringUtils.hasText(operator)) {
            throw new BizException("请填写操作人");
        }
    }

    private void appendCheck(LaunchClearance sheet, String checkType, ValidationResult vr,
            String operator, String note) {
        LaunchClearanceCheck check = new LaunchClearanceCheck();
        check.setClearanceId(sheet.getId());
        check.setCheckType(checkType);
        if (vr != null) {
            check.setVerdict(vr.verdict);
            check.setBlockersJson(toJson(vr.blockers));
            check.setSummaryJson(toJson(vr.summary));
        }
        check.setOperator(operator);
        check.setNote(note);
        check.setCreatedAt(LocalDateTime.now());
        checkRepository.save(check);
    }

    /** 阻塞明细拼成一句人话，用于异常提示（逐条列出，不只给汇总数字） */
    private String summarize(List<Map<String, Object>> blockers) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blockers.size(); i++) {
            if (i > 0) {
                sb.append('；');
            }
            sb.append(blockers.get(i).get("message"));
        }
        return sb.toString();
    }

    /** 放行单号：LC-yyyyMMdd-序号 */
    private String nextClearanceNo() {
        String scope = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long seq = CodeSequenceService.next(CLEARANCE_SEQ_KEY, scope);
        return "LC-" + scope + "-" + String.format("%03d", seq);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("放行单快照序列化失败", e);
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("放行单快照解析失败", e);
        }
    }

    private List<Map<String, Object>> parseList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("放行单快照解析失败", e);
        }
    }

    /** 一次放行校验的全部产出 */
    private static final class ValidationResult {
        private Vessel vessel;
        private Berth berth;
        private Contract contract;
        private String verdict;
        private final List<Map<String, Object>> blockers = new ArrayList<>();
        private final List<Map<String, Object>> warnings = new ArrayList<>();
        private final List<Map<String, Object>> blockRows = new ArrayList<>();
        private final List<Map<String, Object>> punchRows = new ArrayList<>();
        private final Map<String, Object> summary = new LinkedHashMap<>();

        boolean ready() {
            return VERDICT_READY.equals(verdict);
        }

        void addBlocker(String type, String typeName, String refNo, String message) {
            blockers.add(entry(type, typeName, refNo, message));
        }

        void addWarning(String type, String refNo, String message) {
            warnings.add(entry(type, null, refNo, message));
        }

        private Map<String, Object> entry(String type, String typeName, String refNo, String message) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", type);
            item.put("typeName", typeName);
            item.put("refNo", refNo);
            item.put("message", message);
            return item;
        }

        void addBlockRow(Block block, Survey latest, boolean erected, boolean surveyValid) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", block.getId());
            row.put("blockCode", block.getBlockCode());
            row.put("blockName", block.getBlockName());
            row.put("blockTypeName", Dict.blockTypeName(block.getBlockType()));
            row.put("segSeq", block.getSegSeq());
            row.put("status", block.getStatus());
            row.put("statusName", Dict.blockStatusName(block.getStatus()));
            row.put("erected", erected);
            row.put("latestSurveyNo", latest == null ? null : latest.getSurveyNo());
            row.put("latestSurveyResult", latest == null ? null : latest.getResult());
            row.put("latestSurveyResultName", latest == null ? "（无报验）" : Dict.surveyResultName(latest.getResult()));
            row.put("surveyValid", surveyValid);
            blockRows.add(row);
        }

        void addPunchRow(PunchItem punch, boolean closed) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", punch.getId());
            row.put("punchNo", punch.getPunchNo());
            row.put("title", punch.getTitle());
            row.put("blockCode", punch.getBlockCode());
            row.put("severity", punch.getSeverity());
            row.put("severityName", Dict.punchSeverityName(punch.getSeverity()));
            row.put("status", punch.getStatus());
            row.put("statusName", Dict.punchStatusName(punch.getStatus()));
            row.put("owner", punch.getOwner());
            row.put("closed", closed);
            punchRows.add(row);
        }
    }
}
