package com.shipyard.dock.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.shipyard.dock.common.Dict;
import com.shipyard.dock.domain.Block;
import com.shipyard.dock.domain.Contract;
import com.shipyard.dock.repository.BerthRepository;
import com.shipyard.dock.repository.BlockRepository;
import com.shipyard.dock.repository.ContractRepository;
import com.shipyard.dock.repository.ErectionRepository;
import com.shipyard.dock.repository.LiftRepository;
import com.shipyard.dock.repository.OwnerRepository;
import com.shipyard.dock.repository.PunchItemRepository;
import com.shipyard.dock.repository.SurveyRepository;
import com.shipyard.dock.repository.VesselRepository;

/**
 * 统计：总览数字 + 各模块手写 JPQL 统计 + 词典。
 */
@Service
public class StatsService {

    private final BerthRepository berthRepository;
    private final VesselRepository vesselRepository;
    private final ContractRepository contractRepository;
    private final BlockRepository blockRepository;
    private final ErectionRepository erectionRepository;
    private final LiftRepository liftRepository;
    private final SurveyRepository surveyRepository;
    private final PunchItemRepository punchItemRepository;
    private final OwnerRepository ownerRepository;

    public StatsService(BerthRepository berthRepository, VesselRepository vesselRepository,
            ContractRepository contractRepository, BlockRepository blockRepository,
            ErectionRepository erectionRepository, LiftRepository liftRepository,
            SurveyRepository surveyRepository, PunchItemRepository punchItemRepository,
            OwnerRepository ownerRepository) {
        this.berthRepository = berthRepository;
        this.vesselRepository = vesselRepository;
        this.contractRepository = contractRepository;
        this.blockRepository = blockRepository;
        this.erectionRepository = erectionRepository;
        this.liftRepository = liftRepository;
        this.surveyRepository = surveyRepository;
        this.punchItemRepository = punchItemRepository;
        this.ownerRepository = ownerRepository;
    }

    public Map<String, Object> overview() {
        List<Block> blocks = blockRepository.findAllByOrderByIdAsc();
        List<Contract> contracts = contractRepository.findAllByOrderByIdAsc();
        long occupied = berthRepository.findAllByOrderByIdAsc().stream()
                .filter(b -> "OCCUPIED".equals(b.getStatus())).count();
        long passCount = surveyRepository.findAllByOrderBySurveyDateDesc().stream()
                .filter(s -> "PASS".equals(s.getResult())).count();
        long openPunch = punchItemRepository.findAllByOrderByIdAsc().stream()
                .filter(p -> !"CLOSED".equals(p.getStatus())).count();
        double blockWeight = blocks.stream()
                .mapToDouble(b -> b.getWeightT() == null ? 0.0 : b.getWeightT().doubleValue()).sum();
        double contractAmount = contracts.stream()
                .mapToDouble(c -> c.getAmount() == null ? 0.0 : c.getAmount().doubleValue()).sum();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("berthCount", berthRepository.count());
        data.put("berthOccupied", occupied);
        data.put("vesselCount", vesselRepository.count());
        data.put("ownerCount", ownerRepository.count());
        data.put("contractCount", contracts.size());
        data.put("contractAmount", Math.round(contractAmount * 100.0) / 100.0);
        data.put("blockCount", blocks.size());
        data.put("blockWeight", Math.round(blockWeight * 10.0) / 10.0);
        data.put("erectionCount", erectionRepository.count());
        // 只统计真实完成的历史吊次；未开始排程 / 进行中吊次不计入实绩
        data.put("liftCount", liftRepository.countByStatus("DONE"));
        data.put("liftInProgressCount", liftRepository.countByStatus("IN_PROGRESS"));
        data.put("liftScheduledCount", liftRepository.countByStatus("SCHEDULED"));
        data.put("surveyCount", surveyRepository.count());
        data.put("passCount", passCount);
        data.put("punchCount", punchItemRepository.count());
        data.put("openPunchCount", openPunch);
        return data;
    }

    public Map<String, Object> dict() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("berthTypes", meta(Dict.BERTH_TYPES, Dict::berthTypeName));
        data.put("berthStatuses", meta(Dict.BERTH_STATUSES, Dict::berthStatusName));
        data.put("vesselTypes", meta(Dict.VESSEL_TYPES, Dict::vesselTypeName));
        data.put("vesselStatuses", meta(Dict.VESSEL_STATUSES, Dict::vesselStatusName));
        data.put("ownerTypes", meta(Dict.OWNER_TYPES, Dict::ownerTypeName));
        data.put("coopLevels", meta(Dict.COOP_LEVELS, Dict::coopLevelName));
        data.put("contractTypes", meta(Dict.CONTRACT_TYPES, Dict::contractTypeName));
        data.put("contractStatuses", meta(Dict.CONTRACT_FLOW, Dict::contractStatusName));
        data.put("blockTypes", meta(Dict.BLOCK_TYPES, Dict::blockTypeName));
        data.put("blockStatuses", meta(Dict.BLOCK_FLOW, Dict::blockStatusName));
        data.put("craneTypes", meta(Dict.CRANE_TYPES, Dict::craneTypeName));
        data.put("craneStatuses", meta(Dict.CRANE_STATUSES, Dict::craneStatusName));
        data.put("erectionStatuses", meta(Dict.ERECTION_STATUSES, Dict::erectionStatusName));
        data.put("surveyItems", meta(Dict.SURVEY_ITEMS, Dict::surveyItemName));
        data.put("surveyResults", meta(Dict.SURVEY_RESULTS, Dict::surveyResultName));
        data.put("authorities", meta(Dict.AUTHORITIES, Dict::authorityName));
        data.put("punchSeverities", meta(Dict.PUNCH_SEVERITIES, Dict::punchSeverityName));
        data.put("punchStatuses", meta(Dict.PUNCH_STATUSES, Dict::punchStatusName));
        data.put("maxBlockWeight", Dict.MAX_BLOCK_WEIGHT);
        data.put("maxLiftWeight", Dict.MAX_LIFT_WEIGHT);
        data.put("maxWindLevel", Dict.MAX_WIND_LEVEL);
        return data;
    }

    public Map<String, Object> berthStat() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", berthRepository.statByBerthType());
        return data;
    }

    public Map<String, Object> blockStat() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", blockRepository.statByStatus());
        return data;
    }

    public Map<String, Object> liftStat() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", liftRepository.statByCraneType());
        return data;
    }

    public Map<String, Object> surveyStat() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", surveyRepository.statByResult());
        return data;
    }

    public Map<String, Object> contractStat() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", contractRepository.statByStatus());
        return data;
    }

    public Map<String, Object> vesselStat() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", vesselRepository.statByVesselType());
        return data;
    }

    private List<Map<String, Object>> meta(List<String> codes, java.util.function.Function<String, String> namer) {
        return codes.stream().map(code -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", code);
            item.put("name", namer.apply(code));
            return item;
        }).toList();
    }
}
