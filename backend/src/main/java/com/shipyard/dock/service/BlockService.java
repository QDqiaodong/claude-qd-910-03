package com.shipyard.dock.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shipyard.dock.common.BizException;
import com.shipyard.dock.common.Dict;
import com.shipyard.dock.domain.Block;
import com.shipyard.dock.domain.Vessel;
import com.shipyard.dock.dto.BlockRequest;
import com.shipyard.dock.repository.BlockRepository;
import com.shipyard.dock.repository.ErectionRepository;
import com.shipyard.dock.repository.LiftRepository;
import com.shipyard.dock.repository.PunchItemRepository;
import com.shipyard.dock.repository.SurveyRepository;
import com.shipyard.dock.repository.VesselRepository;

/**
 * 模块三：船体分段。
 *
 * <p>规则：① 分段重量必须 &gt; 0；② 状态按 预制 → 涂装 → 搭载 → 合拢 → 交验 逐级推进，跳阶段拦截；
 * ③ 必须归属一艘本厂的船舶；④ 合拢前必须已完成涂装；⑤ 交验前所属遗留项必须全部闭环。</p>
 *
 * <p>分段编码 {@code blockCode} 不在这里拼，交给 {@code @SegmentCode} +
 * Hibernate {@code @ValueGenerationType} 在插入前生成。</p>
 */
@Service
public class BlockService {

    private final BlockRepository blockRepository;
    private final VesselRepository vesselRepository;
    private final PunchItemRepository punchItemRepository;
    private final SurveyRepository surveyRepository;
    private final ErectionRepository erectionRepository;
    private final LiftRepository liftRepository;

    public BlockService(BlockRepository blockRepository, VesselRepository vesselRepository,
            PunchItemRepository punchItemRepository, SurveyRepository surveyRepository,
            ErectionRepository erectionRepository, LiftRepository liftRepository) {
        this.blockRepository = blockRepository;
        this.vesselRepository = vesselRepository;
        this.punchItemRepository = punchItemRepository;
        this.surveyRepository = surveyRepository;
        this.erectionRepository = erectionRepository;
        this.liftRepository = liftRepository;
    }

    public List<Block> list() {
        return blockRepository.findAllByOrderByIdAsc();
    }

    public Block get(Long id) {
        return blockRepository.findById(id)
                .orElseThrow(() -> new BizException("分段不存在：" + id));
    }

    @Transactional
    public Block create(BlockRequest request) {
        if (!Dict.isValidBlockType(request.getBlockType())) {
            throw new BizException("分段类型非法（底部 / 舷侧 / 甲板 / 艏部 / 艉部 / 上建 六选一）："
                    + request.getBlockType());
        }
        Block block = new Block();
        apply(block, request);
        bindVessel(block);
        if (block.getStatus() == null) {
            block.setStatus("PREFAB");
        }
        if (!Dict.isValidBlockStatus(block.getStatus())) {
            throw new BizException("分段状态非法：" + block.getStatus());
        }
        if (block.getPainted() == null) {
            block.setPainted(Boolean.FALSE);
        }
        checkWeight(block);
        checkSeq(block);
        block.setCreatedAt(LocalDateTime.now());
        return blockRepository.save(block);
    }

    @Transactional
    public Block update(Long id, BlockRequest request) {
        Block block = get(id);
        if (request.getBlockType() != null && !Dict.isValidBlockType(request.getBlockType())) {
            throw new BizException("分段类型非法（底部 / 舷侧 / 甲板 / 艏部 / 艉部 / 上建 六选一）："
                    + request.getBlockType());
        }
        if (request.getStatus() != null && !request.getStatus().equals(block.getStatus())
                && !Dict.canTransferBlock(block.getStatus(), request.getStatus())) {
            throw new BizException("分段状态不能从 " + Dict.blockStatusName(block.getStatus())
                    + " 直接变为 " + Dict.blockStatusName(request.getStatus())
                    + "（预制 → 涂装 → 搭载 → 合拢 → 交验，跳阶段拦截）");
        }
        applyPartial(block, request);
        if (request.getVesselId() != null) {
            bindVessel(block);
        }
        if ("CLOSURE".equals(block.getStatus()) || "SURVEY".equals(block.getStatus())) {
            checkPaintedBeforeClosure(block);
        }
        checkWeight(block);
        checkSeq(block);
        return blockRepository.save(block);
    }

