package com.shipyard.dock.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
import com.shipyard.dock.domain.PunchItem;
import com.shipyard.dock.domain.Survey;
import com.shipyard.dock.dto.PunchItemRequest;
import com.shipyard.dock.dto.SurveyRequest;
import com.shipyard.dock.generation.CodeSequenceService;
import com.shipyard.dock.repository.BlockRepository;
import com.shipyard.dock.repository.ContractRepository;
import com.shipyard.dock.repository.PunchItemRepository;
import com.shipyard.dock.repository.SurveyRepository;

/**
 * 模块五：报验与遗留项。
 *
 * <p>规则：① 报验单号唯一（由 {@code @SurveyNo} + Hibernate {@code @ValueGenerationType} 生成）；
 * ② 结论只能 合格 / 让步 / 不合格；③ 判不合格必须至少开一条遗留项；
 * ④ 遗留项闭环前，所属分段不允许置为已交验；
 * ⑤ 报验补录 / 撤回 / 改判（含改为让步接收）都会触发关联合同付款节点的结算状态重算，
 * 重算留痕且不影响已完成的付款节点。</p>
 */
@Service
public class SurveyService {

    /** 遗留项编号序号键 */
    private static final String PUNCH_SEQ_KEY = "PUNCH_NO";

    private final SurveyRepository surveyRepository;
    private final PunchItemRepository punchItemRepository;
    private final BlockRepository blockRepository;
    private final ContractRepository contractRepository;
    private final ContractPaymentService paymentService;

    public SurveyService(SurveyRepository surveyRepository, PunchItemRepository punchItemRepository,
            BlockRepository blockRepository, ContractRepository contractRepository,
            ContractPaymentService paymentService) {
        this.surveyRepository = surveyRepository;
        this.punchItemRepository = punchItemRepository;
        this.blockRepository = blockRepository;
        this.contractRepository = contractRepository;
        this.paymentService = paymentService;
    }

    public List<Survey> list() {
        return surveyRepository.findAllByOrderBySurveyDateDesc();
    }

    public Survey get(Long id) {
        return surveyRepository.findById(id)
                .orElseThrow(() -> new BizException("报验单不存在：" + id));
    }

    @Transactional
    public Survey create(SurveyRequest request) {
        if (request.getBlockId() == null) {
            throw new BizException("报验单必须指定被检分段");
        }
        Block block = blockRepository.findById(request.getBlockId())
                .orElseThrow(() -> new BizException("分段不存在：" + request.getBlockId()));
        if (!Dict.isValidSurveyItem(request.getSurveyItem())) {
            throw new BizException("报验项目非法（焊缝探伤 / 主尺度 / 涂装膜厚 / 舾装完整性 / 舱室密性 五选一）："
                    + request.getSurveyItem());
        }
        if (!Dict.isValidSurveyResult(request.getResult())) {
            throw new BizException("报验结论只能三选一：合格 / 让步 / 不合格，当前为：" + request.getResult());
        }
        if (request.getAuthority() != null && !Dict.isValidAuthority(request.getAuthority())) {
            throw new BizException("检验机构非法（CCS / ABS / LR / DNV / BV / 船东代表）：" + request.getAuthority());
        }
        // 判不合格必须至少开一条遗留项
        if ("FAIL".equals(request.getResult())) {
            if (request.getPunchItems() == null || request.getPunchItems().isEmpty()) {
                throw new BizException("报验判定为「不合格」，必须至少开一条遗留项");
            }
            for (PunchItemRequest item : request.getPunchItems()) {
                if (!StringUtils.hasText(item.getTitle())) {
                    throw new BizException("遗留项标题不能为空");
                }
            }
        }

        Survey survey = new Survey();
        survey.setBlockId(block.getId());
        survey.setBlockCode(block.getBlockCode());
        survey.setVesselId(block.getVesselId());
        survey.setVesselNo(block.getVesselNo());
        survey.setSurveyItem(request.getSurveyItem());
        survey.setSurveyDate(request.getSurveyDate() == null ? LocalDateTime.now() : request.getSurveyDate());
        survey.setAuthority(request.getAuthority());
        survey.setInspector(request.getInspector());
        survey.setResult(request.getResult());
        survey.setRemark(request.getRemark());
        survey.setCreatedAt(LocalDateTime.now());
        Survey saved = surveyRepository.save(survey);

        if (request.getPunchItems() != null) {
            for (PunchItemRequest item : request.getPunchItems()) {
                PunchItem punch = new PunchItem();
                punch.setSurveyId(saved.getId());
                punch.setSurveyNo(saved.getSurveyNo());
                punch.setBlockId(saved.getBlockId());
                punch.setBlockCode(saved.getBlockCode());
                punch.setTitle(item.getTitle());
                punch.setDescription(item.getDescription());
                punch.setSeverity(item.getSeverity() == null ? "MINOR" : item.getSeverity());
                punch.setDueDate(item.getDueDate());
                punch.setOwner(item.getOwner());
                punch.setStatus("OPEN");
                punch.setCreatedAt(LocalDateTime.now());
                punch.setPunchNo(nextPunchNo(saved.getSurveyNo()));
                punchItemRepository.save(punch);
            }
        }
        // 报验补录：触发该船履行中合同的付款节点结算状态重算（同事务，留痕）
        paymentService.recalcForVessel(saved.getVesselId(),
                "报验补录 " + saved.getSurveyNo() + "（" + Dict.surveyResultName(saved.getResult()) + "）");
        return saved;
    }

