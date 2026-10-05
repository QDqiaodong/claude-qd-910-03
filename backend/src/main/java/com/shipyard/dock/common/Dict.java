package com.shipyard.dock.common;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 船舶修造船厂词典与业务规则参数表：船台船坞类型与状态流转、船舶类型、船东类型、
 * 修造合同类型与状态流转、分段类型与状态流转、起重设备类型、搭载搭载状态、
 * 报验项目、报验结论、船检机构、遗留项严重度与状态，以及各类阈值。
 *
 * <p>规则集中在此，便于出题时被要求调整或扩展。</p>
 */
public final class Dict {

    private Dict() {
    }

    // ---------------- 船台船坞类型 ----------------
    public static final List<String> BERTH_TYPES = List.of("SLIPWAY", "DRYDOCK", "WHARF");

    private static final Map<String, String> BERTH_TYPE_NAME = Map.of(
            "SLIPWAY", "船台",
            "DRYDOCK", "船坞",
            "WHARF", "舾装码头");

    // ---------------- 船台船坞状态：空闲 → 占用 → 检修 → 空闲 ----------------
    public static final List<String> BERTH_STATUSES = List.of("IDLE", "OCCUPIED", "MAINTENANCE");

    private static final Map<String, String> BERTH_STATUS_NAME = Map.of(
            "IDLE", "空闲",
            "OCCUPIED", "占用",
            "MAINTENANCE", "检修");

    /** 允许的状态流转（含出坞/让位：占用 → 空闲） */
    private static final Map<String, List<String>> BERTH_NEXT = Map.of(
            "IDLE", List.of("OCCUPIED", "MAINTENANCE"),
            "OCCUPIED", List.of("MAINTENANCE", "IDLE"),
            "MAINTENANCE", List.of("IDLE"));

    // ---------------- 船舶类型 ----------------
    public static final List<String> VESSEL_TYPES = List.of(
            "BULK", "TANKER", "CONTAINER", "PASSENGER", "ENGINEERING");

    private static final Map<String, String> VESSEL_TYPE_NAME = Map.of(
            "BULK", "散货船",
            "TANKER", "油轮",
            "CONTAINER", "集装箱船",
            "PASSENGER", "客滚船",
            "ENGINEERING", "工程船");

    public static final List<String> VESSEL_STATUSES = List.of("IN_YARD", "LAUNCHED", "DELIVERED");

    private static final Map<String, String> VESSEL_STATUS_NAME = Map.of(
            "IN_YARD", "在厂",
            "LAUNCHED", "已下水",
            "DELIVERED", "已交付");

    // ---------------- 船东 ----------------
    public static final List<String> OWNER_TYPES = List.of("STATE", "PRIVATE", "FOREIGN");

    private static final Map<String, String> OWNER_TYPE_NAME = Map.of(
            "STATE", "国有航运",
            "PRIVATE", "民营船东",
            "FOREIGN", "境外船东");

    public static final List<String> COOP_LEVELS = List.of("STRATEGIC", "KEY", "NORMAL");

    private static final Map<String, String> COOP_LEVEL_NAME = Map.of(
            "STRATEGIC", "战略客户",
            "KEY", "重点客户",
            "NORMAL", "一般客户");

    // ---------------- 修造合同 ----------------
    public static final List<String> CONTRACT_TYPES = List.of("NEW_BUILD", "REPAIR", "CONVERSION");

    private static final Map<String, String> CONTRACT_TYPE_NAME = Map.of(
            "NEW_BUILD", "新船建造",
            "REPAIR", "坞检修理",
            "CONVERSION", "改装加长");

    /** 草拟 → 已签 → 履行中 → 已结算，逐级推进，不可跳级 */
    public static final List<String> CONTRACT_FLOW = List.of("DRAFT", "SIGNED", "PERFORMING", "SETTLED");

    private static final Map<String, String> CONTRACT_STATUS_NAME = Map.of(
            "DRAFT", "草拟",
            "SIGNED", "已签",
            "PERFORMING", "履行中",
            "SETTLED", "已结算");