    /** 逐级推进分段状态：预制 → 涂装 → 搭载 → 合拢 → 交验 */
    @Transactional
    public Block advance(Long id) {
        Block block = get(id);
        int index = Dict.BLOCK_FLOW.indexOf(block.getStatus());
        if (index < 0) {
            throw new BizException("分段 " + block.getBlockCode() + " 状态非法：" + block.getStatus());
        }
        if (index + 1 >= Dict.BLOCK_FLOW.size()) {
            throw new BizException("分段 " + block.getBlockCode() + " 已处于终态「"
                    + Dict.blockStatusName(block.getStatus()) + "」，无需再推进");
        }
        String next = Dict.BLOCK_FLOW.get(index + 1);
        if (!Dict.canTransferBlock(block.getStatus(), next)) {
            throw new BizException("分段状态不能从 " + Dict.blockStatusName(block.getStatus())
                    + " 变为 " + Dict.blockStatusName(next));
        }
        block.setStatus(next);
        checkPaintedBeforeClosure(block);
        checkBeforeSurvey(block);
        if ("SURVEY".equals(next) && block.getActualFinishDate() == null) {
            block.setActualFinishDate(LocalDate.now());
        }
        return blockRepository.save(block);
    }

    /** 标记涂装完成：合拢的前置条件 */
    @Transactional
    public Block finishPaint(Long id) {
        Block block = get(id);
        if (Boolean.TRUE.equals(block.getPainted())) {
            throw new BizException("分段 " + block.getBlockCode() + " 已经完成涂装，不要重复报完工");
        }
        block.setPainted(Boolean.TRUE);
        block.setPaintFinishDate(LocalDate.now());
        if ("PREFAB".equals(block.getStatus())) {
            block.setStatus("PAINT");
        }
        return blockRepository.save(block);
    }

    @Transactional
    public void delete(Long id) {
        Block block = get(id);
        int lifts = liftRepository.findByBlockIdOrderByLiftTimeAsc(id).size();
        int surveys = surveyRepository.findByBlockIdOrderBySurveyDateDesc(id).size();
        int erections = erectionRepository.findByBlockIdOrderByIdAsc(id).size();
        if (lifts + surveys + erections > 0) {
            throw new BizException("分段 " + block.getBlockCode() + " 已有 " + erections + " 条搭载任务、"
                    + lifts + " 个吊次、" + surveys + " 张报验单引用，不能删除");
        }
        blockRepository.deleteById(id);
    }

    /** 卡片墙视图：分段 + 状态 / 类型元数据 + 船舶清单 */
    public Map<String, Object> board() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Block> blocks = blockRepository.findAllByOrderByIdAsc();
        data.put("blocks", blocks);
        data.put("statuses", blockRepository.statByStatus());
        data.put("vessels", vesselRepository.findAllByOrderByIdAsc());
        // 搭载任务（含排程设备 / 待复核）一并下发，分段卡片直接显示当前吊装归属
        data.put("erections", erectionRepository.findAllByOrderByIdAsc());

