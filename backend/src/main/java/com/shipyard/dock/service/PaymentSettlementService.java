package com.shipyard.dock.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import com.shipyard.dock.domain.Block;
import com.shipyard.dock.domain.Contract;
import com.shipyard.dock.domain.ContractPaymentLog;
import com.shipyard.dock.domain.ContractPaymentNode;
import com.shipyard.dock.domain.Survey;
import com.shipyard.dock.domain.Vessel;
import com.shipyard.dock.repository.BlockRepository;
import com.shipyard.dock.repository.ContractPaymentLogRepository;
import com.shipyard.dock.repository.ContractPaymentNodeRepository;
import com.shipyard.dock.repository.ContractRepository;
import com.shipyard.dock.repository.SurveyRepository;
import com.shipyard.dock.repository.VesselRepository;

/**
 * 合同付款节点结算引擎（模块二的结算部分）。
 *
 * <p>这是付款结算状态的唯一计算口径：合同进入结算流程（履行中）后按合同类型生成付款节点，
 * 每个节点能不能付款，由后端统一依据 <b>合同金额 × 节点比例、该船已完成的有效报验结论
 * （合格 / 让步接收）、当前船舶状态、前序节点是否已付</b> 实时计算。条件不满足时节点停留在
 * 「待结算」，并把阻塞项逐条落库（不是只在页面上禁用按钮）。</p>
 *
 * <p>联动重算：报验结果被补录（新增有效报验）、撤回（删除报验单）或改为让步接收 / 不合格
 * （更新结论），以及船舶状态变化、合同金额调整，都会在同一事务里重算受影响合同的全部
 * <b>待结算</b> 节点并追加操作记录；已付款节点与当时的确认记录、操作者痕迹冻结保留，
 * 任何刷新都不能悄悄覆盖历史。</p>
 *
 * <p>并发与一致性：确认付款先对节点加行级悲观锁、再锁合同与船舶，重新跑同一份校验；
 * 金额扣减（合同已付金额）、节点置为已付款、合同置为已结算、确认记录在同一事务内完成，
 * 任一失败整体回滚；被驳回的尝试通过独立事务只追加一条「驳回 + 操作者」记录。
 * 多人同时确认 / 网络重试同一节点，后到者读到已付款直接拦下，只有一笔有效结果。</p>
 *
 * <p>读口径：合同详情、船舶合同列表、报验详情都走 {@link #decorate} /
 * {@link #nodeView}，刷新三处看到的结算状态与阻塞原因必然一致；每笔已付款的证据快照
 * （金额 / 报验结论 / 船舶状态）永久留存，历史可解释。</p>
 */
@Service
public class PaymentSettlementService {

    /** 待结算 / 已付款 / 联动来源等常量表 */
    private static final String NODE_PENDING = "PENDING";
    private static final String NODE_PAID = "PAID";
    private static final String VERDICT_READY = "READY";
    private static final String VERDICT_BLOCKED = "BLOCKED";
    private static final String SOURCE_RECHECK = "RECHECK";
    private static final String DEFAULT_OPERATOR = "系统联动";

    private final ContractRepository contractRepository;
    private final ContractPaymentNodeRepository nodeRepository;
    private final ContractPaymentLogRepository logRepository;
    private final VesselRepository vesselRepository;
    private final BlockRepository blockRepository;
    private final SurveyRepository surveyRepository;
    private final PaymentTraceService traceService;
    private final ObjectMapper objectMapper;

    public PaymentSettlementService(ContractRepository contractRepository,
            ContractPaymentNodeRepository nodeRepository, ContractPaymentLogRepository logRepository,
            VesselRepository vesselRepository, BlockRepository blockRepository,
            SurveyRepository surveyRepository, PaymentTraceService traceService, ObjectMapper objectMapper) {
        this.contractRepository = contractRepository;
        this.nodeRepository = nodeRepository;
        this.logRepository = logRepository;
        this.vesselRepository = vesselRepository;
        this.blockRepository = blockRepository;
        this.surveyRepository = surveyRepository;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
    }

    // =================================================================
    // 读：节点详情 / 合同结算汇总（三处页面共用同一份计算）
    // =================================================================