    // ---------------- 分段 ----------------
    public static final List<String> BLOCK_TYPES = List.of(
            "BOTTOM", "SIDE", "DECK", "BOW", "STERN", "SUPER");

    private static final Map<String, String> BLOCK_TYPE_NAME = Map.of(
            "BOTTOM", "底部分段",
            "SIDE", "舷侧分段",
            "DECK", "甲板分段",
            "BOW", "艏部分段",
            "STERN", "艉部分段",
            "SUPER", "上建分段");

    /** 预制 → 涂装 → 搭载 → 合拢 → 交验，逐级推进，不可跳级 */
    public static final List<String> BLOCK_FLOW = List.of("PREFAB", "PAINT", "ERECTION", "CLOSURE", "SURVEY");

    private static final Map<String, String> BLOCK_STATUS_NAME = Map.of(
            "PREFAB", "预制",
            "PAINT", "涂装",
            "ERECTION", "搭载",
            "CLOSURE", "合拢",
            "SURVEY", "交验");

    // ---------------- 起重设备 ----------------
    public static final List<String> CRANE_TYPES = List.of("GANTRY", "TOWER", "CRAWLER", "FLOATING");

    private static final Map<String, String> CRANE_TYPE_NAME = Map.of(
            "GANTRY", "龙门吊",
            "TOWER", "塔吊",
            "CRAWLER", "履带吊",
            "FLOATING", "浮吊");

    public static final List<String> CRANE_STATUSES = List.of("AVAILABLE", "MAINTAINING", "RETIRED");

    private static final Map<String, String> CRANE_STATUS_NAME = Map.of(
            "AVAILABLE", "可用",
            "MAINTAINING", "检修",
            "RETIRED", "停用");

    // ---------------- 搭载任务 ----------------
    public static final List<String> ERECTION_STATUSES = List.of("PLANNED", "READY", "DONE");

    private static final Map<String, String> ERECTION_STATUS_NAME = Map.of(
            "PLANNED", "已排程",
            "READY", "待吊装",
            "DONE", "已完成");

    private static final Map<String, List<String>> ERECTION_NEXT = Map.of(
            "PLANNED", List.of("READY"),
            "READY", List.of("DONE", "PLANNED"),
            "DONE", List.of());

    // ---------------- 吊次状态 ----------------
    /** 未开始（随排程走） → 进行中（归属冻结） → 已完成（历史，永不倒改） */
    public static final List<String> LIFT_STATUSES = List.of("SCHEDULED", "IN_PROGRESS", "DONE");

    private static final Map<String, String> LIFT_STATUS_NAME = Map.of(
            "SCHEDULED", "未开始",
            "IN_PROGRESS", "进行中",
            "DONE", "已完成");

    // ---------------- 起重设备排程操作（只追加的审计动作） ----------------
    public static final List<String> SCHEDULE_ACTIONS = List.of(
            "SCHEDULE", "UNAVAILABLE", "AUTO_REASSIGN", "HOLD_REVIEW",
            "REASSIGN", "LIFT_START", "SAFETY_HANDLING", "RESTORE_ORIGINAL",
            "KEEP_ALTERNATIVE", "RECOVER");

    private static final Map<String, String> SCHEDULE_ACTION_NAME = Map.ofEntries(
            Map.entry("SCHEDULE", "排程指派"),
            Map.entry("UNAVAILABLE", "设备停用/检修"),
            Map.entry("AUTO_REASSIGN", "自动改派替代设备"),
            Map.entry("HOLD_REVIEW", "保留原排程待复核"),
            Map.entry("REASSIGN", "人工改派"),
            Map.entry("LIFT_START", "吊次开始"),
            Map.entry("SAFETY_HANDLING", "进行中吊次安全处置"),
            Map.entry("RESTORE_ORIGINAL", "恢复原排程"),
            Map.entry("KEEP_ALTERNATIVE", "保留替代设备"),
            Map.entry("RECOVER", "设备恢复可用"));

