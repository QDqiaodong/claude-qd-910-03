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
import com.shipyard.dock.domain.Contract;
import com.shipyard.dock.domain.ContractPaymentLog;
import com.shipyard.dock.domain.ContractPaymentNode;
import com.shipyard.dock.domain.Survey;
import com.shipyard.dock.domain.Vessel;
import com.shipyard.dock.repository.ContractPaymentLogRepository;
import com.shipyard.dock.repository.ContractPaymentNodeRepository;
import com.shipyard.dock.repository.ContractRepository;
import com.shipyard.dock.repository.SurveyRepository;
import com.shipyard.dock.repository.VesselRepository;

/**
 * 合同付款节点：合同进入结算流程后的分期付款结算联动。
 *
 * <p>节点生成：合同进入履行中时按模板生成五个节点（签订款 20% / 交验进度款 30% /
 * 下水进度款 20% / 交船尾款 20% / 质保金 10%），应付金额 = 合同金额 × 比例，
 * 最后一个节点按合同金额倒挤调平，保证各节点合计精确等于合同金额。</p>
 *
 * <p>结算条件（任一不满足都逐条指出阻塞项，未满足时节点停留在「待结算」）：
 * 签订款——合同进入履行中即满足；交验进度款——该船至少一条报验结论为合格或让步；
 * 下水进度款——船舶已下水或已交付；交船尾款——船舶已交付；
 * 质保金——船舶已交付且该船每个分段的最新报验结论都不是不合格。</p>
 *
 * <p>联动重算：报验补录 / 撤回 / 改判让步、船舶状态变化、合同金额变更都会触发重算，
 * 重算只刷新未结算节点的金额与状态并追加 RECALC 记录；已结算节点与历史确认记录不被覆盖。</p>
 *
 * <p>并发与一致性：确认结算先拿节点行级悲观锁再复核状态（{@code @Version} 双保险），
 * 多人同时确认或网络重试只允许一个有效结果；确认把「节点置为已结算 + 合同已结算金额累加
 * + 确认记录」放在同一事务里，任一失败整体回滚，不留金额已扣但节点未更新的半完成状态。</p>
 */