    /**
     * 合同结算详情：合同 + 节点（含实时结算状态与阻塞）+ 全部操作记录。
     * 合同详情、船舶合同列表点进来、报验详情跳转过来，看到的都是这一份。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> contractSettlement(Long contractId) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new BizException("合同不存在：" + contractId));
        decorate(contract, true);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contract", contract);
        data.put("nodes", contract.getPaymentNodes());
        data.put("logs", logViews(contractId));
        return data;
    }

    /** 节点历史：该节点的全部操作记录（确认时用了什么金额 / 报验结论 / 船舶状态） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> nodeHistory(Long nodeId) {
        ContractPaymentNode node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new BizException("付款节点不存在：" + nodeId));
        List<Map<String, Object>> list = new ArrayList<>();
        for (ContractPaymentLog log : logRepository.findByNodeIdOrderByIdAsc(nodeId)) {
            list.add(logView(log));
        }
        return list;
    }

    /**
     * 给合同实体挂上实时结算字段（状态 / 已付 / 阻塞）。{@code withNodes}=true 时附带完整节点视图。
     * 合同列表（船舶时间轴右侧合同卡）与合同详情走同一逻辑。
     */
    @Transactional(readOnly = true)
    public Contract decorate(Contract contract, boolean withNodes) {
        if (contract == null) {
            return null;
        }
        boolean started = isStarted(contract);
        contract.setSettlementStarted(started);
        contract.setPaymentNodes(null);
        contract.setBlockers(List.of());
        contract.setSettlementWarnings(List.of());
        if (!started) {
            contract.setPaymentState("NOT_STARTED");
            contract.setPaymentStateName("未进入结算");
            contract.setPaidNodeCount(0);
            contract.setTotalNodeCount(0);
            contract.setSettledAmount(BigDecimal.ZERO.setScale(2));
            if (contract.getPaidAmount() == null) {
                contract.setPaidAmount(BigDecimal.ZERO.setScale(2));
            }
            return contract;
        }

        List<ContractPaymentNode> nodes = nodeRepository.findByContractIdOrderBySeqAsc(contract.getId());
        int paidCount = 0;
        BigDecimal settled = BigDecimal.ZERO.setScale(2);
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> firstBlockers = null;
        Vessel vessel = contract.getVesselId() == null ? null
                : vesselRepository.findById(contract.getVesselId()).orElse(null);
        for (ContractPaymentNode node : nodes) {
            if (node.isPaid()) {
                paidCount++;
                settled = settled.add(nullToZero(node.getAmountPaid()));
                warnings.addAll(driftWarnings(node, contract, vessel));
                continue;
            }
            // 待结算节点：用当前数据实时判定，仅用于读取，不落库；阻塞取最早一个待结算节点
            Evaluation ev = evaluate(contract, vessel, nodes, node);
            if (firstBlockers == null) {
                firstBlockers = ev.blockers;
            }
        }
        contract.setBlockers(firstBlockers == null ? List.of() : firstBlockers);
        if (contract.getPaidAmount() == null) {
            contract.setPaidAmount(settled);
        }
        contract.setSettledAmount(nullToZero(contract.getPaidAmount()).setScale(2, RoundingMode.HALF_UP));
        contract.setPaidNodeCount(paidCount);
        contract.setTotalNodeCount(nodes.size());
        contract.setSettlementWarnings(warnings);
        contract.setPaymentState(resolveState(contract, nodes.size(), paidCount));
        contract.setPaymentStateName(stateName(contract.getPaymentState()));
        if (withNodes) {
            List<Map<String, Object>> views = new ArrayList<>();
            for (ContractPaymentNode node : nodes) {
                views.add(nodeView(contract, vessel, nodes, node));
            }
            contract.setPaymentNodes(views);
        }
        return contract;
    }

    /** 批量装饰合同列表（避免逐合同查节点），只挂汇总状态与最早阻塞，不带节点明细 */
    @Transactional(readOnly = true)
    public List<Contract> decorateAll(List<Contract> contracts) {
        for (Contract contract : contracts) {
            decorate(contract, false);
        }
        return contracts;
    }

    /** 节点视图：已付款给冻结快照，待结算给实时判定结果 */
    @Transactional(readOnly = true)
    public Map<String, Object> nodeView(ContractPaymentNode node) {
        Contract contract = contractRepository.findById(node.getContractId())
                .orElseThrow(() -> new BizException("合同不存在：" + node.getContractId()));
        Vessel vessel = contract.getVesselId() == null ? null
                : vesselRepository.findById(contract.getVesselId()).orElse(null);
        List<ContractPaymentNode> siblings = nodeRepository.findByContractIdOrderBySeqAsc(contract.getId());
        return nodeView(contract, vessel, siblings, node);
    }

    private Map<String, Object> nodeView(Contract contract, Vessel vessel,
            List<ContractPaymentNode> siblings, ContractPaymentNode node) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", node.getId());
        view.put("nodeNo", node.getNodeNo());
        view.put("contractId", node.getContractId());
        view.put("contractNo", node.getContractNo());
        view.put("vesselId", node.getVesselId());
        view.put("vesselNo", node.getVesselNo());
        view.put("vesselName", node.getVesselName());
        view.put("seq", node.getSeq());
        view.put("nodeName", node.getNodeName());
        view.put("ratio", node.getRatio());
        view.put("status", node.getStatus());
        view.put("statusName", Dict.paymentNodeStatusName(node.getStatus()));
        view.put("requiredVesselStatus", node.getRequiredVesselStatus());
        view.put("requiredVesselStatusName", Dict.vesselStatusName(node.getRequiredVesselStatus()));
        view.put("requiredAcceptSurveys", node.getRequiredAcceptSurveys());
        view.put("confirmedBy", node.getConfirmedBy());
        view.put("confirmedAt", node.getConfirmedAt());
        view.put("settleRef", node.getSettleRef());
        view.put("amountPaid", node.getAmountPaid());
        view.put("frozenContractAmount", node.getFrozenContractAmount());
        view.put("evidence", parseMap(node.getEvidenceJson()));

