package com.shipyard.dock.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.shipyard.dock.common.BizException;
import com.shipyard.dock.common.Dict;
import com.shipyard.dock.domain.Crane;
import com.shipyard.dock.domain.CraneScheduleLog;
import com.shipyard.dock.domain.Erection;
import com.shipyard.dock.domain.Lift;
import com.shipyard.dock.dto.CraneRequest;
import com.shipyard.dock.repository.CraneRepository;
import com.shipyard.dock.repository.CraneScheduleLogRepository;
import com.shipyard.dock.repository.ErectionRepository;
import com.shipyard.dock.repository.LiftRepository;

/**
 * 起重设备档案：吊次登记的额定起重量来源。
 *
 * <p>设备状态（可用 / 检修 / 停用）不允许通过通用编辑接口直接改写 —— 必须走
 * {@link ScheduleService} 的检修停用 / 恢复流程，否则进行中吊次、排程与负荷校验会互相矛盾。
 * 只改档案状态或只批量替换排程里的设备编号，都不视为完成。</p>
 */
@Service
public class CraneService {

    private final CraneRepository craneRepository;
    private final LiftRepository liftRepository;
    private final ErectionRepository erectionRepository;
    private final CraneScheduleLogRepository logRepository;

    public CraneService(CraneRepository craneRepository, LiftRepository liftRepository,
            ErectionRepository erectionRepository, CraneScheduleLogRepository logRepository) {
        this.craneRepository = craneRepository;
        this.liftRepository = liftRepository;
        this.erectionRepository = erectionRepository;
        this.logRepository = logRepository;
    }

    public List<Crane> list() {
        return craneRepository.findAllByOrderByIdAsc();
    }

    public Crane get(Long id) {
        return craneRepository.findById(id)
                .orElseThrow(() -> new BizException("起重设备不存在：" + id));
    }

    /** 设备详情：档案 + 未开始任务 / 进行中吊次 / 历史吊次 + 排程操作日志 */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        Crane crane = get(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("crane", crane);
        data.put("notStartedTasks",
                erectionRepository.findByCraneIdAndStatusInOrderByPlanDateAscIdAsc(id, List.of("PLANNED", "READY")));
        data.put("scheduledLifts",
                liftRepository.findByCraneIdAndStatusInOrderByLiftTimeAsc(id, List.of("SCHEDULED")));
        data.put("inProgressLifts",
                liftRepository.findByCraneIdAndStatusOrderByLiftTimeAsc(id, "IN_PROGRESS"));
        data.put("historyLifts",
                liftRepository.findByCraneIdAndStatusInOrderByLiftTimeAsc(id, List.of("DONE")));
        data.put("logs", logRepository.findByCraneIdOrderByIdDesc(id));
        return data;
    }

    public List<CraneScheduleLog> logs() {
        return logRepository.findAllByOrderByIdDesc();
    }

    @Transactional
    public Crane create(CraneRequest request) {
        if (!StringUtils.hasText(request.getCraneNo())) {
            throw new BizException("设备编号不能为空");
        }
        if (craneRepository.existsByCraneNo(request.getCraneNo())) {
            throw new BizException("设备编号已存在：" + request.getCraneNo());
        }
        if (!Dict.isValidCraneType(request.getCraneType())) {
            throw new BizException("设备类型非法（龙门吊 / 塔吊 / 履带吊 / 浮吊 四选一）：" + request.getCraneType());
        }
        Crane crane = new Crane();
        apply(crane, request);
        if (crane.getStatus() == null) {
            crane.setStatus("AVAILABLE");
        }
        if (!Dict.isValidCraneStatus(crane.getStatus())) {
            throw new BizException("设备状态非法：" + crane.getStatus());
        }
        // 新建档案允许直接以检修状态建档（此时还没有任何排程 / 吊次），但必须带原因
        if (!crane.isAvailable() && !StringUtils.hasText(crane.getUnavailableReason())) {
            throw new BizException("以检修 / 停用状态建档必须填写原因");
        }
        if (!crane.isAvailable()) {
            crane.setMaintenanceFrom(LocalDateTime.now());
        }
        checkRatedLoad(crane);
        crane.setCreatedAt(LocalDateTime.now());
        return craneRepository.save(crane);
    }

    @Transactional
    public Crane update(Long id, CraneRequest request) {
        Crane crane = get(id);
        if (StringUtils.hasText(request.getCraneNo()) && !request.getCraneNo().equals(crane.getCraneNo())
                && craneRepository.existsByCraneNo(request.getCraneNo())) {
            throw new BizException("设备编号已存在：" + request.getCraneNo());
        }
        if (request.getCraneType() != null && !Dict.isValidCraneType(request.getCraneType())) {
            throw new BizException("设备类型非法（龙门吊 / 塔吊 / 履带吊 / 浮吊 四选一）：" + request.getCraneType());
        }
        // 关键红线：禁止用通用编辑直接切状态，必须走「检修停用 / 恢复」联动流程
        if (request.getStatus() != null && !request.getStatus().equals(crane.getStatus())) {
            throw new BizException("不能直接编辑设备状态：请使用「设为检修 / 停用」联动处置"
                    + "（自动汇总未开始任务、进行中吊次与历史吊次），恢复时走「检修恢复」");
        }
        applyPartial(crane, request);
        checkRatedLoad(crane);
        return craneRepository.save(crane);
    }

    @Transactional
    public void delete(Long id) {
        Crane crane = get(id);
        int lifts = liftRepository.findByCraneIdOrderByLiftTimeAsc(id).size();
        if (lifts > 0) {
            throw new BizException("设备 " + crane.getCraneNo() + " 已有 " + lifts + " 个吊次记录，不能删除");
        }
        List<Erection> tasks = erectionRepository
                .findByCraneIdAndStatusInOrderByPlanDateAscIdAsc(id, List.of("PLANNED", "READY"));
        if (!tasks.isEmpty()) {
            throw new BizException("设备 " + crane.getCraneNo() + " 上还有 " + tasks.size()
                    + " 个未开始搭载任务，不能删除");
        }
        craneRepository.deleteById(id);
    }

    private void apply(Crane crane, CraneRequest request) {
        crane.setCraneNo(request.getCraneNo());
        crane.setCraneName(request.getCraneName());
        crane.setCraneType(request.getCraneType());
        crane.setRatedLoadT(request.getRatedLoadT());
        crane.setSpanM(request.getSpanM());
        crane.setLiftHeightM(request.getLiftHeightM());
        crane.setStatus(request.getStatus());
        crane.setLocation(request.getLocation());
        crane.setNote(request.getNote());
    }

    private void applyPartial(Crane crane, CraneRequest request) {
        ifNotNull(request.getCraneNo(), crane::setCraneNo);
        ifNotNull(request.getCraneName(), crane::setCraneName);
        ifNotNull(request.getCraneType(), crane::setCraneType);
        ifNotNull(request.getRatedLoadT(), crane::setRatedLoadT);
        ifNotNull(request.getSpanM(), crane::setSpanM);
        ifNotNull(request.getLiftHeightM(), crane::setLiftHeightM);
        ifNotNull(request.getLocation(), crane::setLocation);
        ifNotNull(request.getNote(), crane::setNote);
    }

    private void checkRatedLoad(Crane crane) {
        if (crane.getRatedLoadT() == null || crane.getRatedLoadT().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("额定起重量必须大于 0");
        }
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