@Service
public class ContractPaymentService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_DUE = "DUE";
    private static final String STATUS_SETTLED = "SETTLED";

    private final ContractPaymentNodeRepository nodeRepository;
    private final ContractPaymentLogRepository logRepository;
    private final ContractRepository contractRepository;
    private final VesselRepository vesselRepository;
    private final SurveyRepository surveyRepository;
    private final ObjectMapper objectMapper;

    public ContractPaymentService(ContractPaymentNodeRepository nodeRepository,
            ContractPaymentLogRepository logRepository, ContractRepository contractRepository,
            VesselRepository vesselRepository, SurveyRepository surveyRepository,
            ObjectMapper objectMapper) {
        this.nodeRepository = nodeRepository;
        this.logRepository = logRepository;
        this.contractRepository = contractRepository;
        this.vesselRepository = vesselRepository;
        this.surveyRepository = surveyRepository;
        this.objectMapper = objectMapper;
    }

    public boolean hasNodes(Long contractId) {
        return nodeRepository.existsByContractId(contractId);
    }

    /** 已结算金额合计（已完成的付款节点），合同金额变更的下限 */
    public BigDecimal settledSum(Long contractId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ContractPaymentNode node : nodeRepository.findByContractIdOrderBySeqAsc(contractId)) {
            if (STATUS_SETTLED.equals(node.getStatus()) && node.getSettledAmount() != null) {
                sum = sum.add(node.getSettledAmount());
            }
        }
        return sum;
    }

    // ---------------- 节点生成 ----------------

    /**
     * 合同进入履行中时按模板生成全部付款节点（幂等：已生成则跳过）。
     * 生成时即以当前合同金额、报验结论与船舶状态计算各节点状态，并逐节点留存 GENERATE 记录。
     */
    @Transactional
    public void ensureNodes(Contract contract) {
        if (contract.getId() == null || nodeRepository.existsByContractId(contract.getId())) {
            return;
        }
        if (!"PERFORMING".equals(contract.getStatus()) && !"SETTLED".equals(contract.getStatus())) {
            return;
        }
        Vessel vessel = loadVessel(contract.getVesselId());
        List<Survey> surveys = loadSurveys(contract.getVesselId());
        LocalDateTime now = LocalDateTime.now();
        BigDecimal allocated = BigDecimal.ZERO;
        int seq = 0;
        for (String code : Dict.PAYMENT_NODE_FLOW) {
            seq++;
            ContractPaymentNode node = new ContractPaymentNode();
            node.setContractId(contract.getId());
            node.setContractNo(contract.getContractNo());
            node.setVesselId(contract.getVesselId());
            node.setNodeCode(code);
            node.setNodeName(Dict.paymentNodeName(code));
            node.setSeq(seq);
            node.setRatio(Dict.paymentNodeRatio(code));
            BigDecimal amount = seq < Dict.PAYMENT_NODE_FLOW.size()
                    ? scale(contract.getAmount().multiply(node.getRatio()))
                    : contract.getAmount().subtract(allocated);
            allocated = allocated.add(amount);
            node.setAmount(amount);
            EvalResult eval = evaluate(code, contract, vessel, surveys);
            node.setStatus(eval.met ? STATUS_DUE : STATUS_PENDING);
            node.setBlockersJson(toJson(eval.blockers));
            node.setCreatedAt(now);
            node.setUpdatedAt(now);
            nodeRepository.save(node);
            appendLog(node, contract, "GENERATE", contract.getAmount(), amount, surveys, vessel,
                    eval.blockers, node.getStatus(), "合同进入履行中", "按模板生成分期付款节点");
        }
    }

    // ---------------- 联动重算 ----------------

    /**
     * 重算某合同全部未结算节点：报验补录 / 撤回 / 改判让步、船舶状态变化、合同金额变更时触发。
     * 只刷新未结算节点的金额、状态与阻塞明细；已结算节点是历史，不被刷新覆盖。
     * 有变化才追加 RECALC 记录（记录当时的金额、报验结论与船舶状态），无变化不留噪音。
     */
    @Transactional
    public void recalcForContract(Long contractId, String trigger) {
        Contract contract = contractRepository.findById(contractId).orElse(null);
        if (contract == null || !"PERFORMING".equals(contract.getStatus())) {
            return; // 未进结算流程没有节点；已结算合同整体冻结
        }
        ensureNodes(contract);
        Vessel vessel = loadVessel(contract.getVesselId());
        List<Survey> surveys = loadSurveys(contract.getVesselId());
        List<ContractPaymentNode> nodes = nodeRepository.findByContractIdOrderBySeqAsc(contractId);
        BigDecimal allocated = settledSum(contractId);
        LocalDateTime now = LocalDateTime.now();
        List<ContractPaymentNode> open = new ArrayList<>();
        for (ContractPaymentNode node : nodes) {
            if (!STATUS_SETTLED.equals(node.getStatus())) {
                open.add(node);
            }
        }
        for (int i = 0; i < open.size(); i++) {
            ContractPaymentNode node = open.get(i);
            // 最后一个未结算节点倒挤调平：已结算合计 + 未结算合计始终等于合同金额
            BigDecimal newAmount = i < open.size() - 1
                    ? scale(contract.getAmount().multiply(node.getRatio()))
                    : contract.getAmount().subtract(allocated);
            allocated = allocated.add(newAmount);
            EvalResult eval = evaluate(node.getNodeCode(), contract, vessel, surveys);
            String newStatus = eval.met ? STATUS_DUE : STATUS_PENDING;
            String newBlockers = toJson(eval.blockers);
            boolean vesselChanged = !java.util.Objects.equals(node.getVesselId(), contract.getVesselId());
            boolean changed = vesselChanged
                    || newAmount.compareTo(node.getAmount()) != 0
                    || !newStatus.equals(node.getStatus())
                    || !newBlockers.equals(node.getBlockersJson());
            if (!changed) {
                continue;
            }
            StringBuilder note = new StringBuilder("联动重算：");
            if (newAmount.compareTo(node.getAmount()) != 0) {
                note.append("金额 ").append(node.getAmount()).append(" → ").append(newAmount).append("；");
            }
            if (!newStatus.equals(node.getStatus())) {
                note.append("状态 ").append(Dict.paymentNodeStatusName(node.getStatus()))
                        .append(" → ").append(Dict.paymentNodeStatusName(newStatus)).append("；");
            }
            if (note.toString().equals("联动重算：")) {
                note.append("阻塞明细随最新数据刷新");
            }
            node.setVesselId(contract.getVesselId());
            node.setAmount(newAmount);
            node.setStatus(newStatus);
            node.setBlockersJson(newBlockers);
            node.setUpdatedAt(now);
            nodeRepository.save(node);
            appendLog(node, contract, "RECALC", contract.getAmount(), newAmount, surveys, vessel,
                    eval.blockers, newStatus, trigger, trimNote(note));
        }
    }

    /** 某船全部履行中合同的付款节点联动重算（报验 / 船舶状态变化的统一入口） */
    @Transactional
    public void recalcForVessel(Long vesselId, String trigger) {
        if (vesselId == null) {
            return;
        }
        for (Contract contract : contractRepository.findByVesselIdOrderByIdAsc(vesselId)) {
            if ("PERFORMING".equals(contract.getStatus())) {
                recalcForContract(contract.getId(), trigger);
            }
        }
    }

    // ---------------- 确认结算 ----------------

    /**
     * 确认结算一个付款节点。
     *
     * <p>同一事务内依次完成：行级锁定节点与合同 → 复核节点状态（已结算则重复确认无效）→
     * 以最新数据复算结算条件（报验被撤回 / 改判、船舶状态回退时整体失败并逐条列出阻塞项）→
     * 节点置为已结算并记录操作者与时间 → 合同已结算金额累加 → 追加确认记录（当时快照）。
     * 任一环节失败整体回滚，不留金额已扣但节点未更新的半完成状态。</p>
     */
    @Transactional
    public Map<String, Object> confirmNode(Long nodeId, String operator) {
        if (!StringUtils.hasText(operator)) {
            throw new BizException("请填写操作人");
        }
        ContractPaymentNode node = nodeRepository.findByIdForUpdate(nodeId)
                .orElseThrow(() -> new BizException("付款节点不存在：" + nodeId));
        // 行锁内复核状态：多人同时确认 / 网络重试时，后到者在这里被拦下，只允许一个有效结果
        if (STATUS_SETTLED.equals(node.getStatus())) {
            throw new BizException("付款节点「" + node.getNodeName() + "」已由 " + node.getSettledBy()
                    + " 于 " + node.getSettledAt() + " 确认结算，重复确认无效");
        }
        Contract contract = contractRepository.findByIdForUpdate(node.getContractId())
                .orElseThrow(() -> new BizException("合同不存在：" + node.getContractId()));
        if ("SETTLED".equals(contract.getStatus())) {
            throw new BizException("合同 " + contract.getContractNo() + " 已整体结算，付款节点不可再变动");
        }
        Vessel vessel = loadVessel(contract.getVesselId());
        List<Survey> surveys = loadSurveys(contract.getVesselId());
        // 确认前以最新数据复算：确认期间报验被撤回 / 改判、船舶状态回退时，本次确认整体失败
        EvalResult eval = evaluate(node.getNodeCode(), contract, vessel, surveys);
        if (!eval.met) {
            throw new BizException("付款节点「" + node.getNodeName() + "」当前不满足结算条件，停留在待结算："
                    + summarize(eval.blockers));
        }
        LocalDateTime now = LocalDateTime.now();
        node.setStatus(STATUS_SETTLED);
        node.setSettledAmount(node.getAmount());
        node.setSettledBy(operator);
        node.setSettledAt(now);
        node.setBlockersJson("[]");
        node.setUpdatedAt(now);
        nodeRepository.save(node);
        // 同一事务内累计合同已结算金额：节点状态与金额记账同生共死
        BigDecimal paid = contract.getPaidAmount() == null ? BigDecimal.ZERO : contract.getPaidAmount();
        contract.setPaidAmount(paid.add(node.getAmount()));
        contractRepository.save(contract);
        appendLog(node, contract, "CONFIRM", contract.getAmount(), node.getAmount(), surveys, vessel,
                List.of(), STATUS_SETTLED, operator,
                "确认结算 " + node.getAmount() + " 万元，已记入合同已结算金额");
        return detail(contract.getId());
    }

    // ---------------- 合同整体结算联动 ----------------

    /**
     * 合同推进到「已结算」的前置校验：全部付款节点必须已结算，且结算合计等于合同金额。
     * 未满足时逐条列出未结算节点与当前阻塞项（实时复算，不依赖可能滞后的存储值）。
     */
    public void requireAllSettled(Contract contract) {
        List<ContractPaymentNode> nodes = nodeRepository.findByContractIdOrderBySeqAsc(contract.getId());
        Vessel vessel = loadVessel(contract.getVesselId());
        List<Survey> surveys = loadSurveys(contract.getVesselId());
        List<String> unsettled = new ArrayList<>();
        BigDecimal settledAmount = BigDecimal.ZERO;
        for (ContractPaymentNode node : nodes) {
            if (STATUS_SETTLED.equals(node.getStatus())) {
                settledAmount = settledAmount.add(node.getSettledAmount() == null
                        ? BigDecimal.ZERO : node.getSettledAmount());
                continue;
            }
            EvalResult eval = evaluate(node.getNodeCode(), contract, vessel, surveys);
            String reason = eval.met ? "条件已满足，待财务确认结算"
                    : "阻塞：" + summarize(eval.blockers);
            unsettled.add("「" + node.getNodeName() + "」（" + Dict.paymentNodeStatusName(node.getStatus())
                    + "，" + reason + "）");
        }
        if (!unsettled.isEmpty()) {
            throw new BizException("合同 " + contract.getContractNo() + " 还有 " + unsettled.size()
                    + " 个付款节点未结算，不能整体结算：" + String.join("；", unsettled));
        }
        if (settledAmount.compareTo(contract.getAmount()) != 0) {
            throw new BizException("合同 " + contract.getContractNo() + " 付款节点结算合计 " + settledAmount
                    + " 万元与合同金额 " + contract.getAmount() + " 万元不一致，请核对后再结算");
        }
    }

    // ---------------- 视图：详情 / 摘要（三处同源） ----------------

    /**
     * 合同付款详情：合同 + 节点（带实时阻塞项）+ 汇总 + 全部生成 / 重算 / 确认记录。
     * 合同详情、船舶合同列表与报验详情共用同一份实时评估，刷新后口径一致。
     */
    public Map<String, Object> detail(Long contractId) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new BizException("合同不存在：" + contractId));
        Map<String, Object> data = new LinkedHashMap<>();
        attachSummary(List.of(contract));
        data.put("contract", contract);
        List<Map<String, Object>> nodeViews = nodeViews(contract);
        data.put("nodes", nodeViews);
        data.put("summary", summaryMap(contract, nodeViews));
        List<Map<String, Object>> logs = new ArrayList<>();
        for (ContractPaymentLog log : logRepository.findByContractIdOrderByIdAsc(contractId)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", log.getId());
            view.put("nodeId", log.getNodeId());
            view.put("nodeCode", log.getNodeCode());
            view.put("nodeName", log.getNodeName());
            view.put("action", log.getAction());
            view.put("actionName", log.getActionName());
            view.put("contractAmount", log.getContractAmount());
            view.put("nodeAmount", log.getNodeAmount());
            view.put("surveySummary", parseMap(log.getSurveySummaryJson()));
            view.put("vesselStatus", log.getVesselStatus());
            view.put("vesselStatusName", log.getVesselStatusName());
            view.put("blockers", parseList(log.getBlockersJson()));
            view.put("result", log.getResult());
            view.put("resultName", log.getResultName());
            view.put("operator", log.getOperator());
            view.put("note", log.getNote());
            view.put("createdAt", log.getCreatedAt());
            logs.add(view);
        }
        data.put("logs", logs);
        return data;
    }

    /** 给一批合同挂上付款结算摘要（列表 / 船舶详情 / 报验详情共用，保证同源） */
    public void attachSummary(List<Contract> contracts) {
        for (Contract contract : contracts) {
            contract.setPaymentSummary(summaryOf(contract));
        }
    }

    private Map<String, Object> summaryOf(Contract contract) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("paidAmount", contract.getPaidAmount() == null ? BigDecimal.ZERO : contract.getPaidAmount());
        if (!nodeRepository.existsByContractId(contract.getId())) {
            summary.put("generated", false);
            summary.put("totalNodes", 0);
            summary.put("settledNodes", 0);
            summary.put("dueNodes", 0);
            summary.put("pendingNodes", 0);
            summary.put("allSettled", false);
            summary.put("blockers", List.of());
            return summary;
        }
        List<Map<String, Object>> nodeViews = nodeViews(contract);
        return summaryMap(contract, nodeViews);
    }

    /** 节点视图：未结算节点带实时阻塞项（与确认时同一套评估逻辑） */
    private List<Map<String, Object>> nodeViews(Contract contract) {
        Vessel vessel = loadVessel(contract.getVesselId());
        List<Survey> surveys = loadSurveys(contract.getVesselId());
        List<Map<String, Object>> views = new ArrayList<>();
        for (ContractPaymentNode node : nodeRepository.findByContractIdOrderBySeqAsc(contract.getId())) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", node.getId());
            view.put("nodeCode", node.getNodeCode());
            view.put("nodeName", node.getNodeName());
            view.put("seq", node.getSeq());
            view.put("ratio", node.getRatio());
            view.put("amount", node.getAmount());
            view.put("status", node.getStatus());
            view.put("statusName", node.getStatusName());
            List<Map<String, Object>> blockers = STATUS_SETTLED.equals(node.getStatus())
                    ? List.of()
                    : evaluate(node.getNodeCode(), contract, vessel, surveys).blockers;
            view.put("blockers", blockers);
            view.put("settledAmount", node.getSettledAmount());
            view.put("settledBy", node.getSettledBy());
            view.put("settledAt", node.getSettledAt());
            views.add(view);
        }
        return views;
    }

    private Map<String, Object> summaryMap(Contract contract, List<Map<String, Object>> nodeViews) {
        Map<String, Object> summary = new LinkedHashMap<>();
        int settled = 0;
        int due = 0;
        int pending = 0;
        List<Map<String, Object>> blockers = new ArrayList<>();
        for (Map<String, Object> view : nodeViews) {
            String status = (String) view.get("status");
            if (STATUS_SETTLED.equals(status)) {
                settled++;
            } else if (STATUS_DUE.equals(status)) {
                due++;
            } else {
                pending++;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nodeBlockers = (List<Map<String, Object>>) view.get("blockers");
            for (Map<String, Object> blocker : nodeBlockers) {
                Map<String, Object> item = new LinkedHashMap<>(blocker);
                item.put("nodeName", view.get("nodeName"));
                blockers.add(item);
            }
        }
        summary.put("generated", !nodeViews.isEmpty());
        summary.put("totalNodes", nodeViews.size());
        summary.put("settledNodes", settled);
        summary.put("dueNodes", due);
        summary.put("pendingNodes", pending);
        summary.put("allSettled", !nodeViews.isEmpty() && settled == nodeViews.size());
        summary.put("paidAmount", contract.getPaidAmount() == null ? BigDecimal.ZERO : contract.getPaidAmount());
        summary.put("blockers", blockers);
        return summary;
    }

    // ---------------- 结算条件评估（生成 / 重算 / 确认 / 展示共用同一套口径） ----------------

    private EvalResult evaluate(String nodeCode, Contract contract, Vessel vessel, List<Survey> surveys) {
        EvalResult eval = new EvalResult();
        String vesselNo = vessel == null
                ? (contract.getVesselNo() == null ? "-" : contract.getVesselNo())
                : vessel.getVesselNo();
        String vesselStatus = vessel == null ? null : vessel.getStatus();
        switch (nodeCode) {
            case "SIGN":
                // 合同进入履行中即满足（节点只在履行中生成）
                break;
            case "SURVEY": {
                boolean valid = surveys.stream().anyMatch(s ->
                        "PASS".equals(s.getResult()) || "CONCESSION".equals(s.getResult()));
                if (!valid) {
                    eval.addBlocker("NO_VALID_SURVEY", "报验结论", vesselNo,
                            "该船还没有任何一条合格或让步的报验结论，交验进度款未到结算条件");
                }
                break;
            }
            case "LAUNCH": {
                if (vessel == null) {
                    eval.addBlocker("NO_VESSEL", "船舶状态", vesselNo, "合同未绑定船舶，无法核对船舶状态");
                } else if (!"LAUNCHED".equals(vesselStatus) && !"DELIVERED".equals(vesselStatus)) {
                    eval.addBlocker("VESSEL_NOT_LAUNCHED", "船舶状态", vesselNo,
                            "船舶当前状态为「" + Dict.vesselStatusName(vesselStatus) + "」，尚未下水，下水进度款未到结算条件");
                }
                break;
            }
            case "DELIVERY": {
                if (vessel == null) {
                    eval.addBlocker("NO_VESSEL", "船舶状态", vesselNo, "合同未绑定船舶，无法核对船舶状态");
                } else if (!"DELIVERED".equals(vesselStatus)) {
                    eval.addBlocker("VESSEL_NOT_DELIVERED", "船舶状态", vesselNo,
                            "船舶当前状态为「" + Dict.vesselStatusName(vesselStatus) + "」，尚未交付，交船尾款未到结算条件");
                }
                break;
            }
            case "WARRANTY": {
                if (vessel == null) {
                    eval.addBlocker("NO_VESSEL", "船舶状态", vesselNo, "合同未绑定船舶，无法核对船舶状态");
                } else if (!"DELIVERED".equals(vesselStatus)) {
                    eval.addBlocker("VESSEL_NOT_DELIVERED", "船舶状态", vesselNo,
                            "船舶当前状态为「" + Dict.vesselStatusName(vesselStatus) + "」，尚未交付，质保金未到结算条件");
                }
                if (surveys.isEmpty()) {
                    eval.addBlocker("NO_SURVEY_RECORD", "报验结论", vesselNo,
                            "该船尚无报验记录，无法确认全部报验结论为合格或让步，质保金未到结算条件");
                } else {
                    for (Survey latest : latestByBlock(surveys)) {
                        if ("FAIL".equals(latest.getResult())) {
                            eval.addBlocker("LATEST_SURVEY_FAIL", "报验结论", latest.getSurveyNo(),
                                    "分段 " + latest.getBlockCode() + " 最新报验 " + latest.getSurveyNo()
                                            + " 结论为「不合格」，质保金需全部报验结论为合格或让步");
                        }
                    }
                }
                break;
            }
            default:
                eval.addBlocker("UNKNOWN_NODE", "付款节点", nodeCode, "未知付款节点：" + nodeCode);
        }
        eval.met = eval.blockers.isEmpty();
        return eval;
    }

    /** 每个分段的最新报验结论（surveys 按报验时间倒序传入，先出现的即最新） */
    private List<Survey> latestByBlock(List<Survey> surveys) {
        Map<Long, Survey> latest = new LinkedHashMap<>();
        for (Survey survey : surveys) {
            latest.putIfAbsent(survey.getBlockId(), survey);
        }
        return new ArrayList<>(latest.values());
    }

    /** 报验结论快照：计数 + 各分段最新结论，历史记录据此解释当时使用的报验结论 */
    private Map<String, Object> surveySummary(List<Survey> surveys, Vessel vessel) {
        Map<String, Object> summary = new LinkedHashMap<>();
        int pass = 0;
        int concession = 0;
        int fail = 0;
        for (Survey survey : surveys) {
            if ("PASS".equals(survey.getResult())) {
                pass++;
            } else if ("CONCESSION".equals(survey.getResult())) {
                concession++;
            } else if ("FAIL".equals(survey.getResult())) {
                fail++;
            }
        }
        List<Map<String, Object>> latest = new ArrayList<>();
        for (Survey survey : latestByBlock(surveys)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("blockCode", survey.getBlockCode());
            item.put("surveyNo", survey.getSurveyNo());
            item.put("result", survey.getResult());
            item.put("resultName", Dict.surveyResultName(survey.getResult()));
            latest.add(item);
        }
        summary.put("vesselNo", vessel == null ? null : vessel.getVesselNo());
        summary.put("total", surveys.size());
        summary.put("pass", pass);
        summary.put("concession", concession);
        summary.put("fail", fail);
        summary.put("latest", latest);
        return summary;
    }

    // ---------------- 私有工具 ----------------

    private Vessel loadVessel(Long vesselId) {
        return vesselId == null ? null : vesselRepository.findById(vesselId).orElse(null);
    }

    private List<Survey> loadSurveys(Long vesselId) {
        return vesselId == null ? List.of() : surveyRepository.findByVesselIdOrderBySurveyDateDesc(vesselId);
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private void appendLog(ContractPaymentNode node, Contract contract, String action,
            BigDecimal contractAmount, BigDecimal nodeAmount, List<Survey> surveys, Vessel vessel,
            List<Map<String, Object>> blockers, String result, String operator, String note) {
        ContractPaymentLog log = new ContractPaymentLog();
        log.setNodeId(node.getId());
        log.setContractId(contract.getId());
        log.setContractNo(contract.getContractNo());
        log.setNodeCode(node.getNodeCode());
        log.setNodeName(node.getNodeName());
        log.setAction(action);
        log.setContractAmount(contractAmount);
        log.setNodeAmount(nodeAmount);
        log.setSurveySummaryJson(toJson(surveySummary(surveys, vessel)));
        log.setVesselStatus(vessel == null ? null : vessel.getStatus());
        log.setBlockersJson(toJson(blockers));
        log.setResult(result);
        log.setOperator(operator);
        log.setNote(note);
        log.setCreatedAt(LocalDateTime.now());
        logRepository.save(log);
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

    private String trimNote(StringBuilder note) {
        String text = note.toString();
        return text.endsWith("；") ? text.substring(0, text.length() - 1) : text;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("付款节点快照序列化失败", e);
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
            throw new IllegalStateException("付款节点快照解析失败", e);
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
            throw new IllegalStateException("付款节点快照解析失败", e);
        }
    }

    /** 一次节点评估的产出：是否满足 + 逐条阻塞项 */
    private static final class EvalResult {
        private boolean met;
        private final List<Map<String, Object>> blockers = new ArrayList<>();

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