        List<Map<String, Object>> statusMeta = new ArrayList<>();
        for (String status : Dict.BLOCK_FLOW) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", status);
            item.put("name", Dict.blockStatusName(status));
            item.put("count", blocks.stream().filter(b -> status.equals(b.getStatus())).count());
            statusMeta.add(item);
        }
        data.put("statusMeta", statusMeta);

        List<Map<String, Object>> typeMeta = new ArrayList<>();
        for (String type : Dict.BLOCK_TYPES) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", type);
            item.put("name", Dict.blockTypeName(type));
            item.put("count", blocks.stream().filter(b -> type.equals(b.getBlockType())).count());
            typeMeta.add(item);
        }
        data.put("typeMeta", typeMeta);
        return data;
    }

    /** 分段详情：分段 + 报验单 + 遗留项 + 吊次 + 搭载任务（含排程设备 / 待复核） */
    public Map<String, Object> detail(Long id) {
        Block block = get(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("block", block);
        data.put("surveys", surveyRepository.findByBlockIdOrderBySurveyDateDesc(id));
        data.put("punchItems", punchItemRepository.findByBlockIdOrderByIdAsc(id));
        data.put("lifts", liftRepository.findByBlockIdOrderByLiftTimeAsc(id));
        data.put("erections", erectionRepository.findByBlockIdOrderByIdAsc(id));
        data.put("openPunchCount", punchItemRepository.countByBlockIdAndStatusNot(id, "CLOSED"));
        return data;
    }

    private void apply(Block block, BlockRequest request) {
        block.setVesselId(request.getVesselId());
        block.setBlockName(request.getBlockName());
        block.setBlockType(request.getBlockType());
        block.setWeightT(request.getWeightT());
        block.setSegSeq(request.getSegSeq());
        block.setStatus(request.getStatus());
        block.setPainted(request.getPainted());
        block.setPaintFinishDate(request.getPaintFinishDate());
        block.setWorkshop(request.getWorkshop());
        block.setPlanStartDate(request.getPlanStartDate());
        block.setActualFinishDate(request.getActualFinishDate());
        block.setNote(request.getNote());
    }

    private void applyPartial(Block block, BlockRequest request) {
        ifNotNull(request.getVesselId(), block::setVesselId);
        ifNotNull(request.getBlockName(), block::setBlockName);
        ifNotNull(request.getBlockType(), block::setBlockType);
        ifNotNull(request.getWeightT(), block::setWeightT);
        ifNotNull(request.getSegSeq(), block::setSegSeq);
        ifNotNull(request.getStatus(), block::setStatus);
        ifNotNull(request.getPainted(), block::setPainted);
        ifNotNull(request.getPaintFinishDate(), block::setPaintFinishDate);
        ifNotNull(request.getWorkshop(), block::setWorkshop);
        ifNotNull(request.getPlanStartDate(), block::setPlanStartDate);
        ifNotNull(request.getActualFinishDate(), block::setActualFinishDate);
        ifNotNull(request.getNote(), block::setNote);
    }

    /** 必须归属一艘本厂的船舶 */
    private void bindVessel(Block block) {
        if (block.getVesselId() == null) {
            throw new BizException("分段必须归属一艘本厂的船舶");
        }
        Vessel vessel = vesselRepository.findById(block.getVesselId())
                .orElseThrow(() -> new BizException("船舶不存在，请先建档：" + block.getVesselId()));
        block.setVesselNo(vessel.getVesselNo());
        block.setVesselName(vessel.getVesselName());
    }

    private void checkWeight(Block block) {
        if (block.getWeightT() == null || block.getWeightT().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("分段重量必须大于 0");
        }
        if (block.getWeightT().compareTo(Dict.MAX_BLOCK_WEIGHT) > 0) {
            throw new BizException("分段重量 " + block.getWeightT() + " t 超过单段上限 "
                    + Dict.MAX_BLOCK_WEIGHT + " t");
        }
    }

    /** 同一条船内搭载顺序号不能重复 */
    private void checkSeq(Block block) {
        if (block.getSegSeq() == null || block.getSegSeq() <= 0) {
            throw new BizException("搭载顺序号必须大于 0");
        }
        List<Block> siblings = blockRepository.findByVesselIdOrderBySegSeqAsc(block.getVesselId());
        for (Block sibling : siblings) {
            if (!sibling.getId().equals(block.getId()) && sibling.getSegSeq() != null
                    && sibling.getSegSeq().equals(block.getSegSeq())) {
                throw new BizException("船号 " + block.getVesselNo() + " 的搭载顺序号 "
                        + block.getSegSeq() + " 已被分段 " + sibling.getBlockCode() + " 占用");
            }
        }
    }

    /** 合拢前必须已完成涂装 */
    private void checkPaintedBeforeClosure(Block block) {
        if ("CLOSURE".equals(block.getStatus()) || "SURVEY".equals(block.getStatus())) {
            if (!Boolean.TRUE.equals(block.getPainted())) {
                throw new BizException("分段 " + block.getBlockCode() + " 尚未完成涂装，不允许进入"
                        + Dict.blockStatusName(block.getStatus()) + "阶段");
            }
        }
    }

    /** 交验前遗留项必须全部闭环 */
    private void checkBeforeSurvey(Block block) {
        if (!"SURVEY".equals(block.getStatus())) {
            return;
        }
        long open = punchItemRepository.countByBlockIdAndStatusNot(block.getId(), "CLOSED");
        if (open > 0) {
            throw new BizException("分段 " + block.getBlockCode() + " 还有 " + open
                    + " 条遗留项未闭环，不允许置为已交验");
        }
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