    @Transactional
    public Survey update(Long id, SurveyRequest request) {
        Survey survey = get(id);
        if (request.getSurveyItem() != null && !Dict.isValidSurveyItem(request.getSurveyItem())) {
            throw new BizException("报验项目非法（焊缝探伤 / 主尺度 / 涂装膜厚 / 舾装完整性 / 舱室密性 五选一）："
                    + request.getSurveyItem());
        }
        if (request.getAuthority() != null && !Dict.isValidAuthority(request.getAuthority())) {
            throw new BizException("检验机构非法（CCS / ABS / LR / DNV / BV / 船东代表）：" + request.getAuthority());
        }
        if (request.getResult() != null) {
            if (!Dict.isValidSurveyResult(request.getResult())) {
                throw new BizException("报验结论只能三选一：合格 / 让步 / 不合格，当前为：" + request.getResult());
            }
            if ("FAIL".equals(request.getResult())
                    && punchItemRepository.findBySurveyIdOrderByIdAsc(id).isEmpty()
                    && (request.getPunchItems() == null || request.getPunchItems().isEmpty())) {
                throw new BizException("报验判定为「不合格」，必须至少开一条遗留项");
            }
        }
        String oldResult = survey.getResult();
        applyPartial(survey, request);
        Survey saved = surveyRepository.save(survey);
        if (request.getPunchItems() != null && !request.getPunchItems().isEmpty()) {
            for (PunchItemRequest item : request.getPunchItems()) {
                if (!StringUtils.hasText(item.getTitle())) {
                    continue;
                }
                PunchItem punch = new PunchItem();
                punch.setSurveyId(saved.getId());
                punch.setSurveyNo(saved.getSurveyNo());
                punch.setBlockId(saved.getBlockId());
                punch.setBlockCode(saved.getBlockCode());
                punch.setTitle(item.getTitle());
                punch.setDescription(item.getDescription());
                punch.setSeverity(item.getSeverity() == null ? "MINOR" : item.getSeverity());
                punch.setDueDate(item.getDueDate());
                punch.setOwner(item.getOwner());
                punch.setStatus("OPEN");
                punch.setCreatedAt(LocalDateTime.now());
                punch.setPunchNo(nextPunchNo(saved.getSurveyNo()));
                punchItemRepository.save(punch);
            }
        }
        // 报验结论变更（含改为让步接收）：触发该船履行中合同的付款节点结算状态重算（同事务，留痕）
        String trigger = "报验更新 " + saved.getSurveyNo();
        if (request.getResult() != null && !request.getResult().equals(oldResult)) {
            trigger = "报验结论变更 " + saved.getSurveyNo() + "（" + Dict.surveyResultName(oldResult)
                    + " → " + Dict.surveyResultName(saved.getResult()) + "）";
        }
        paymentService.recalcForVessel(saved.getVesselId(), trigger);
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        Survey survey = get(id);
        List<PunchItem> items = punchItemRepository.findBySurveyIdOrderByIdAsc(id);
        if (!items.isEmpty()) {
            throw new BizException("报验单 " + survey.getSurveyNo() + " 下还有 " + items.size()
                    + " 条遗留项，请先处理遗留项再删除");
        }
        Long vesselId = survey.getVesselId();
        String surveyNo = survey.getSurveyNo();
        surveyRepository.deleteById(id);
        // 报验撤回：触发该船履行中合同的付款节点结算状态重算（同事务，留痕）
        paymentService.recalcForVessel(vesselId, "报验撤回 " + surveyNo);
    }