        if (node.isPaid()) {
            view.put("verdict", VERDICT_READY);
            view.put("verdictName", "可付款");
            view.put("blockers", List.of());
            view.put("amountDue", node.getAmountPaid());
            view.put("driftWarnings", driftWarnings(node, contract, vessel));
        } else {
            Evaluation ev = evaluate(contract, vessel, siblings, node);
            view.put("verdict", ev.verdict);
            view.put("verdictName", VERDICT_READY.equals(ev.verdict) ? "可付款" : "有阻塞");
            view.put("blockers", ev.blockers);
            view.put("amountDue", ev.amountDue);
            view.put("currentAcceptCount", ev.acceptCount);
            view.put("currentSurveyNo", ev.latestSurveyNo);
            view.put("currentSurveyResult", ev.latestSurveyResult);
            view.put("currentSurveyResultName", ev.latestSurveyResult == null ? null
                    : Dict.surveyResultName(ev.latestSurveyResult));
            view.put("currentVesselStatus", ev.vessel == null ? null : ev.vessel.getStatus());
            view.put("currentVesselStatusName", ev.vessel == null ? null
                    : Dict.vesselStatusName(ev.vessel.getStatus()));
            view.put("prevUnpaid", ev.prevUnpaid);
        }
        return view;
    }

    // =================================================================
    // 进入结算流程：生成节点
    // =================================================================

    /**
     * 合同进入结算流程（推进到履行中，或历史履行中 / 已结算合同首次装载）时生成付款节点。
     * 已存在节点则原样保留、绝不重建（已付款历史不可覆盖）。
     */
    @Transactional
    public List<ContractPaymentNode> ensureNodes(Contract contract, String operator) {
        List<ContractPaymentNode> existing = nodeRepository.findByContractIdOrderBySeqAsc(contract.getId());
        if (!existing.isEmpty()) {
            return existing;
        }
        List<Dict.PaymentMilestoneTemplate> templates = Dict.paymentTemplates(contract.getContractType());
        if (templates.isEmpty()) {
            throw new BizException("合同类型 " + Dict.contractTypeName(contract.getContractType())
                    + " 没有配置付款节点模板，不能进入结算");
        }
        LocalDateTime now = LocalDateTime.now();
        List<ContractPaymentNode> created = new ArrayList<>();
        int seq = 1;
        for (Dict.PaymentMilestoneTemplate tpl : templates) {
            ContractPaymentNode node = new ContractPaymentNode();
            node.setNodeNo(nodeNo(contract.getContractNo(), seq));
            node.setContractId(contract.getId());
            node.setContractNo(contract.getContractNo());
            node.setVesselId(contract.getVesselId());
            node.setVesselNo(contract.getVesselNo());
            node.setVesselName(contract.getVesselName());
            node.setSeq(seq);
            node.setNodeName(tpl.name());
            node.setRatio(tpl.ratio());
            node.setStatus(NODE_PENDING);
            node.setRequiredVesselStatus(tpl.requiredVesselStatus());
            node.setRequiredAcceptSurveys(tpl.requiredAcceptSurveys());
            node.setAmountDue(amountOf(contract.getAmount(), tpl.ratio()));
            node.setCreatedAt(now);
            created.add(nodeRepository.save(node));
            seq++;
        }
        // 生成后立即按当前数据算一次，阻塞项落库（而不是等前端来问）
        Vessel vessel = contract.getVesselId() == null ? null
                : vesselRepository.findById(contract.getVesselId()).orElse(null);
        for (ContractPaymentNode node : created) {
            Evaluation ev = evaluate(contract, vessel, created, node);
            applyEvaluationToNode(node, ev);
            nodeRepository.save(node);
        }
        if (contract.getPaidAmount() == null) {
            contract.setPaidAmount(BigDecimal.ZERO.setScale(2));
            contractRepository.save(contract);
        }
        // 进入结算留痕：记录当时合同金额、报验结论数、船舶状态
        Evaluation first = evaluate(contract, vessel, created, created.get(0));
        traceService.record("CREATE", contract.getId(), null, null, contract.getVesselId(),
                SOURCE_RECHECK, contract.getContractNo(),
                created.stream().allMatch(n -> VERDICT_READY.equals(n.getLatestVerdict()))
                        ? VERDICT_READY : VERDICT_BLOCKED,
                contract.getAmount(), first.acceptCount,
                vessel == null ? null : vessel.getStatus(),
                first.blockers, first.evidence,
                StringUtils.hasText(operator) ? operator : DEFAULT_OPERATOR,
                "合同 " + contract.getContractNo() + " 进入结算流程，生成 " + created.size()
                        + " 个付款节点（" + Dict.contractTypeName(contract.getContractType())
                        + "），应付金额按合同金额 " + contract.getAmount() + " 万元 × 比例计算");
        return created;
    }

    // =================================================================
    // 确认付款（并发安全 + 同事务联动 + 失败留痕）
    // =================================================================

    /**
     * 确认付款。节点行锁 → 合同行锁 → 船舶行锁 → 用最新数据重算 → 全部通过才：
     * 冻结金额 / 报验 / 船舶快照到节点、累加合同已付金额、末笔则合同置已结算、追加确认记录。
     * 任一不通过：先以独立事务追加一条「驳回 + 操作者 + 阻塞项」，再抛业务异常，
     * 主事务整体回滚 —— 不会出现金额已扣但节点没更新的半完成状态。
     */
    @Transactional
    public Map<String, Object> confirm(Long nodeId, String operator) {
        if (!StringUtils.hasText(operator)) {
            throw new BizException("请填写付款确认人");
        }
        // ① 节点行级悲观锁：并发 / 重试在此串行
        ContractPaymentNode node = nodeRepository.findByIdForUpdate(nodeId)
                .orElseThrow(() -> new BizException("付款节点不存在：" + nodeId));
        // ② 重复确认 / 网络重试：已付款直接幂等拦下，后到者不产生第二笔结果
        if (node.isPaid()) {
            throw new BizException("付款节点 " + node.getNodeNo() + " 已由 " + node.getConfirmedBy()
                    + " 于 " + node.getConfirmedAt() + " 确认付款，不能重复确认");
        }
        Contract contract = contractRepository.findByIdForUpdate(node.getContractId())
                .orElseThrow(() -> new BizException("合同不存在：" + node.getContractId()));
        Vessel vessel = null;
        if (contract.getVesselId() != null) {
            vessel = vesselRepository.findByIdForUpdate(contract.getVesselId()).orElse(null);
        }
        List<ContractPaymentNode> siblings = nodeRepository.findByContractIdOrderBySeqAsc(contract.getId());

        // ③ 以最新数据整链重算（金额 / 报验结论 / 船舶状态 / 前序节点）
        Evaluation ev = evaluate(contract, vessel, siblings, node);
        if (!ev.ready()) {
            // ④ 驳回留痕：独立事务，主事务随后回滚，痕迹保留
            traceService.recordReject(contract.getId(), node.getId(), node.getNodeNo(),
                    contract.getVesselId(), node.getNodeNo(), contract.getAmount(), ev.acceptCount,
                    vessel == null ? null : vessel.getStatus(), ev.blockers, ev.evidence,
                    operator, "付款确认被驳回：" + summarize(ev.blockers)
                            + "（本次操作整体回滚，合同已付金额不变）");
            throw new BizException("付款节点 " + node.getNodeNo() + " 结算条件未满足："
                    + summarize(ev.blockers));
        }

        // ⑤ 全部通过：同一事务内完成扣减 / 节点更新 / 合同状态 / 确认记录
        LocalDateTime now = LocalDateTime.now();
        BigDecimal paid = ev.amountDue;
        node.setStatus(NODE_PAID);
        node.setAmountPaid(paid);
        node.setAmountDue(paid);
        node.setFrozenContractAmount(contract.getAmount());
        node.setEvidenceJson(toJson(ev.evidence));
        node.setSettleRef(ev.latestSurveyNo);
        node.setConfirmedBy(operator);
        node.setConfirmedAt(now);
        node.setLatestVerdict(VERDICT_READY);
        node.setLatestCheckedAt(now);
        node.setBlockersJson(toJson(List.of()));
        nodeRepository.save(node);

        // 合同已付金额以全部已付款节点重算（不做增量累加，杜绝并发 / 重试错算）
        BigDecimal totalPaid = BigDecimal.ZERO.setScale(2);
        boolean allPaid = true;
        for (ContractPaymentNode n : siblings) {
            if (n.getId().equals(node.getId())) {
                totalPaid = totalPaid.add(paid);
            } else if (n.isPaid()) {
                totalPaid = totalPaid.add(nullToZero(n.getAmountPaid()));
            } else {
                allPaid = false;
            }
        }
        contract.setPaidAmount(totalPaid);
        String settleNote;
        if (allPaid) {
            contract.setStatus("SETTLED");
            contract.setSettledAt(now);
            contract.setSettleRef(ev.latestSurveyNo);
            settleNote = "末笔付款确认，合同全部 " + siblings.size() + " 个节点结清，合同置为「已结算」";
        } else {
            settleNote = "付款确认完成，等待后续节点";
        }
        contractRepository.save(contract);
        traceService.record("CONFIRM", contract.getId(), node.getId(), node.getNodeNo(),
                contract.getVesselId(), SOURCE_RECHECK, ev.latestSurveyNo, VERDICT_READY,
                contract.getAmount(), ev.acceptCount, vessel == null ? null : vessel.getStatus(),
                List.of(), ev.evidence, operator,
                "确认付款 " + paid + " 万元（合同金额 " + contract.getAmount() + " × "
                        + node.getRatio() + "%），依据报验 " + ev.latestSurveyNo + "（"
                        + Dict.surveyResultName(ev.latestSurveyResult) + "）、船舶状态「"
                        + Dict.vesselStatusName(vessel == null ? null : vessel.getStatus()) + "」。"
                        + settleNote);
        return contractSettlement(contract.getId());
    }

    /** 手工复核：按最新数据重算某合同全部待结算节点（已付款节点不动） */
    @Transactional
    public Map<String, Object> recheckContract(Long contractId, String operator) {
        Contract contract = contractRepository.findByIdForUpdate(contractId)
                .orElseThrow(() -> new BizException("合同不存在：" + contractId));
        if (!isStarted(contract)) {
            throw new BizException("合同 " + contract.getContractNo() + " 尚未进入结算流程，无需复核");
        }
        recalculateContract(contract, SOURCE_RECHECK, contract.getContractNo(), operator);
        return contractSettlement(contractId);
    }

    // =================================================================
    // 联动重算：报验补录 / 撤回 / 改让步、船舶状态、合同金额变化
    // =================================================================

    /**
     * 报验事件联动：重算该船所有「已进入结算」合同的待结算节点。
     * 已付款节点不参与（历史不覆盖），只追加一条说明本次来源的重算记录。
     */
    @Transactional
    public void recalcBySurvey(Long vesselId, String sourceType, String sourceRef, String operator) {
        if (vesselId == null) {
            return;
        }
        List<Contract> contracts = contractRepository.findByVesselIdOrderByIdAsc(vesselId);
        for (Contract contract : contracts) {
            if (!isStarted(contract)) {
                continue;
            }
            Contract locked = contractRepository.findByIdForUpdate(contract.getId())
                    .orElse(contract);
            recalculateContract(locked, sourceType, sourceRef, operator);
        }
    }

    /** 船舶状态变化联动（船舶详情改状态、下水放行确认等） */
    @Transactional
    public void recalcByVesselStatus(Vessel vessel, String operator) {
        if (vessel == null) {
            return;
        }
        recalcBySurvey(vessel.getId(), "VESSEL_STATUS", vessel.getVesselNo(), operator);
    }

    /** 合同金额调整联动：待结算节点的应付金额随新金额变化并重算阻塞 */
    @Transactional
    public void recalcByAmount(Contract contract, String operator) {
        if (!isStarted(contract)) {
            return;
        }
        Contract locked = contractRepository.findByIdForUpdate(contract.getId()).orElse(contract);
        recalculateContract(locked, "CONTRACT_AMOUNT", locked.getContractNo(), operator);
    }

    /**
     * 重算单个合同的全部待结算节点。只写待结算节点，已付款节点一行不动；
     * 阻塞项 / 结论 / 应付金额发生变化才追加联动记录，无变化不制造噪音。
     */
    private void recalculateContract(Contract contract, String sourceType, String sourceRef, String operator) {
        List<ContractPaymentNode> nodes = nodeRepository.findByContractIdOrderBySeqAsc(contract.getId());
        if (nodes.isEmpty()) {
            return;
        }
        Vessel vessel = contract.getVesselId() == null ? null
                : vesselRepository.findById(contract.getVesselId()).orElse(null);
        String op = StringUtils.hasText(operator) ? operator : DEFAULT_OPERATOR;
        boolean changed = false;
        Evaluation any = null;
        for (ContractPaymentNode node : nodes) {
            if (node.isPaid()) {
                continue; // 已付款节点冻结，任何联动都不得倒改
            }
            Evaluation ev = evaluate(contract, vessel, nodes, node);
            any = ev;
            if (verdictChanged(node, ev)) {
                changed = true;
            }
            applyEvaluationToNode(node, ev);
            nodeRepository.save(node);
        }
        if (any == null) {
            return;
        }
        boolean allReady = nodes.stream().allMatch(n -> VERDICT_READY.equals(n.getLatestVerdict()));
        traceService.record("RECALC", contract.getId(), null, null, contract.getVesselId(),
                sourceType, sourceRef, allReady ? VERDICT_READY : VERDICT_BLOCKED,
                contract.getAmount(), any.acceptCount,
                vessel == null ? null : vessel.getStatus(),
                earliestBlockers(nodes), any.evidence, op,
                "因「" + Dict.paymentSourceName(sourceType) + "」（" + sourceRef + "）重新计算合同 "
                        + contract.getContractNo() + " 的待结算付款节点；已付款节点与原确认记录保持不变"
                        + (changed ? "；本次有节点结算状态 / 阻塞项发生变化" : "；各节点结论未发生变化"));
    }

    // =================================================================
    // 唯一结算口径：条件评估
    // =================================================================

    /**
     * 单个待结算节点的结算条件评估。阻塞项逐条给出：
     * ① 合同金额 &gt; 0（金额是所有应付金额的计算基数）；
     * ② 已完成的有效报验结论（合格 / 让步接收）达到节点要求条数，以每张报验单最新结论计；
     * ③ 当前船舶状态达到节点要求（在厂 → 已下水 → 已交付）；
     * ④ 前序节点必须已付款（逐笔确认）；
     * ⑤ 合同绑定的船舶仍存在。
     */
    private Evaluation evaluate(Contract contract, Vessel vessel,
            List<ContractPaymentNode> siblings, ContractPaymentNode node) {
        Evaluation ev = new Evaluation();
        ev.contract = contract;
        ev.vessel = vessel;
        ev.node = node;
        ev.amountDue = amountOf(contract.getAmount(), node.getRatio());

        if (contract.getAmount() == null || contract.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            ev.addBlocker("AMOUNT_INVALID", "合同金额", contract.getContractNo(),
                    "合同金额缺失或不大于 0，付款节点 " + node.getNodeName() + " 的应付金额无法计算");
        }
        if (contract.getVesselId() == null || vessel == null) {
            ev.addBlocker("NO_VESSEL", "未绑定船舶", contract.getContractNo(),
                    "合同未绑定有效船舶，无法核对报验结论与船舶状态");
        }

        // 有效报验结论：该船每个分段的最新一张报验单，结论为合格或让步接收，按报验时间倒序
        List<Survey> accepted = new ArrayList<>();
        if (vessel != null) {
            for (Block block : blockRepository.findByVesselIdOrderBySegSeqAsc(vessel.getId())) {
                surveyRepository.findFirstByBlockIdOrderBySurveyDateDescIdDesc(block.getId())
                        .filter(s -> "PASS".equals(s.getResult()) || "CONCESSION".equals(s.getResult()))
                        .ifPresent(accepted::add);
            }
            accepted.sort((a, b) -> {
                int c = b.getSurveyDate().compareTo(a.getSurveyDate());
                return c != 0 ? c : b.getId().compareTo(a.getId());
            });
        }
        ev.acceptCount = accepted.size();
        ev.latestSurvey = accepted.isEmpty() ? null : accepted.get(0);
        ev.latestSurveyNo = ev.latestSurvey == null ? null : ev.latestSurvey.getSurveyNo();
        ev.latestSurveyResult = ev.latestSurvey == null ? null : ev.latestSurvey.getResult();

        int requiredSurveys = node.getRequiredAcceptSurveys() == null ? 0 : node.getRequiredAcceptSurveys();
        if (vessel != null && accepted.size() < requiredSurveys) {
            ev.addBlocker("SURVEY_NOT_ENOUGH", "报验结论不足", node.getNodeNo(),
                    "付款节点「" + node.getNodeName() + "」要求至少完成 " + requiredSurveys
                            + " 条合格 / 让步接收的报验结论，当前该船仅 " + accepted.size()
                            + " 条（以每张报验单最新结论计；不合格或已撤回的报验不计入）");
        }

        if (vessel != null) {
            int currentRank = Dict.vesselStatusRank(vessel.getStatus());
            int requiredRank = Dict.vesselStatusRank(node.getRequiredVesselStatus());
            if (currentRank < 0 || currentRank < requiredRank) {
                ev.addBlocker("VESSEL_STATUS", "船舶状态未达标", vessel.getVesselNo(),
                        "付款节点「" + node.getNodeName() + "」要求船舶至少为「"
                                + Dict.vesselStatusName(node.getRequiredVesselStatus()) + "」，当前为「"
                                + Dict.vesselStatusName(vessel.getStatus()) + "」");
            }
        }

        // 前序节点必须已付款
        for (ContractPaymentNode other : siblings) {
            if (other.getSeq() < node.getSeq() && !other.isPaid()) {
                ev.prevUnpaid = true;
                ev.addBlocker("PREV_NODE_UNPAID", "前序节点未付", other.getNodeNo(),
                        "前序付款节点「" + other.getNodeName() + "」（" + other.getNodeNo()
                                + "）尚未付款，付款节点须逐笔确认，不能跳付到「" + node.getNodeName() + "」");
            }
        }

        ev.verdict = ev.blockers.isEmpty() ? VERDICT_READY : VERDICT_BLOCKED;
        ev.evidence = buildEvidence(contract, vessel, node, accepted, siblings);
        return ev;
    }

    /** 证据快照：当时的合同金额、节点应付金额、有效报验结论清单、船舶状态、前序节点状态 */
    private Map<String, Object> buildEvidence(Contract contract, Vessel vessel,
            ContractPaymentNode node, List<Survey> accepted, List<ContractPaymentNode> siblings) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("contractNo", contract.getContractNo());
        evidence.put("contractType", contract.getContractType());
        evidence.put("contractTypeName", Dict.contractTypeName(contract.getContractType()));
        evidence.put("contractAmount", contract.getAmount());
        evidence.put("nodeNo", node.getNodeNo());
        evidence.put("nodeName", node.getNodeName());
        evidence.put("ratio", node.getRatio());
        evidence.put("amountDue", amountOf(contract.getAmount(), node.getRatio()));
        evidence.put("requiredAcceptSurveys", node.getRequiredAcceptSurveys());
        evidence.put("requiredVesselStatus", node.getRequiredVesselStatus());
        evidence.put("requiredVesselStatusName", Dict.vesselStatusName(node.getRequiredVesselStatus()));

        Map<String, Object> vesselMap = new LinkedHashMap<>();
        if (vessel != null) {
            vesselMap.put("id", vessel.getId());
            vesselMap.put("vesselNo", vessel.getVesselNo());
            vesselMap.put("vesselName", vessel.getVesselName());
            vesselMap.put("status", vessel.getStatus());
            vesselMap.put("statusName", Dict.vesselStatusName(vessel.getStatus()));
        }
        evidence.put("vessel", vesselMap);

        List<Map<String, Object>> surveyRows = new ArrayList<>();
        for (Survey s : accepted) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("surveyNo", s.getSurveyNo());
            row.put("blockCode", s.getBlockCode());
            row.put("surveyItem", s.getSurveyItem());
            row.put("surveyItemName", Dict.surveyItemName(s.getSurveyItem()));
            row.put("result", s.getResult());
            row.put("resultName", Dict.surveyResultName(s.getResult()));
            row.put("authorityName", Dict.authorityName(s.getAuthority()));
            row.put("inspector", s.getInspector());
            row.put("surveyDate", s.getSurveyDate());
            surveyRows.add(row);
        }
        evidence.put("acceptSurveyCount", accepted.size());
        evidence.put("acceptedSurveys", surveyRows);

        List<Map<String, Object>> nodeRows = new ArrayList<>();
        for (ContractPaymentNode n : siblings) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nodeNo", n.getNodeNo());
            row.put("nodeName", n.getNodeName());
            row.put("seq", n.getSeq());
            row.put("ratio", n.getRatio());
            row.put("status", n.getStatus());
            row.put("statusName", Dict.paymentNodeStatusName(n.getStatus()));
            nodeRows.add(row);
        }
        evidence.put("nodes", nodeRows);
        evidence.put("evaluatedAt", LocalDateTime.now());
        return evidence;
    }

    // =================================================================
    // 报验详情 / 报验列表的结算联动视图
    // =================================================================

    /**
     * 报验单对合同结算的影响：该报验所属船的全部已进入结算合同（带轻量节点视图）。
     * 报验详情刷新后展示的结算状态 / 阻塞原因，与合同详情、船舶合同列表完全同源。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> surveySettlementImpact(Long surveyId, Survey survey) {
        Map<String, Object> data = new LinkedHashMap<>();
        Vessel vessel = survey.getVesselId() == null ? null
                : vesselRepository.findById(survey.getVesselId()).orElse(null);
        data.put("surveyNo", survey.getSurveyNo());
        data.put("surveyResult", survey.getResult());
        data.put("surveyResultName", Dict.surveyResultName(survey.getResult()));
        data.put("vessel", vessel);

        List<Map<String, Object>> contractViews = new ArrayList<>();
        if (vessel != null) {
            for (Contract contract : contractRepository.findByVesselIdOrderByIdAsc(vessel.getId())) {
                if (!isStarted(contract)) {
                    continue;
                }
                decorate(contract, false);
                List<ContractPaymentNode> nodes = nodeRepository.findByContractIdOrderBySeqAsc(contract.getId());
                List<Map<String, Object>> nodeRows = new ArrayList<>();
                boolean references = false;
                for (ContractPaymentNode node : nodes) {
                    Map<String, Object> view = nodeView(contract, vessel, nodes, node);
                    nodeRows.add(view);
                    if (survey.getSurveyNo().equals(view.get("settleRef"))
                            || survey.getSurveyNo().equals(view.get("currentSurveyNo"))) {
                        references = true;
                    }
                }
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("id", contract.getId());
                c.put("contractNo", contract.getContractNo());
                c.put("contractTypeName", contract.getContractTypeName());
                c.put("amount", contract.getAmount());
                c.put("status", contract.getStatus());
                c.put("statusName", contract.getStatusName());
                c.put("paymentState", contract.getPaymentState());
                c.put("paymentStateName", contract.getPaymentStateName());
                c.put("paidNodeCount", contract.getPaidNodeCount());
                c.put("totalNodeCount", contract.getTotalNodeCount());
                c.put("settledAmount", contract.getSettledAmount());
                c.put("blockers", contract.getBlockers());
                c.put("settlementWarnings", contract.getSettlementWarnings());
                c.put("nodes", nodeRows);
                c.put("referencesThisSurvey", references);
                contractViews.add(c);
            }
        }
        data.put("contracts", contractViews);
        return data;
    }

    // =================================================================
    // 私有工具
    // =================================================================

    private boolean isStarted(Contract contract) {
        String s = contract.getStatus();
        return "PERFORMING".equals(s) || "SETTLED".equals(s);
    }

    private String resolveState(Contract contract, int total, int paidCount) {
        if ("SETTLED".equals(contract.getStatus()) || (total > 0 && paidCount >= total)) {
            return "PAID";
        }
        if (paidCount > 0) {
            return "PARTIAL";
        }
        return "PENDING";
    }

    private String stateName(String state) {
        return switch (state) {
            case "NOT_STARTED" -> "未进入结算";
            case "PENDING" -> "待结算";
            case "PARTIAL" -> "部分已付";
            case "PAID" -> "全部已付";
            default -> state;
        };
    }

    /** 已付款节点的依据回退检查：只生成提示，绝不动节点与金额 */
    private List<String> driftWarnings(ContractPaymentNode node, Contract contract, Vessel vessel) {
        List<String> warnings = new ArrayList<>();
        if (node.getFrozenContractAmount() != null && contract.getAmount() != null
                && node.getFrozenContractAmount().compareTo(contract.getAmount()) != 0) {
            warnings.add("节点 " + node.getNodeNo() + " 付款时合同金额为 " + node.getFrozenContractAmount()
                    + " 万元，当前合同金额已调整为 " + contract.getAmount()
                    + " 万元；该笔已付款按付款时金额保留，不追溯调整");
        }
        if (vessel != null && node.getEvidenceJson() != null) {
            Map<String, Object> evidence = parseMap(node.getEvidenceJson());
            Object vesselSnap = evidence == null ? null : evidence.get("vessel");
            if (vesselSnap instanceof Map<?, ?> snap && snap.get("status") != null
                    && !snap.get("status").equals(vessel.getStatus())) {
                warnings.add("节点 " + node.getNodeNo() + " 付款时船舶状态为「"
                        + Dict.vesselStatusName(String.valueOf(snap.get("status"))) + "」，当前为「"
                        + Dict.vesselStatusName(vessel.getStatus()) + "」；已付款结果保留备查");
            }
        }
        return warnings;
    }

    private void applyEvaluationToNode(ContractPaymentNode node, Evaluation ev) {
        node.setLatestVerdict(ev.verdict);
        node.setLatestCheckedAt(LocalDateTime.now());
        node.setBlockersJson(toJson(ev.blockers));
        node.setAmountDue(ev.amountDue);
    }

    private boolean verdictChanged(ContractPaymentNode node, Evaluation ev) {
        if (!ev.verdict.equals(node.getLatestVerdict())) {
            return true;
        }
        List<Map<String, Object>> old = parseList(node.getBlockersJson());
        return old.size() != ev.blockers.size() || !toJson(old).equals(toJson(ev.blockers));
    }

    private List<Map<String, Object>> earliestBlockers(List<ContractPaymentNode> nodes) {
        return nodes.stream().filter(ContractPaymentNode::isPending)
                .min(java.util.Comparator.comparing(ContractPaymentNode::getSeq))
                .map(n -> parseList(n.getBlockersJson()))
                .orElse(List.of());
    }

    /** 应付金额 = 合同金额 × 比例%，保留两位小数四舍五入 */
    private BigDecimal amountOf(BigDecimal contractAmount, Integer ratio) {
        if (contractAmount == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        return contractAmount.multiply(BigDecimal.valueOf(ratio == null ? 0 : ratio))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private String nodeNo(String contractNo, int seq) {
        return "PM-" + contractNo + "-" + String.format("%02d", seq);
    }

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

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value;
    }

    private Map<String, Object> logView(ContractPaymentLog log) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", log.getId());
        view.put("contractId", log.getContractId());
        view.put("nodeId", log.getNodeId());
        view.put("nodeNo", log.getNodeNo());
        view.put("action", log.getAction());
        view.put("actionName", log.getActionName());
        view.put("sourceType", log.getSourceType());
        view.put("sourceTypeName", log.getSourceTypeName());
        view.put("sourceRef", log.getSourceRef());
        view.put("verdict", log.getVerdict());
        view.put("verdictName", log.getVerdictName());
        view.put("contractAmount", log.getContractAmount());
        view.put("acceptSurveyCount", log.getAcceptSurveyCount());
        view.put("vesselStatus", log.getVesselStatus());
        view.put("vesselStatusName", log.getVesselStatusName());
        view.put("operator", log.getOperator());
        view.put("note", log.getNote());
        view.put("createdAt", log.getCreatedAt());
        view.put("blockers", parseList(log.getBlockersJson()));
        view.put("evidence", parseMap(log.getEvidenceJson()));
        return view;
    }

    private List<Map<String, Object>> logViews(Long contractId) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ContractPaymentLog log : logRepository.findByContractIdOrderByIdAsc(contractId)) {
            list.add(logView(log));
        }
        return list;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("付款结算快照序列化失败", e);
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
            throw new IllegalStateException("付款结算快照解析失败", e);
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
            throw new IllegalStateException("付款结算阻塞项解析失败", e);
        }
    }

    /** 一次节点条件评估的产出 */
    private static final class Evaluation {
        private Contract contract;
        private Vessel vessel;
        private ContractPaymentNode node;
        private String verdict;
        private BigDecimal amountDue;
        private int acceptCount;
        private Survey latestSurvey;
        private String latestSurveyNo;
        private String latestSurveyResult;
        private boolean prevUnpaid;
        private final List<Map<String, Object>> blockers = new ArrayList<>();
        private Map<String, Object> evidence;

        boolean ready() {
            return VERDICT_READY.equals(verdict);
        }

        void addBlocker(String type, String typeName, String refNo, String message) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", type);
            item.put("typeName", typeName);
            item.put("refNo", refNo);
            item.put("message", message);
            blockers.add(item);
        }
    }
}