    // ---------------- 报验 ----------------
    public static final List<String> SURVEY_ITEMS = List.of(
            "WELD", "DIMENSION", "COATING", "OUTFITTING", "TIGHTNESS");

    private static final Map<String, String> SURVEY_ITEM_NAME = Map.of(
            "WELD", "焊缝探伤",
            "DIMENSION", "主尺度",
            "COATING", "涂装膜厚",
            "OUTFITTING", "舾装完整性",
            "TIGHTNESS", "舱室密性");

    /** 结论只能三选一：合格 / 让步 / 不合格 */
    public static final List<String> SURVEY_RESULTS = List.of("PASS", "CONCESSION", "FAIL");

    private static final Map<String, String> SURVEY_RESULT_NAME = Map.of(
            "PASS", "合格",
            "CONCESSION", "让步",
            "FAIL", "不合格");

    public static final List<String> AUTHORITIES = List.of("CCS", "ABS", "LR", "DNV", "BV", "OWNER");

    private static final Map<String, String> AUTHORITY_NAME = Map.of(
            "CCS", "中国船级社",
            "ABS", "美国船级社",
            "LR", "英国劳氏",
            "DNV", "挪威船级社",
            "BV", "法国必维",
            "OWNER", "船东代表");

    // ---------------- 遗留项 ----------------
    public static final List<String> PUNCH_SEVERITIES = List.of("MINOR", "MAJOR", "CRITICAL");

    private static final Map<String, String> PUNCH_SEVERITY_NAME = Map.of(
            "MINOR", "一般",
            "MAJOR", "重要",
            "CRITICAL", "严重");

    /** 开启 → 整改中 → 已闭环 */
    public static final List<String> PUNCH_STATUSES = List.of("OPEN", "RECTIFYING", "CLOSED");

    private static final Map<String, String> PUNCH_STATUS_NAME = Map.of(
            "OPEN", "开启",
            "RECTIFYING", "整改中",
            "CLOSED", "已闭环");

    private static final Map<String, List<String>> PUNCH_NEXT = Map.of(
            "OPEN", List.of("RECTIFYING", "CLOSED"),
            "RECTIFYING", List.of("CLOSED"),
            "CLOSED", List.of());

    // ---------------- 下水放行单 ----------------
    /** 待确认 → 已放行 / 已作废 */
    public static final List<String> CLEARANCE_STATUSES = List.of("PENDING", "CONFIRMED", "VOID");

    private static final Map<String, String> CLEARANCE_STATUS_NAME = Map.of(
            "PENDING", "待确认",
            "CONFIRMED", "已放行",
            "VOID", "已作废");

    /** 放行校验结论：READY 可放行 / BLOCKED 有阻塞 */
    public static final List<String> CLEARANCE_VERDICTS = List.of("READY", "BLOCKED");

    // ---------------- 移泊结果 ----------------
    /** SUCCESS 成功 / FAIL 失败（含被并发抢占、校验不通过、重复请求等） */
    public static final List<String> MOVE_RESULTS = List.of("SUCCESS", "FAIL");

    private static final Map<String, String> MOVE_RESULT_NAME = Map.of(
            "SUCCESS", "成功",
            "FAIL", "失败");

    private static final Map<String, String> CLEARANCE_VERDICT_NAME = Map.of(
            "READY", "可放行",
            "BLOCKED", "有阻塞");

    /** 放行单操作记录类型 */
    public static final List<String> CLEARANCE_CHECK_TYPES = List.of(
            "GENERATE", "RECHECK", "SIGN", "CONFIRM", "VOID");

    private static final Map<String, String> CLEARANCE_CHECK_TYPE_NAME = Map.of(
            "GENERATE", "生成放行单",
            "RECHECK", "整改复核",
            "SIGN", "质量签认",
            "CONFIRM", "确认放行",
            "VOID", "作废");