    // ---------------- 遗留项 ----------------

    public List<PunchItem> punchList() {
        return punchItemRepository.findAllByOrderByIdAsc();
    }

    public PunchItem getPunch(Long id) {
        return punchItemRepository.findById(id)
                .orElseThrow(() -> new BizException("遗留项不存在：" + id));
    }

    @Transactional
    public PunchItem createPunch(PunchItemRequest request) {
        if (!StringUtils.hasText(request.getTitle())) {
            throw new BizException("遗留项标题不能为空");
        }
        if (request.getBlockId() == null) {
            throw new BizException("遗留项必须挂到一个分段上");
        }
        Block block = blockRepository.findById(request.getBlockId())
                .orElseThrow(() -> new BizException("分段不存在：" + request.getBlockId()));
        if (request.getSeverity() != null && !Dict.isValidPunchSeverity(request.getSeverity())) {
            throw new BizException("严重度非法（一般 / 重要 / 严重 三选一）：" + request.getSeverity());
        }
        PunchItem punch = new PunchItem();
        punch.setSurveyId(request.getSurveyId());
        if (request.getSurveyId() != null) {
            punch.setSurveyNo(get(request.getSurveyId()).getSurveyNo());
        }
        punch.setBlockId(block.getId());
        punch.setBlockCode(block.getBlockCode());
        punch.setTitle(request.getTitle());
        punch.setDescription(request.getDescription());
        punch.setSeverity(request.getSeverity() == null ? "MINOR" : request.getSeverity());
        punch.setDueDate(request.getDueDate());
        punch.setOwner(request.getOwner());
        punch.setStatus(request.getStatus() == null ? "OPEN" : request.getStatus());
        if (!Dict.isValidPunchStatus(punch.getStatus())) {
            throw new BizException("遗留项状态非法：" + punch.getStatus());
        }
        punch.setCreatedAt(LocalDateTime.now());
        punch.setPunchNo(nextPunchNo(punch.getSurveyNo()));
        return punchItemRepository.save(punch);
    }

    @Transactional
    public PunchItem updatePunch(Long id, PunchItemRequest request) {
        PunchItem punch = getPunch(id);
        if (request.getSeverity() != null && !Dict.isValidPunchSeverity(request.getSeverity())) {
            throw new BizException("严重度非法（一般 / 重要 / 严重 三选一）：" + request.getSeverity());
        }
        if (request.getStatus() != null && !request.getStatus().equals(punch.getStatus())
                && !Dict.canTransferPunch(punch.getStatus(), request.getStatus())) {
            throw new BizException("遗留项状态不能从 " + Dict.punchStatusName(punch.getStatus())
                    + " 变为 " + Dict.punchStatusName(request.getStatus()) + "（开启 → 整改中 → 已闭环）");
        }
        ifNotNull(request.getTitle(), punch::setTitle);
        ifNotNull(request.getDescription(), punch::setDescription);
        ifNotNull(request.getSeverity(), punch::setSeverity);
        ifNotNull(request.getDueDate(), punch::setDueDate);
        ifNotNull(request.getOwner(), punch::setOwner);
        if (request.getStatus() != null) {
            punch.setStatus(request.getStatus());
            if ("CLOSED".equals(request.getStatus()) && punch.getClosedAt() == null) {
                punch.setClosedAt(LocalDateTime.now());
            }
        }
        return punchItemRepository.save(punch);
    }

