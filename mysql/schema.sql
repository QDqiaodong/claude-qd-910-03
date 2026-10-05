SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

-- ---------------------------------------------------------------------
-- 船舶修造船厂 · 船台船坞与分段管理
-- 库名 shipyard_dock；application.yml 里 ddl-auto: none，表结构全部由本文件在 MySQL 镜像构建期导入。
-- 注意：mysql 容器是 UTC，种子数据里的时间一律写 UTC_TIMESTAMP() + INTERVAL 8 HOUR。

CREATE DATABASE IF NOT EXISTS shipyard_dock
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE shipyard_dock;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS berth_move_log;
DROP TABLE IF EXISTS launch_clearance_check;
DROP TABLE IF EXISTS launch_clearance;
DROP TABLE IF EXISTS punch_item;
DROP TABLE IF EXISTS survey;
DROP TABLE IF EXISTS crane_schedule_log;
DROP TABLE IF EXISTS lift;
DROP TABLE IF EXISTS erection;
DROP TABLE IF EXISTS crane;
DROP TABLE IF EXISTS block;
DROP TABLE IF EXISTS berth;
DROP TABLE IF EXISTS contract;
DROP TABLE IF EXISTS vessel;
DROP TABLE IF EXISTS owner;
DROP TABLE IF EXISTS code_sequence;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- 业务编码序列表（Hibernate 自定义生成器的序号来源） ----------------
CREATE TABLE code_sequence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    seq_key VARCHAR(32) NOT NULL,
    seq_scope VARCHAR(48) NOT NULL,
    seq_value BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME,
    UNIQUE KEY uk_code_sequence (seq_key, seq_scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 船东名录 ----------------
CREATE TABLE owner (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_no VARCHAR(16) NOT NULL UNIQUE,
    owner_name VARCHAR(48) NOT NULL,
    owner_type VARCHAR(16),
    contact_person VARCHAR(32),
    contact_phone VARCHAR(24),
    country VARCHAR(24),
    coop_level VARCHAR(16),
    note VARCHAR(64),
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 船舶 ----------------
CREATE TABLE vessel (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vessel_no VARCHAR(24) NOT NULL UNIQUE,
    reg_no VARCHAR(24) NOT NULL UNIQUE,
    vessel_name VARCHAR(48),
    vessel_type VARCHAR(16),
    owner_id BIGINT,
    owner_name VARCHAR(48),
    length_m DECIMAL(10,2),
    width_m DECIMAL(10,2),
    depth_m DECIMAL(10,2),
    gross_tonnage DECIMAL(12,2),
    displacement_t DECIMAL(12,2),
    build_year INT,
    status VARCHAR(16),
    note VARCHAR(64),
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 修造合同 ----------------
-- 状态流转：DRAFT 草拟 → SIGNED 已签 → PERFORMING 履行中 → SETTLED 已结算
CREATE TABLE contract (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    contract_no VARCHAR(24) NOT NULL UNIQUE,
    vessel_id BIGINT,
    vessel_no VARCHAR(24),
    vessel_name VARCHAR(48),
    owner_id BIGINT,
    owner_name VARCHAR(48),
    contract_type VARCHAR(16),
    amount DECIMAL(14,2),
    sign_date DATE,
    delivery_date DATE,
    status VARCHAR(16),
    settled_at DATETIME,
    settle_ref VARCHAR(32),
    note VARCHAR(64),
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 船台 / 船坞 / 舾装码头 ----------------
-- 状态流转：IDLE 空闲 → OCCUPIED 占用 → MAINTENANCE 检修 → IDLE 空闲
CREATE TABLE berth (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    berth_no VARCHAR(16) NOT NULL UNIQUE,
    berth_name VARCHAR(48),
    berth_type VARCHAR(16),
    length_m DECIMAL(10,2),
    width_m DECIMAL(10,2),
    capacity_t DECIMAL(12,2),
    vessel_id BIGINT,
    vessel_name VARCHAR(48),
    status VARCHAR(16),
    occupied_at DATETIME,
    location VARCHAR(32),
    note VARCHAR(64),
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 船舶移泊记录（只追加，责任追溯） ----------------
-- 每一次移泊尝试（含待排区 ↔ 船台）都在此留痕：原船台、目标船台、操作时间、结果与失败原因。
-- from_berth_* 为空表示从待排区排入；result：SUCCESS 成功 / FAIL 失败。
CREATE TABLE berth_move_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vessel_id BIGINT NOT NULL,
    vessel_no VARCHAR(24),
    vessel_name VARCHAR(48),
    from_berth_id BIGINT,
    from_berth_no VARCHAR(16),
    to_berth_id BIGINT,
    to_berth_no VARCHAR(16),
    result VARCHAR(16) NOT NULL,
    reason VARCHAR(255),
    operator VARCHAR(32),
    created_at DATETIME,
    KEY idx_move_vessel (vessel_id),
    KEY idx_move_from_berth (from_berth_id),
    KEY idx_move_to_berth (to_berth_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 船体分段 ----------------
-- block_code 由 @SegmentCode + @ValueGenerationType 在插入前生成：SEG-船号-序号
-- 状态流转：PREFAB 预制 → PAINT 涂装 → ERECTION 搭载 → CLOSURE 合拢 → SURVEY 交验
CREATE TABLE block (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    block_code VARCHAR(24) NOT NULL UNIQUE,
    vessel_id BIGINT,
    vessel_no VARCHAR(24),
    vessel_name VARCHAR(48),
    block_name VARCHAR(48),
    block_type VARCHAR(16),
    weight_t DECIMAL(10,2),
    seg_seq INT,
    status VARCHAR(16),
    painted TINYINT,
    paint_finish_date DATE,
    workshop VARCHAR(32),
    plan_start_date DATE,
    actual_finish_date DATE,
    note VARCHAR(64),
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 起重设备 ----------------
-- 状态：AVAILABLE 可用 / MAINTAINING 检修 / RETIRED 停用
CREATE TABLE crane (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    crane_no VARCHAR(16) NOT NULL UNIQUE,
    crane_name VARCHAR(48),
    crane_type VARCHAR(16),
    rated_load_t DECIMAL(10,2),
    span_m DECIMAL(10,2),
    lift_height_m DECIMAL(10,2),
    status VARCHAR(16),
    location VARCHAR(32),
    note VARCHAR(64),
    unavailable_reason VARCHAR(128),
    maintenance_from DATETIME,
    maintenance_operator VARCHAR(32),
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 搭载任务 ----------------
-- crane_id/crane_no/crane_name：当前排程设备；original_*：检修前原排程设备；
-- review_pending=true 表示原设备检修、该任务正等待管理员恢复 / 保留替代的复核决定。
-- version 是乐观锁：检修、改派、吊次开始、恢复并发时，同一任务只能有一个有效设备归属。
CREATE TABLE erection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    erection_no VARCHAR(24) NOT NULL UNIQUE,
    vessel_id BIGINT,
    vessel_no VARCHAR(24),
    vessel_name VARCHAR(48),
    block_id BIGINT,
    block_code VARCHAR(24),
    seg_seq INT,
    berth_id BIGINT,
    berth_no VARCHAR(16),
    plan_date DATE,
    status VARCHAR(16),
    crane_id BIGINT,
    crane_no VARCHAR(16),
    crane_name VARCHAR(48),
    original_crane_id BIGINT,
    original_crane_no VARCHAR(16),
    original_crane_name VARCHAR(48),
    review_pending TINYINT,
    schedule_note VARCHAR(255),
    scheduled_at DATETIME,
    version INT NOT NULL DEFAULT 0,
    note VARCHAR(64),
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 吊次 ----------------
-- status：SCHEDULED 未开始（可随排程改派） / IN_PROGRESS 进行中（禁止静默换机） /
-- DONE 已完成（历史吊次，永远归属实际执行设备，检修恢复不得倒改）。
-- current_stage：进行中当前吊装阶段；safety_handling：设备检修时对在吊吊次的安全处置记录。
CREATE TABLE lift (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lift_no VARCHAR(24) NOT NULL UNIQUE,
    erection_id BIGINT,
    erection_no VARCHAR(24),
    block_id BIGINT,
    block_code VARCHAR(24),
    crane_id BIGINT,
    crane_no VARCHAR(16),
    crane_name VARCHAR(48),
    rated_load_t DECIMAL(10,2),
    lift_weight_t DECIMAL(10,2),
    seg_seq INT,
    status VARCHAR(16),
    current_stage VARCHAR(32),
    started_at DATETIME,
    safety_handling VARCHAR(255),
    lift_time DATETIME,
    operator VARCHAR(32),
    wind_level INT,
    remark VARCHAR(64),
    created_at DATETIME,
    KEY idx_lift_crane_status (crane_id, status),
    KEY idx_lift_erection (erection_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 起重设备排程操作日志（只追加，责任追溯） ----------------
-- action_type：SCHEDULE 排程指派 / UNAVAILABLE 设备停用 / AUTO_REASSIGN 自动改派 /
-- HOLD_REVIEW 保留待复核 / REASSIGN 人工改派 / LIFT_START 吊次开始 /
-- SAFETY_HANDLING 安全处置 / RESTORE_ORIGINAL 恢复原排程 /
-- KEEP_ALTERNATIVE 保留替代设备 / RECOVER 设备恢复
CREATE TABLE crane_schedule_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    action_type VARCHAR(24) NOT NULL,
    crane_id BIGINT,
    crane_no VARCHAR(16),
    crane_name VARCHAR(48),
    other_crane_id BIGINT,
    other_crane_no VARCHAR(16),
    erection_id BIGINT,
    erection_no VARCHAR(24),
    lift_id BIGINT,
    lift_no VARCHAR(24),
    block_code VARCHAR(24),
    detail VARCHAR(255),
    operator VARCHAR(32),
    created_at DATETIME,
    KEY idx_log_crane (crane_id),
    KEY idx_log_erection (erection_id),
    KEY idx_log_lift (lift_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 报验单 ----------------
-- survey_no 由 @SurveyNo + @ValueGenerationType 在插入前生成：SV-yyyyMMdd-序号
CREATE TABLE survey (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    survey_no VARCHAR(24) NOT NULL UNIQUE,
    block_id BIGINT,
    block_code VARCHAR(24),
    vessel_id BIGINT,
    vessel_no VARCHAR(24),
    survey_item VARCHAR(16),
    survey_date DATETIME,
    authority VARCHAR(16),
    inspector VARCHAR(32),
    result VARCHAR(16),
    remark VARCHAR(64),
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 遗留项 ----------------
CREATE TABLE punch_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    punch_no VARCHAR(24) NOT NULL UNIQUE,
    survey_id BIGINT,
    survey_no VARCHAR(24),
    block_id BIGINT,
    block_code VARCHAR(24),
    title VARCHAR(64),
    description VARCHAR(128),
    severity VARCHAR(16),
    due_date DATE,
    owner VARCHAR(32),
    status VARCHAR(16),
    closed_at DATETIME,
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 下水放行单 ----------------
-- 状态流转：PENDING 待确认 → CONFIRMED 已放行 / VOID 已作废
-- snapshot_json / blockers_json 是生成时的关键快照，落库后不再改写；
-- 后续整改的每一次复核都追加到 launch_clearance_check，旧证据不被新数据覆盖。
CREATE TABLE launch_clearance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    clearance_no VARCHAR(24) NOT NULL UNIQUE,
    vessel_id BIGINT NOT NULL,
    vessel_no VARCHAR(24),
    vessel_name VARCHAR(48),
    berth_id BIGINT,
    berth_no VARCHAR(16),
    berth_name VARCHAR(48),
    contract_id BIGINT,
    contract_no VARCHAR(24),
    contract_status VARCHAR(16),
    status VARCHAR(16) NOT NULL,
    verdict VARCHAR(16) NOT NULL,
    latest_verdict VARCHAR(16),
    latest_checked_at DATETIME,
    block_count INT,
    erected_count INT,
    survey_valid_count INT,
    critical_open_count INT,
    punch_open_count INT,
    snapshot_json TEXT,
    blockers_json TEXT,
    previous_no VARCHAR(24),
    signed_by VARCHAR(32),
    signed_at DATETIME,
    generated_by VARCHAR(32),
    generated_at DATETIME,
    confirmed_by VARCHAR(32),
    confirmed_at DATETIME,
    voided_by VARCHAR(32),
    voided_at DATETIME,
    void_reason VARCHAR(128),
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 放行单复核 / 操作记录 ----------------
-- check_type：GENERATE 生成 / RECHECK 复核 / SIGN 签认 / CONFIRM 确认放行 / VOID 作废
CREATE TABLE launch_clearance_check (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    clearance_id BIGINT NOT NULL,
    check_type VARCHAR(16) NOT NULL,
    verdict VARCHAR(16),
    blockers_json TEXT,
    summary_json TEXT,
    operator VARCHAR(32),
    note VARCHAR(255),
    created_at DATETIME,
    KEY idx_clearance_check (clearance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 种子数据
-- =====================================================================

INSERT INTO owner (id, owner_no, owner_name, owner_type, contact_person, contact_phone, country, coop_level, note, created_at) VALUES
(1, 'OW-001', '华通海运集团有限公司', 'STATE',   '郑海涛', '13805321188', '中国',     'STRATEGIC', '年度框架协议，两条散货船在建',     UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 96 DAY),
(2, 'OW-002', '北方远洋船务有限公司', 'STATE',   '宋维国', '13906427766', '中国',     'KEY',       '坞检修理长期合作',                 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 92 DAY),
(3, 'OW-003', '蓝海湾航运（香港）有限公司', 'FOREIGN', '陈嘉良', '+852-98112233', '中国香港', 'NORMAL',   '油轮修理单船结算',             UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 88 DAY),
(4, 'OW-004', '江海联运物流股份公司', 'PRIVATE', '周启明', '13701239900', '中国',     'NORMAL',    '支线集装箱船加长改装',             UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 80 DAY),
(5, 'OW-005', '太湖疏浚工程有限公司', 'PRIVATE', '徐建平', '13815567722', '中国',     'KEY',       '工程船改装加长',                   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 74 DAY),
(6, 'OW-006', '星海渔业集团股份公司', 'PRIVATE', '林永康', '13905018844', '中国',     'NORMAL',    '客滚船新建，首单',                 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 66 DAY);

INSERT INTO vessel (id, vessel_no, reg_no, vessel_name, vessel_type, owner_id, owner_name, length_m, width_m, depth_m, gross_tonnage, displacement_t, build_year, status, note, created_at) VALUES
(1, 'HN2601', '2026CS0188', '华通之星',   'BULK',        1, '华通海运集团有限公司',       199.90, 32.26, 18.50, 34500.00, 81000.00, 2026, 'IN_YARD',   '76000DWT 散货船，机舱段已合拢', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 90 DAY),
(2, 'HN2602', '2026CS0189', '北远泰山',   'BULK',        2, '北方远洋船务有限公司',       225.00, 32.26, 19.60, 41200.00, 93000.00, 2026, 'IN_YARD',   '92000DWT 散货船，货舱段搭载中', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 86 DAY),
(3, 'HN2603', '2026TK0210', '蓝海油运',   'TANKER',      3, '蓝海湾航运（香港）有限公司', 179.00, 30.40, 17.20, 29800.00, 65000.00, 2026, 'IN_YARD',   '65000DWT 原油船，坞检修理',     UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 82 DAY),
(4, 'HN2604', '2026CS0211', '江海集运',   'CONTAINER',   4, '江海联运物流股份公司',       141.00, 22.80, 12.50, 15600.00, 32000.00, 2026, 'LAUNCHED',  '1100TEU 支线箱船，已下水',      UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 78 DAY),
(5, 'HN2605', '2026EG0233', '太湖浚1',    'ENGINEERING', 5, '太湖疏浚工程有限公司',        96.00, 18.20,  8.60,  6800.00, 12000.00, 2025, 'DELIVERED', '绞吸式挖泥船，改装后已交付',    UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 70 DAY),
(6, 'HN2606', '2026CS0240', '星海快航',   'PASSENGER',   6, '星海渔业集团股份公司',        78.00, 15.60,  6.80,  4200.00,  6800.00, 2026, 'IN_YARD',   '沿海客滚船，上建分段预制中',    UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 62 DAY),
(7, 'HN2607', '2026CS0255', '华通远景',   'BULK',        1, '华通海运集团有限公司',       190.00, 32.26, 18.00, 33100.00, 78000.00, 2026, 'IN_YARD',   '同系列第二条，尚未排船台',      UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 58 DAY),
(8, 'HN2608', '2026TK0266', '江海油运6',  'TANKER',      4, '江海联运物流股份公司',       166.00, 28.00, 15.40, 24500.00, 52000.00, 2026, 'IN_YARD',   '成品油船，坞修待排期',          UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 54 DAY);

INSERT INTO contract (id, contract_no, vessel_id, vessel_no, vessel_name, owner_id, owner_name, contract_type, amount, sign_date, delivery_date, status, settled_at, settle_ref, note, created_at) VALUES
(1, 'CT-2026-011', 1, 'HN2601', '华通之星',  1, '华通海运集团有限公司',       'NEW_BUILD',  38600.00, '2026-01-18', '2026-09-30', 'PERFORMING', NULL, NULL, '含机电舾装总包',                       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 80 DAY),
(2, 'CT-2026-012', 2, 'HN2602', '北远泰山',  2, '北方远洋船务有限公司',       'NEW_BUILD',  41200.00, '2026-01-25', '2026-10-15', 'PERFORMING', NULL, NULL, '分段建造 + 船台合拢',                  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 76 DAY),
(3, 'CT-2026-013', 3, 'HN2603', '蓝海油运',  3, '蓝海湾航运（香港）有限公司', 'REPAIR',      2600.00, '2026-03-02', '2026-05-20', 'SETTLED',    UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 DAY, 'SV-20260425-001', '坞检修理，已凭报验通过单结算', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 64 DAY),
(4, 'CT-2026-014', 4, 'HN2604', '江海集运',  4, '江海联运物流股份公司',       'REPAIR',      1850.00, '2026-03-10', '2026-06-10', 'SIGNED',     NULL, NULL, '螺旋桨与舵系检修',                     UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
(5, 'CT-2026-015', 5, 'HN2605', '太湖浚1',   5, '太湖疏浚工程有限公司',       'CONVERSION',  3200.00, '2025-11-08', '2026-04-30', 'PERFORMING', NULL, NULL, '船体加长 12m 改装',                    UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 56 DAY),
(6, 'CT-2026-016', 6, 'HN2606', '星海快航',  6, '星海渔业集团股份公司',       'NEW_BUILD',   5600.00, '2026-04-02', '2026-12-20', 'DRAFT',      NULL, NULL, '条款待船东确认',                       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
(7, 'CT-2026-017', 1, 'HN2601', '华通之星',  1, '华通海运集团有限公司',       'REPAIR',       980.00, '2026-04-05', '2026-07-15', 'SETTLED',    UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 DAY,  'SV-20260427-001', '艏部球鼻艏磕碰修理，已结算',     UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 46 DAY),
(8, 'CT-2026-018', 8, 'HN2608', '江海油运6', 4, '江海联运物流股份公司',       'REPAIR',      1450.00, '2026-04-12', '2026-08-30', 'SIGNED',     NULL, NULL, '货油舱涂层翻修',                       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY);

INSERT INTO berth (id, berth_no, berth_name, berth_type, length_m, width_m, capacity_t, vessel_id, vessel_name, status, occupied_at, location, note, created_at) VALUES
(1, 'BT-01', '一号船台',   'SLIPWAY', 240.00, 42.00, 120000.00, 1,    '华通之星', 'OCCUPIED',    UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 70 DAY, '厂区东岸', '主力船台，配 600t 龙门吊', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 120 DAY),
(2, 'BT-02', '二号船台',   'SLIPWAY', 210.00, 38.00,  95000.00, NULL, NULL,       'IDLE',        NULL,                                                '厂区东岸', '可排 180m 以下船舶',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 118 DAY),
(3, 'BT-03', '三号船台',   'SLIPWAY', 180.00, 32.00,  60000.00, NULL, NULL,       'IDLE',        NULL,                                                '厂区东岸', '小型船舶与工程船',         UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 116 DAY),
(4, 'DD-01', '一号船坞',   'DRYDOCK', 280.00, 45.00, 150000.00, 3,    '蓝海油运', 'OCCUPIED',    UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 64 DAY, '厂区西岸', '坞修主力，可进 15 万吨',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 114 DAY),
(5, 'DD-02', '二号船坞',   'DRYDOCK', 230.00, 40.00, 110000.00, NULL, NULL,       'MAINTENANCE', NULL,                                                '厂区西岸', '坞门检修中，暂停排船',     UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 112 DAY),
(6, 'DD-03', '三号船坞',   'DRYDOCK', 320.00, 52.00, 200000.00, NULL, NULL,       'IDLE',        NULL,                                                '厂区西岸', '最大坞，留给 VLCC',        UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 110 DAY),
(7, 'WH-01', '舾装码头A',  'WHARF',   260.00, 34.00, 120000.00, 2,    '北远泰山', 'OCCUPIED',    UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 58 DAY, '舾装码头', '下水后舾装与调试',         UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 108 DAY),
(8, 'WH-02', '舾装码头B',  'WHARF',   200.00, 26.00,  70000.00, NULL, NULL,       'IDLE',        NULL,                                                '舾装码头', '备用泊位',                 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 106 DAY);

INSERT INTO block (id, block_code, vessel_id, vessel_no, vessel_name, block_name, block_type, weight_t, seg_seq, status, painted, paint_finish_date, workshop, plan_start_date, actual_finish_date, note, created_at) VALUES
(1,  'SEG-HN2601-001', 1, 'HN2601', '华通之星', '机舱底部',   'BOTTOM', 186.50, 1, 'CLOSURE',  1, '2026-03-28', '内场一跨', '2026-02-16', NULL, '含主机座与双层底', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 68 DAY),
(2,  'SEG-HN2601-002', 1, 'HN2601', '华通之星', '左舷边舱',   'SIDE',   142.30, 2, 'CLOSURE',  1, '2026-03-30', '内场一跨', '2026-02-20', NULL, '与机舱段同批合拢', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 66 DAY),
(3,  'SEG-HN2601-003', 1, 'HN2601', '华通之星', '主甲板',     'DECK',    98.70, 3, 'ERECTION', 1, '2026-04-02', '内场二跨', '2026-03-02', NULL, '含舱口围板',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 62 DAY),
(4,  'SEG-HN2601-004', 1, 'HN2601', '华通之星', '球鼻艏',     'BOW',    210.40, 4, 'PAINT',    0, NULL,         '内场二跨', '2026-03-10', NULL, '线型复杂，翻身两次', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 58 DAY),
(5,  'SEG-HN2601-005', 1, 'HN2601', '华通之星', '右舷边舱',   'SIDE',   138.90, 5, 'PREFAB',   0, NULL,         '内场一跨', '2026-03-18', NULL, '胎架上组立中',     UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 54 DAY),
(6,  'SEG-HN2601-006', 1, 'HN2601', '华通之星', '上层建筑',   'SUPER',   76.20, 6, 'PREFAB',   0, NULL,         '内场三跨', '2026-04-01', NULL, '整体吊装，含内舾装', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
(7,  'SEG-HN2602-001', 2, 'HN2602', '北远泰山', '货舱底部',   'BOTTOM', 178.40, 1, 'CLOSURE',  1, '2026-03-22', '内场一跨', '2026-02-10', NULL, '第一货舱双层底',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 64 DAY),
(8,  'SEG-HN2602-002', 2, 'HN2602', '北远泰山', '左舷边舱',   'SIDE',   133.60, 2, 'PAINT',    0, NULL,         '内场二跨', '2026-03-06', NULL, '涂装中，待报完工', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
(9,  'SEG-HN2602-003', 2, 'HN2602', '北远泰山', '艏楼甲板',   'DECK',    92.10, 3, 'PREFAB',   0, NULL,         '内场三跨', '2026-03-25', NULL, '含锚系布置',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 56 DAY),
(10, 'SEG-HN2603-001', 3, 'HN2603', '蓝海油运', '双层底',     'BOTTOM', 165.80, 1, 'ERECTION', 1, '2026-04-05', '内场一跨', '2026-03-01', NULL, '坞修更换段',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 52 DAY),
(11, 'SEG-HN2603-002', 3, 'HN2603', '蓝海油运', '边舱',       'SIDE',   121.50, 2, 'PAINT',    0, NULL,         '内场二跨', '2026-03-12', NULL, '腐蚀板换新增厚',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 48 DAY),
(12, 'SEG-HN2603-003', 3, 'HN2603', '蓝海油运', '艏尖舱',     'BOW',    156.30, 3, 'PREFAB',   0, NULL,         '内场二跨', '2026-03-20', NULL, '含锚链筒',         UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 44 DAY);

INSERT INTO crane (id, crane_no, crane_name, crane_type, rated_load_t, span_m, lift_height_m, status, location, note, unavailable_reason, maintenance_from, maintenance_operator, created_at) VALUES
(1, 'CR-01', '600t 龙门吊',   'GANTRY',   600.00, 182.00, 72.00, 'AVAILABLE',   '一号船台', '船台主力吊装设备',     NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 120 DAY),
(2, 'CR-02', '300t 龙门吊',   'GANTRY',   300.00, 148.00, 62.00, 'AVAILABLE',   '二号船台', '分段翻身与搭载',       NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 118 DAY),
(3, 'CR-03', '150t 履带吊',   'CRAWLER',  150.00,  30.00, 46.00, 'AVAILABLE',   '内场',     '机动补位，场地转运',   NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 116 DAY),
(4, 'CR-04', '80t 塔吊',      'TOWER',     80.00,  60.00, 55.00, 'AVAILABLE',   '三号船台', '小分段组立',           NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 114 DAY),
(5, 'CR-05', '1200t 浮吊',    'FLOATING', 1200.00,  36.00, 80.00, 'AVAILABLE',  '舾装码头', '大段整体吊装',         NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 112 DAY),
(6, 'CR-06', '300t 龙门吊B',  'GANTRY',   300.00, 148.00, 62.00, 'MAINTAINING', '二号船台', '行走机构检修中',       '行走机构异响，返厂更换减速箱，计划停用两周', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, '马国栋', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 110 DAY);

INSERT INTO erection (id, erection_no, vessel_id, vessel_no, vessel_name, block_id, block_code, seg_seq, berth_id, berth_no, plan_date, status, crane_id, crane_no, crane_name, original_crane_id, original_crane_no, original_crane_name, review_pending, schedule_note, scheduled_at, note, created_at) VALUES
(1, 'ERE-HN2601-001', 1, 'HN2601', '华通之星', 1,  'SEG-HN2601-001', 1, 1, 'BT-01', '2026-04-16', 'DONE',    1, 'CR-01', '600t 龙门吊', NULL, NULL, NULL, 0, NULL, NULL, '机舱底部定位合拢', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
(2, 'ERE-HN2601-002', 1, 'HN2601', '华通之星', 2,  'SEG-HN2601-002', 2, 1, 'BT-01', '2026-04-17', 'DONE',    1, 'CR-01', '600t 龙门吊', NULL, NULL, NULL, 0, NULL, NULL, '左舷边舱对接',     UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 39 DAY),
(3, 'ERE-HN2601-003', 1, 'HN2601', '华通之星', 3,  'SEG-HN2601-003', 3, 1, 'BT-01', '2026-04-20', 'DONE',    2, 'CR-02', '300t 龙门吊', NULL, NULL, NULL, 0, NULL, NULL, '主甲板铺装',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 36 DAY),
(4, 'ERE-HN2602-001', 2, 'HN2602', '北远泰山', 7,  'SEG-HN2602-001', 1, 7, 'WH-01', '2026-04-18', 'DONE',    5, 'CR-05', '1200t 浮吊',  NULL, NULL, NULL, 0, NULL, NULL, '货舱底部定位',     UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 38 DAY),
(5, 'ERE-HN2602-002', 2, 'HN2602', '北远泰山', 8,  'SEG-HN2602-002', 2, 7, 'WH-01', '2026-10-05', 'PLANNED', 2, 'CR-02', '300t 龙门吊', NULL, NULL, NULL, 0, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, '待左舷边舱涂装完工', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 34 DAY),
(6, 'ERE-HN2603-001', 3, 'HN2603', '蓝海油运', 10, 'SEG-HN2603-001', 1, 4, 'DD-01', '2026-04-19', 'DONE',    5, 'CR-05', '1200t 浮吊',  NULL, NULL, NULL, 0, NULL, NULL, '坞内双层底换新',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 37 DAY),
(7, 'ERE-HN2603-002', 3, 'HN2603', '蓝海油运', 11, 'SEG-HN2603-002', 2, 4, 'DD-01', '2026-10-04', 'READY',   2, 'CR-02', '300t 龙门吊', NULL, NULL, NULL, 0, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, '边舱已就位，翻身吊装进行中', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 35 DAY),
(8, 'ERE-HN2601-004', 1, 'HN2601', '华通之星', 4,  'SEG-HN2601-004', 4, 1, 'BT-01', '2026-10-06', 'PLANNED', 1, 'CR-01', '600t 龙门吊', NULL, NULL, NULL, 0, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, '球鼻艏待涂装完工', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 33 DAY);

INSERT INTO lift (id, lift_no, erection_id, erection_no, block_id, block_code, crane_id, crane_no, crane_name, rated_load_t, lift_weight_t, seg_seq, status, current_stage, started_at, safety_handling, lift_time, operator, wind_level, remark, created_at) VALUES
(1,  'LF-20260416-001', 1, 'ERE-HN2601-001', 1,  'SEG-HN2601-001', 1, 'CR-01', '600t 龙门吊', 600.00,  186.50, 1, 'DONE', NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY + INTERVAL 2 HOUR,  '刘长顺', 3, '首吊，一次定位成功',     UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
(2,  'LF-20260417-001', 2, 'ERE-HN2601-002', 2,  'SEG-HN2601-002', 1, 'CR-01', '600t 龙门吊', 600.00,  142.30, 2, 'DONE', NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 39 DAY + INTERVAL 3 HOUR,  '刘长顺', 4, '左侧对接，配两只手拉葫芦', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 39 DAY),
(3,  'LF-20260418-001', 4, 'ERE-HN2602-001', 7,  'SEG-HN2602-001', 5, 'CR-05', '1200t 浮吊', 1200.00, 178.40, 1, 'DONE', NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 38 DAY + INTERVAL 1 HOUR,  '吴德海', 5, '浮吊跨码头作业',           UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 38 DAY),
(4,  'LF-20260419-001', 6, 'ERE-HN2603-001', 10, 'SEG-HN2603-001', 5, 'CR-05', '1200t 浮吊', 1200.00, 165.80, 1, 'DONE', NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 37 DAY + INTERVAL 2 HOUR,  '吴德海', 3, '坞内换新段落位',           UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 37 DAY),
(5,  'LF-20260420-001', 3, 'ERE-HN2601-003', 3,  'SEG-HN2601-003', 2, 'CR-02', '300t 龙门吊', 300.00,   98.70, 3, 'DONE', NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 36 DAY + INTERVAL 4 HOUR,  '孙明远', 4, '主甲板分三段吊装',         UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 36 DAY),
(9,  'LF-20260424-001', 4, 'ERE-HN2602-001', 7,  'SEG-HN2602-001', 1, 'CR-01', '600t 龙门吊', 600.00,   96.20, 1, 'DONE', NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 32 DAY + INTERVAL 1 HOUR,  '吴德海', 3, '二次翻身吊装附件',         UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 32 DAY),
(10, 'LF-20260425-001', 6, 'ERE-HN2603-001', 10, 'SEG-HN2603-001', 1, 'CR-01', '600t 龙门吊', 600.00,  88.40, 1, 'DONE', NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 31 DAY + INTERVAL 2 HOUR,  '刘长顺', 4, '补吊管系单元',             UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 31 DAY),
(11, 'LF-20260426-001', 1, 'ERE-HN2601-001', 1,  'SEG-HN2601-001', 3, 'CR-03', '150t 履带吊', 150.00,  96.50, 1, 'DONE', NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY + INTERVAL 6 HOUR,  '赵文斌', 5, '主机座二次灌浆前吊装',     UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY),
-- 进行中吊次：设备进入检修时不得换机，只允许记录安全处置，归属与当前阶段冻结
(6,  'LF-20261004-001', 7, 'ERE-HN2603-002', 11, 'SEG-HN2603-002', 2, 'CR-02', '300t 龙门吊', 300.00, 121.50, 2, 'IN_PROGRESS', '空中翻身', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR, '孙明远', 4, '边舱翻身一次后吊装，按计划今日落位', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
-- 未开始吊次：跟随搭载排程，原设备检修时可自动改派或保留待复核
(12, 'LF-20261005-001', 5, 'ERE-HN2602-002', 8,  'SEG-HN2602-002', 2, 'CR-02', '300t 龙门吊', 300.00, 133.60, 2, 'SCHEDULED', NULL, NULL, NULL, '2026-10-05 00:00:00', '孙明远', NULL, '预留合拢口 50mm',          UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(13, 'LF-20261006-001', 8, 'ERE-HN2601-004', 4,  'SEG-HN2601-004', 1, 'CR-01', '600t 龙门吊', 600.00, 210.40, 4, 'SCHEDULED', NULL, NULL, NULL, '2026-10-06 00:00:00', '刘长顺', NULL, '球鼻艏翻身吊装，预计载荷 36%', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY);

INSERT INTO survey (id, survey_no, block_id, block_code, vessel_id, vessel_no, survey_item, survey_date, authority, inspector, result, remark, created_at) VALUES
(1,  'SV-20260418-001', 1,  'SEG-HN2601-001', 1, 'HN2601', 'WELD',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 38 DAY, 'CCS',   '钱广安', 'PASS',       '纵骨角焊缝 UT 探伤一次合格',      UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 38 DAY),
(2,  'SV-20260419-001', 2,  'SEG-HN2601-002', 1, 'HN2601', 'DIMENSION',  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 37 DAY, 'CCS',   '钱广安', 'PASS',       '主尺度偏差 3mm，在公差内',        UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 37 DAY),
(3,  'SV-20260420-001', 7,  'SEG-HN2602-001', 2, 'HN2602', 'WELD',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 36 DAY, 'OWNER', '周立群', 'CONCESSION', '两处咬边，让步接收要求补磨',      UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 36 DAY),
(4,  'SV-20260421-001', 10, 'SEG-HN2603-001', 3, 'HN2603', 'COATING',    UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 35 DAY, 'LR',    '周立群', 'FAIL',       '膜厚普遍不足，需返工重涂',        UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 35 DAY),
(5,  'SV-20260422-001', 1,  'SEG-HN2601-001', 1, 'HN2601', 'TIGHTNESS',  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 34 DAY, 'CCS',   '钱广安', 'PASS',       '双层底舱室冲水试验无渗漏',        UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 34 DAY),
(6,  'SV-20260423-001', 3,  'SEG-HN2601-003', 1, 'HN2601', 'OUTFITTING', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 33 DAY, 'OWNER', '孙佩林', 'PASS',       '甲板舾装件清点齐全',              UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 33 DAY),
(7,  'SV-20260424-001', 8,  'SEG-HN2602-002', 2, 'HN2602', 'COATING',    UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 32 DAY, 'ABS',   '周立群', 'FAIL',       '舷侧外板漏涂两处，需补涂',        UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 32 DAY),
(8,  'SV-20260425-001', 11, 'SEG-HN2603-002', 3, 'HN2603', 'DIMENSION',  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 31 DAY, 'LR',    '孙佩林', 'PASS',       '边舱线型复测合格，同意进入搭载',  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 31 DAY),
(9,  'SV-20260426-001', 4,  'SEG-HN2601-004', 1, 'HN2601', 'WELD',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, 'CCS',   '钱广安', 'CONCESSION', '球鼻艏三处气孔，让步使用限期返修', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY),
(10, 'SV-20260427-001', 5,  'SEG-HN2601-005', 1, 'HN2601', 'WELD',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 29 DAY, 'CCS',   '孙佩林', 'PASS',       '右舷边舱胎上焊缝抽检合格',        UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 29 DAY);

INSERT INTO punch_item (id, punch_no, survey_id, survey_no, block_id, block_code, title, description, severity, due_date, owner, status, closed_at, created_at) VALUES
(1, 'PI-20260421-001-01', 4, 'SV-20260421-001', 10, 'SEG-HN2603-001', '双层底涂层膜厚不足', '实测 180μm 低于 250μm 要求，需整体扫砂重涂', 'MAJOR',    '2026-05-06', '王立柱', 'OPEN',       NULL,                                                UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 35 DAY),
(2, 'PI-20260421-001-02', 4, 'SV-20260421-001', 10, 'SEG-HN2603-001', '焊缝飞溅未打磨',     '焊后飞溅与焊渣未清理，影响涂装附着力',       'MINOR',    '2026-05-04', '王立柱', 'CLOSED',     UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 35 DAY),
(3, 'PI-20260421-001-03', 4, 'SV-20260421-001', 10, 'SEG-HN2603-001', '舱室焊后积水未清',   '双层底舱内积水未抽干，需干燥后复检',         'MINOR',    '2026-05-08', '赵文斌', 'RECTIFYING', NULL,                                                UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 35 DAY),
(4, 'PI-20260424-001-01', 7, 'SV-20260424-001', 8,  'SEG-HN2602-002', '舷侧外板漏涂',       '外板两处漏涂，需打磨补涂并复测膜厚',         'MAJOR',    '2026-05-12', '陈兆丰', 'RECTIFYING', NULL,                                                UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 32 DAY),
(5, 'PI-20260424-001-02', 7, 'SV-20260424-001', 8,  'SEG-HN2602-002', '管系支架未固定',     '三段管系临时支撑未正式固定，需补焊支架',     'MINOR',    '2026-05-15', '陈兆丰', 'OPEN',       NULL,                                                UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 32 DAY),
(6, 'PI-20260420-001-01', 3, 'SV-20260420-001', 7,  'SEG-HN2602-001', '货舱底咬边补磨',     '两处咬边补磨后复检，磨除深度不超过板厚 5%',  'MINOR',    '2026-04-28', '赵文斌', 'CLOSED',     UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 28 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 36 DAY),
(7, 'PI-20260426-001-01', 9, 'SV-20260426-001', 4,  'SEG-HN2601-004', '球鼻艏焊缝返修',     '三处气孔需清除后补焊，返修后重新 UT 探伤',   'MAJOR',    '2026-05-20', '王立柱', 'OPEN',       NULL,                                                UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY),
(8, 'PI-20260426-001-02', 9, 'SV-20260426-001', 4,  'SEG-HN2601-004', '锚链筒定位偏差',     '锚链筒中心线偏差 8mm，需重新定位焊接',       'CRITICAL', '2026-05-22', '王立柱', 'OPEN',       NULL,                                                UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY);

-- ---------------- 编码序号初值（和上面已用的号对齐，保证后续新增不撞号） ----------------
INSERT INTO code_sequence (seq_key, seq_scope, seq_value, updated_at) VALUES
('SEGMENT_CODE', 'HN2601', 6, UTC_TIMESTAMP() + INTERVAL 8 HOUR),
('SEGMENT_CODE', 'HN2602', 3, UTC_TIMESTAMP() + INTERVAL 8 HOUR),
('SEGMENT_CODE', 'HN2603', 3, UTC_TIMESTAMP() + INTERVAL 8 HOUR),
('SURVEY_NO', '20260418', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR),
('SURVEY_NO', '20260419', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR),
('SURVEY_NO', '20260420', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR),
('SURVEY_NO', '20260421', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR),
('SURVEY_NO', '20260422', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR),
('SURVEY_NO', '20260423', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR),
('SURVEY_NO', '20260424', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR),
('SURVEY_NO', '20260425', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR),
('SURVEY_NO', '20260426', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR),
('SURVEY_NO', '20260427', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR),
('ERECTION_NO', 'HN2601', 4, UTC_TIMESTAMP() + INTERVAL 8 HOUR),
('ERECTION_NO', 'HN2602', 2, UTC_TIMESTAMP() + INTERVAL 8 HOUR),
('ERECTION_NO', 'HN2603', 2, UTC_TIMESTAMP() + INTERVAL 8 HOUR),
('PUNCH_NO', '20260420-001', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR),
('PUNCH_NO', '20260421-001', 3, UTC_TIMESTAMP() + INTERVAL 8 HOUR),
('PUNCH_NO', '20260424-001', 2, UTC_TIMESTAMP() + INTERVAL 8 HOUR),
('PUNCH_NO', '20260426-001', 2, UTC_TIMESTAMP() + INTERVAL 8 HOUR);

-- ---------------- 起重设备排程操作日志（种子，责任追溯链样例） ----------------
INSERT INTO crane_schedule_log (id, action_type, crane_id, crane_no, crane_name, other_crane_id, other_crane_no, erection_id, erection_no, lift_id, lift_no, block_code, detail, operator, created_at) VALUES
(1, 'SCHEDULE',      2, 'CR-02', '300t 龙门吊', NULL, NULL,    5, 'ERE-HN2602-002', 12, 'LF-20261005-001', 'SEG-HN2602-002', '边舱搭载排程指派 CR-02，计划 2026-10-05', '马国栋', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(2, 'SCHEDULE',      1, 'CR-01', '600t 龙门吊', NULL, NULL,    8, 'ERE-HN2601-004', 13, 'LF-20261006-001', 'SEG-HN2601-004', '球鼻艏搭载排程指派 CR-01，计划 2026-10-06', '马国栋', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(3, 'SCHEDULE',      2, 'CR-02', '300t 龙门吊', NULL, NULL,    7, 'ERE-HN2603-002', 6,  'LF-20261004-001', 'SEG-HN2603-002', '边舱翻身吊装排程指派 CR-02，计划 2026-10-04', '马国栋', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(4, 'LIFT_START',    2, 'CR-02', '300t 龙门吊', NULL, NULL,    7, 'ERE-HN2603-002', 6,  'LF-20261004-001', 'SEG-HN2603-002', '吊次开始，当前阶段：起钩', '孙明远', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR),
(5, 'UNAVAILABLE',   6, 'CR-06', '300t 龙门吊B', NULL, NULL,  NULL, NULL,             NULL, NULL,             NULL,             '设备置为检修：行走机构异响，返厂更换减速箱', '马国栋', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY);