    // ---------------- 业务阈值 ----------------
    /** 单个分段重量上限（吨） */
    public static final BigDecimal MAX_BLOCK_WEIGHT = BigDecimal.valueOf(1200);

    /** 单次吊装重量上限（吨），超过任何一台在用设备都拦 */
    public static final BigDecimal MAX_LIFT_WEIGHT = BigDecimal.valueOf(1500);

    /** 六级及以上风力禁止吊装 */
    public static final int MAX_WIND_LEVEL = 6;

    /** 分段长度/宽度余量：船台尺度须比船舶尺度至少富余这么多（米） */
    public static final BigDecimal BERTH_MARGIN_M = BigDecimal.valueOf(0);

    // ---------------- 取值方法 ----------------

    public static boolean isValidBerthType(String type) {
        return type != null && BERTH_TYPE_NAME.containsKey(type);
    }

    public static String berthTypeName(String type) {
        return type == null ? "" : BERTH_TYPE_NAME.getOrDefault(type, type);
    }

    public static boolean isValidBerthStatus(String status) {
        return status != null && BERTH_STATUS_NAME.containsKey(status);
    }

    public static String berthStatusName(String status) {
        return status == null ? "" : BERTH_STATUS_NAME.getOrDefault(status, status);
    }

    /** 船台状态流转：空闲 → 占用 → 检修 → 空闲 */
    public static boolean canTransferBerth(String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        return BERTH_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    public static boolean isValidVesselType(String type) {
        return type != null && VESSEL_TYPE_NAME.containsKey(type);
    }

    public static String vesselTypeName(String type) {
        return type == null ? "" : VESSEL_TYPE_NAME.getOrDefault(type, type);
    }

    public static boolean isValidVesselStatus(String status) {
        return status != null && VESSEL_STATUS_NAME.containsKey(status);
    }

    public static String vesselStatusName(String status) {
        return status == null ? "" : VESSEL_STATUS_NAME.getOrDefault(status, status);
    }

    public static boolean isValidOwnerType(String type) {
        return type != null && OWNER_TYPE_NAME.containsKey(type);
    }

    public static String ownerTypeName(String type) {
        return type == null ? "" : OWNER_TYPE_NAME.getOrDefault(type, type);
    }

    public static boolean isValidCoopLevel(String level) {
        return level != null && COOP_LEVEL_NAME.containsKey(level);
    }

    public static String coopLevelName(String level) {
        return level == null ? "" : COOP_LEVEL_NAME.getOrDefault(level, level);
    }

    public static boolean isValidContractType(String type) {
        return type != null && CONTRACT_TYPE_NAME.containsKey(type);
    }

    public static String contractTypeName(String type) {
        return type == null ? "" : CONTRACT_TYPE_NAME.getOrDefault(type, type);
    }

    public static boolean isValidContractStatus(String status) {
        return status != null && CONTRACT_STATUS_NAME.containsKey(status);
    }

    public static String contractStatusName(String status) {
        return status == null ? "" : CONTRACT_STATUS_NAME.getOrDefault(status, status);
    }

    /** 合同状态只能逐级推进：草拟 → 已签 → 履行中 → 已结算 */
    public static boolean canTransferContract(String from, String to) {
        int i = CONTRACT_FLOW.indexOf(from);
        return i >= 0 && i + 1 < CONTRACT_FLOW.size() && CONTRACT_FLOW.get(i + 1).equals(to);
    }

    public static boolean isValidBlockType(String type) {
        return type != null && BLOCK_TYPE_NAME.containsKey(type);
    }

    public static String blockTypeName(String type) {
        return type == null ? "" : BLOCK_TYPE_NAME.getOrDefault(type, type);
    }

    public static boolean isValidBlockStatus(String status) {
        return status != null && BLOCK_STATUS_NAME.containsKey(status);
    }

    public static String blockStatusName(String status) {
        return status == null ? "" : BLOCK_STATUS_NAME.getOrDefault(status, status);
    }

    public static int blockStatusIndex(String status) {
        return BLOCK_FLOW.indexOf(status);
    }

    /** 分段状态只能逐级推进：预制 → 涂装 → 搭载 → 合拢 → 交验 */
    public static boolean canTransferBlock(String from, String to) {
        int i = BLOCK_FLOW.indexOf(from);
        return i >= 0 && i + 1 < BLOCK_FLOW.size() && BLOCK_FLOW.get(i + 1).equals(to);
    }

    public static boolean isValidCraneType(String type) {
        return type != null && CRANE_TYPE_NAME.containsKey(type);
    }

    public static String craneTypeName(String type) {
        return type == null ? "" : CRANE_TYPE_NAME.getOrDefault(type, type);
    }

    public static boolean isValidCraneStatus(String status) {
        return status != null && CRANE_STATUS_NAME.containsKey(status);
    }

    public static String craneStatusName(String status) {
        return status == null ? "" : CRANE_STATUS_NAME.getOrDefault(status, status);
    }

    public static boolean isValidErectionStatus(String status) {
        return status != null && ERECTION_STATUS_NAME.containsKey(status);
    }

    public static String erectionStatusName(String status) {
        return status == null ? "" : ERECTION_STATUS_NAME.getOrDefault(status, status);
    }

    public static boolean canTransferErection(String from, String to) {
        return ERECTION_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    public static boolean isValidLiftStatus(String status) {
        return status != null && LIFT_STATUS_NAME.containsKey(status);
    }

    public static String liftStatusName(String status) {
        return status == null ? "" : LIFT_STATUS_NAME.getOrDefault(status, status);
    }

    public static String scheduleActionName(String action) {
        return action == null ? "" : SCHEDULE_ACTION_NAME.getOrDefault(action, action);
    }

    public static boolean isValidSurveyItem(String item) {
        return item != null && SURVEY_ITEM_NAME.containsKey(item);
    }

    public static String surveyItemName(String item) {
        return item == null ? "" : SURVEY_ITEM_NAME.getOrDefault(item, item);
    }

    public static boolean isValidSurveyResult(String result) {
        return result != null && SURVEY_RESULT_NAME.containsKey(result);
    }

    public static String surveyResultName(String result) {
        return result == null ? "" : SURVEY_RESULT_NAME.getOrDefault(result, result);
    }

    public static boolean isValidAuthority(String authority) {
        return authority != null && AUTHORITY_NAME.containsKey(authority);
    }

    public static String authorityName(String authority) {
        return authority == null ? "" : AUTHORITY_NAME.getOrDefault(authority, authority);
    }

    public static boolean isValidPunchSeverity(String severity) {
        return severity != null && PUNCH_SEVERITY_NAME.containsKey(severity);
    }

    public static String punchSeverityName(String severity) {
        return severity == null ? "" : PUNCH_SEVERITY_NAME.getOrDefault(severity, severity);
    }

    public static boolean isValidPunchStatus(String status) {
        return status != null && PUNCH_STATUS_NAME.containsKey(status);
    }

    public static String punchStatusName(String status) {
        return status == null ? "" : PUNCH_STATUS_NAME.getOrDefault(status, status);
    }

    public static boolean canTransferPunch(String from, String to) {
        return PUNCH_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    public static boolean isValidClearanceStatus(String status) {
        return status != null && CLEARANCE_STATUS_NAME.containsKey(status);
    }

    public static String clearanceStatusName(String status) {
        return status == null ? "" : CLEARANCE_STATUS_NAME.getOrDefault(status, status);
    }

    public static String clearanceVerdictName(String verdict) {
        return verdict == null ? "" : CLEARANCE_VERDICT_NAME.getOrDefault(verdict, verdict);
    }

    public static String moveResultName(String result) {
        return result == null ? "" : MOVE_RESULT_NAME.getOrDefault(result, result);
    }

    public static String clearanceCheckTypeName(String checkType) {
        return checkType == null ? "" : CLEARANCE_CHECK_TYPE_NAME.getOrDefault(checkType, checkType);
    }
}