    /** 推进遗留项：开启 → 整改中 → 已闭环 */
    @Transactional
    public PunchItem advancePunch(Long id) {
        PunchItem punch = getPunch(id);
        if ("CLOSED".equals(punch.getStatus())) {
            throw new BizException("遗留项 " + punch.getPunchNo() + " 已闭环，无需再推进");
        }
        String next = "OPEN".equals(punch.getStatus()) ? "RECTIFYING" : "CLOSED";
        punch.setStatus(next);
        if ("CLOSED".equals(next) && punch.getClosedAt() == null) {
            punch.setClosedAt(LocalDateTime.now());
        }
        return punchItemRepository.save(punch);
    }

    @Transactional
    public void deletePunch(Long id) {
        PunchItem punch = getPunch(id);
        if ("CLOSED".equals(punch.getStatus())) {
            throw new BizException("已闭环的遗留项不允许删除，保留整改痕迹");
        }
        punchItemRepository.deleteById(id);
    }

    /** 左右双栏对照视图：报验项 + 遗留项清单 */
    public Map<String, Object> board() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Survey> surveys = surveyRepository.findAllByOrderBySurveyDateDesc();
        List<PunchItem> punchItems = punchItemRepository.findAllByOrderByIdAsc();
        data.put("surveys", surveys);
        data.put("punchItems", punchItems);
        data.put("blocks", blockRepository.findAllByOrderByIdAsc());
        data.put("verdicts", surveyRepository.statByResult());

        List<Map<String, Object>> resultMeta = new ArrayList<>();
        for (String result : Dict.SURVEY_RESULTS) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", result);
            item.put("name", Dict.surveyResultName(result));
            item.put("count", surveys.stream().filter(s -> result.equals(s.getResult())).count());
            resultMeta.add(item);
        }
        data.put("resultMeta", resultMeta);

        List<Map<String, Object>> punchStatusMeta = new ArrayList<>();
        for (String status : Dict.PUNCH_STATUSES) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", status);
            item.put("name", Dict.punchStatusName(status));
            item.put("count", punchItems.stream().filter(p -> status.equals(p.getStatus())).count());
            punchStatusMeta.add(item);
        }
        data.put("punchStatusMeta", punchStatusMeta);
        data.put("openPunchCount", punchItems.stream().filter(p -> !"CLOSED".equals(p.getStatus())).count());
        return data;
    }

    /** 报验单详情：报验单 + 遗留项 + 分段 + 关联合同的付款结算状态（与合同详情、船舶合同列表同源） */
    public Map<String, Object> detail(Long id) {
        Survey survey = get(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("survey", survey);
        data.put("punchItems", punchItemRepository.findBySurveyIdOrderByIdAsc(id));
        blockRepository.findById(survey.getBlockId()).ifPresent(block -> data.put("block", block));
        // 本单结论会影响这些合同的付款节点结算状态；此处与合同详情、船舶合同列表同一份实时评估
        List<Contract> affected = new ArrayList<>();
        for (Contract contract : contractRepository.findByVesselIdOrderByIdAsc(survey.getVesselId())) {
            if (paymentService.hasNodes(contract.getId())) {
                affected.add(contract);
            }
        }
        paymentService.attachSummary(affected);
        data.put("affectedContracts", affected);
        return data;
    }

    /** 遗留项编号：PI-报验日期序号-本单序号 */
    private String nextPunchNo(String surveyNo) {
        String scope = StringUtils.hasText(surveyNo)
                ? surveyNo.replace("SV-", "")
                : LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long seq = CodeSequenceService.next(PUNCH_SEQ_KEY, scope);
        return "PI-" + scope + "-" + String.format("%02d", seq);
    }

    private void applyPartial(Survey survey, SurveyRequest request) {
        ifNotNull(request.getSurveyItem(), survey::setSurveyItem);
        ifNotNull(request.getSurveyDate(), survey::setSurveyDate);
        ifNotNull(request.getAuthority(), survey::setAuthority);
        ifNotNull(request.getInspector(), survey::setInspector);
        ifNotNull(request.getResult(), survey::setResult);
        ifNotNull(request.getRemark(), survey::setRemark);
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
