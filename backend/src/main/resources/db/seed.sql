-- =====================================================================
-- 种子数据：五个必答场景的基础主数据
--   A 跨修订序列号区间重叠（DRW-MOTOR-100 rev A/B 重叠，SN-0018 命中）
--   B 迟到拆装凭证（2024-08-20 事件，2025-03-01 才接收）
--   C 同名异件（两个“伺服驱动单元” SDU-A1 / SDU-B2，禁止自动合并）
--   D 规则撤回（SUB-RULE-001 被供应商收回，已签发结论保留锁定版本）
--   E 两条约束联动（电源+协议 / 电源+尺寸，同时满足才 PASS）
--   附加：同一实物 PP-PWR-0001 重叠时间段出现在两台设备 -> 冻结裁定
-- 派生数据（快照/冲突/兼容事实/影响分析/质量事件）由后端 DemoDataInitializer 生成
-- =====================================================================

-- 0. 账号（密码哈希 = sha256("mdm-salt::" + 明文)）
INSERT INTO app_user (id, username, display_name, password_hash, role, team) VALUES
 (1,'admin','平台管理员','39258977696fccfb3adb52d5480343c42f529cc34e7fff4919b9a924bd62178c','ADMIN','主数据平台组'),
 (2,'engineer','工程数据工程师','cb2ee072893e0b06e6ee0f0fc06011ebc8483fa40cc008e7dd8b8e21de17ba6c','ENGINEER','工程数据组'),
 (3,'viewer','售后查询员','b96e4ba3a4001770864be1efe39aca2a72cf539bbb81ac5ff24a51ce141117dd','VIEWER','售后服务组');
SELECT setval('app_user_id_seq', 10);

-- 1. 接入契约
INSERT INTO ingestion_contract (id, contract_code, contract_name, source_team, source_system, schema_version, effective_from, status, field_spec, trust_score, created_by) VALUES
 (1,'CT-DRAWING','工程图号接入契约','工程数据组','PLM','v3','2024-01-01T00:00:00Z','VERIFIED',
  '[{"field":"drawingNo","type":"string","required":true,"meaning":"工程图号"},{"field":"revision","type":"string","required":true,"meaning":"修订版本"},{"field":"serialRange","type":"range","required":true,"meaning":"适用序列号区间"},{"field":"effectiveWindow","type":"timeRange","required":true,"meaning":"生效时间边界"}]',90,'admin'),
 (2,'CT-RULE','供应商替代规则接入契约','采购认证组','SRM','v2','2024-01-01T00:00:00Z','VERIFIED',
  '[{"field":"ruleCode","type":"string","required":true,"meaning":"规则编号"},{"field":"fromPart","type":"string","required":true,"meaning":"被替代件型号"},{"field":"toPart","type":"string","required":true,"meaning":"替代件型号(方向)"},{"field":"constraints","type":"array","required":true,"meaning":"协议/尺寸/电源约束"},{"field":"coSubstitution","type":"array","required":false,"meaning":"联换条件"}]',80,'admin'),
 (3,'CT-VOUCHER','实装拆装凭证接入契约','售后维修组','MES-CS','v4','2024-01-01T00:00:00Z','VERIFIED',
  '[{"field":"voucherNo","type":"string","required":true,"meaning":"凭证号"},{"field":"equipmentSn","type":"string","required":true,"meaning":"设备序列号"},{"field":"position","type":"string","required":true,"meaning":"装配位置"},{"field":"physicalSn","type":"string","required":true,"meaning":"实物序列号"},{"field":"eventType","type":"enum","required":true,"meaning":"INSTALL/REMOVE"},{"field":"eventTime","type":"datetime","required":true,"meaning":"实际拆装时间"}]',70,'admin'),
 (4,'CT-INSPECTION','检验数据接入契约','质量检验组','QMS','v2','2024-01-01T00:00:00Z','VERIFIED',
  '[{"field":"inspectionCode","type":"string","required":true,"meaning":"检验单号"},{"field":"testItem","type":"string","required":true,"meaning":"测试项"},{"field":"result","type":"enum","required":true,"meaning":"PASS/FAIL/CONDITIONAL"},{"field":"testReportRef","type":"string","required":true,"meaning":"测试报告归档号"}]',85,'admin');
SELECT setval('ingestion_contract_id_seq', 10);

INSERT INTO ingestion_contract_version (contract_code, schema_version, source_team, source_ref, payload_digest, field_spec, trust_score) VALUES
 ('CT-DRAWING','v3','工程数据组','plm/export/2024Q1/drawings.zip','sha256:d3a1f0c9drawing','[]'::jsonb,90),
 ('CT-RULE','v2','采购认证组','srm/rules-2024.json','sha256:9b2e77rule','[]'::jsonb,80),
 ('CT-VOUCHER','v4','售后维修组','mes/vouchers/stream','sha256:44c81voucher','[]'::jsonb,70),
 ('CT-INSPECTION','v2','质量检验组','qms/inspection/2024','sha256:77f0inspect','[]'::jsonb,85);

-- 2. 五类实体 ---------------------------------------------------------
-- 供应商
INSERT INTO master_entity (id, entity_type, entity_code, display_name, spec_payload, status) VALUES
 (1,'SUPPLIER','SUP-SIEMENS','西门子（中国）有限公司','{"country":"DE"}','VERIFIED'),
 (2,'SUPPLIER','SUP-DELTA','台达电子工业股份有限公司','{"country":"CN"}','VERIFIED'),
 (3,'SUPPLIER','SUP-SANYO','山洋电气株式会社','{"country":"JP"}','VERIFIED');
-- 设备序列号
INSERT INTO master_entity (id, entity_type, entity_code, display_name, spec_payload, status) VALUES
 (10,'EQUIPMENT','EQ-CNC-2024-0007','五轴加工中心 0007','{"factorySerialNo":"SN-0007","model":"CNC-X5"}','VERIFIED'),
 (11,'EQUIPMENT','EQ-CNC-2024-0008','五轴加工中心 0008','{"factorySerialNo":"SN-0008","model":"CNC-X5"}','VERIFIED'),
 (12,'EQUIPMENT','EQ-CNC-2024-0018','五轴加工中心 0018','{"factorySerialNo":"SN-0018","model":"CNC-X5"}','VERIFIED');
-- 装配位置
INSERT INTO master_entity (id, entity_type, entity_code, display_name, spec_payload, status) VALUES
 (20,'MOUNT_POSITION','POS-MAIN-SPINDLE','主轴驱动位','{"slot":"A1"}','VERIFIED'),
 (21,'MOUNT_POSITION','POS-SERVO-X','X轴伺服驱动位','{"slot":"B2"}','VERIFIED'),
 (22,'MOUNT_POSITION','POS-POWER-BOX','电源仓安装位','{"slot":"C1"}','VERIFIED'),
 (23,'MOUNT_POSITION','POS-COOLANT','冷却泵安装位','{"slot":"D3"}','VERIFIED');
-- 部件型号（32/33 同名异件；规格是约束求值的唯一依据）
INSERT INTO master_entity (id, entity_type, entity_code, display_name, supplier_code, spec_payload, status) VALUES
 (30,'PART_MODEL','PM-MOTOR-100A','主轴电机 100A','SUP-SIEMENS','{"voltage":"380V","power":"15kW","rpm":"12000","dimension":"280mm"}','VERIFIED'),
 (31,'PART_MODEL','PM-MOTOR-100B','主轴电机 100B','SUP-SIEMENS','{"voltage":"380V","power":"18kW","rpm":"15000","dimension":"280mm"}','VERIFIED'),
 (32,'PART_MODEL','PM-SDU-A1','伺服驱动单元','SUP-SANYO','{"voltage":"380V","protocol":"EtherCAT","power":"2kW","dimension":"180mm"}','VERIFIED'),
 (33,'PART_MODEL','PM-SDU-B2','伺服驱动单元','SUP-DELTA','{"voltage":"220V","protocol":"CANopen","power":"1.5kW","dimension":"160mm"}','VERIFIED'),
 (34,'PART_MODEL','PM-SDU-C3','伺服驱动单元 C3','SUP-DELTA','{"voltage":"380V","protocol":"EtherCAT","power":"2kW","dimension":"180mm"}','VERIFIED'),
 (35,'PART_MODEL','PM-PWR-500','工业电源模块 500','SUP-SIEMENS','{"voltage":"380V","dimension":"2U","interface":"DIN"}','VERIFIED'),
 (36,'PART_MODEL','PM-PWR-501','工业电源模块 501','SUP-SIEMENS','{"voltage":"380V","dimension":"2U","interface":"DIN"}','VERIFIED'),
 (37,'PART_MODEL','PM-PWR-502','工业电源模块 502','SUP-DELTA','{"voltage":"380V","dimension":"3U","interface":"DIN"}','VERIFIED'),
 (38,'PART_MODEL','PM-PUMP-200','冷却泵 200','SUP-DELTA','{"voltage":"380V","flow":"40L/min","dimension":"120mm"}','VERIFIED');
-- 实物序列号
INSERT INTO master_entity (id, entity_type, entity_code, display_name, supplier_code, spec_payload, status) VALUES
 (50,'PHYSICAL_PART','PP-MTR-0001','主轴电机实物 0001','SUP-SIEMENS','{"modelCode":"PM-MOTOR-100A"}','VERIFIED'),
 (51,'PHYSICAL_PART','PP-MTR-0018','主轴电机实物 0018','SUP-SIEMENS','{"modelCode":"PM-MOTOR-100A"}','VERIFIED'),
 (52,'PHYSICAL_PART','PP-SDU-0001','伺服驱动实物 0001','SUP-SANYO','{"modelCode":"PM-SDU-A1"}','VERIFIED'),
 (53,'PHYSICAL_PART','PP-SDU-0002','伺服驱动实物 0002','SUP-SANYO','{"modelCode":"PM-SDU-A1"}','VERIFIED'),
 (54,'PHYSICAL_PART','PP-SDU-0003','伺服驱动实物 0003','SUP-DELTA','{"modelCode":"PM-SDU-C3"}','VERIFIED'),
 (55,'PHYSICAL_PART','PP-PWR-0001','电源模块实物 0001','SUP-SIEMENS','{"modelCode":"PM-PWR-500"}','VERIFIED'),
 (56,'PHYSICAL_PART','PP-PUMP-0001','冷却泵实物 0001','SUP-DELTA','{"modelCode":"PM-PUMP-200"}','VERIFIED'),
 (57,'PHYSICAL_PART','PP-PWR-0002','电源模块实物 0002','SUP-SIEMENS','{"modelCode":"PM-PWR-500"}','VERIFIED');
SELECT setval('master_entity_id_seq', 100);

-- 同名候选工作队列：三个“伺服驱动单元”，等待人工裁定，系统绝不自动合并
INSERT INTO entity_alias_candidate (entity_type, name_text, candidate_entity_id, target_entity_id, decision, note) VALUES
 ('PART_MODEL','伺服驱动单元',32,33,'PENDING','名称完全相同，供应商与协议不同，等待人工确认保持分立'),
 ('PART_MODEL','伺服驱动单元',32,34,'PENDING','名称相同，C3 为 A1 的认证替代型号，仍需独立建模');

-- 3. 图号与修订（rev A / rev B 序列号区间 SN-0015..SN-0020 重叠）
INSERT INTO drawing (id, drawing_no, title, part_model_id, current_revision, status) VALUES
 (1,'DRW-MOTOR-100','主轴电机总图',30,'B','VERIFIED'),
 (2,'DRW-SDU-300','伺服驱动单元安装图',32,'A','VERIFIED'),
 (3,'DRW-PWR-500','电源模块安装图',35,'A','VERIFIED');
SELECT setval('drawing_id_seq', 10);

INSERT INTO drawing_revision (id, drawing_id, revision, serial_range_from, serial_range_to, effective_from, effective_to, source_contract_id, source_ref, payload_digest, change_note, status) VALUES
 (1,1,'A','SN-0001','SN-0020','2024-01-01T00:00:00Z',NULL,1,'PLM-DRW-100-A.pdf','sha256:revA-100','初版发布','VERIFIED'),
 (2,1,'B','SN-0015','SN-0030','2024-06-01T00:00:00Z',NULL,1,'PLM-DRW-100-B.pdf','sha256:revB-100','提升功率至18kW，区间与A版重叠','VERIFIED'),
 (3,2,'A','SN-0001','SN-9999','2024-02-01T00:00:00Z',NULL,1,'PLM-DRW-300-A.pdf','sha256:revA-300','伺服安装图初版','VERIFIED'),
 (4,3,'A','SN-0001','SN-9999','2024-01-15T00:00:00Z',NULL,1,'PLM-DRW-500-A.pdf','sha256:revA-500','电源安装图初版','VERIFIED');
SELECT setval('drawing_revision_id_seq', 10);

-- 4. 检验记录（规则血缘连回的测试报告）
INSERT INTO inspection_record (id, inspection_code, equipment_id, part_model_id, physical_part_id, test_item, result, inspected_at, source_contract_id, source_ref, payload_digest, detail) VALUES
 (1,'INS-2024-0301',NULL,34,NULL,'伺服替代认证（电源380V+协议EtherCAT联动）','PASS','2024-03-01T09:00:00Z',4,'TR-2024-0301.pdf','sha256:tr0301','{"protocol":"EtherCAT","voltage":"380V","reportRef":"TR-2024-0301"}'),
 (2,'INS-2024-0810',NULL,36,NULL,'电源模块替代尺寸与电源联动测试','PASS','2024-08-10T10:00:00Z',4,'TR-2024-0810.pdf','sha256:tr0810','{"voltage":"380V","dimension":"2U","reportRef":"TR-2024-0810"}'),
 (3,'INS-2024-0811',NULL,37,NULL,'电源模块替代尺寸复核','CONDITIONAL','2024-08-11T10:00:00Z',4,'TR-2024-0811.pdf','sha256:tr0811','{"voltage":"380V","dimension":"3U","deviation":"尺寸3U超出2U仓体","reportRef":"TR-2024-0811"}');
SELECT setval('inspection_record_id_seq', 10);

-- 5. 出厂配置
INSERT INTO factory_record (id, equipment_id, mount_position_id, part_model_id, physical_part_id, factory_time, source_contract_id, source_ref, payload_digest, trust_score) VALUES
 (1,10,20,30,50,'2024-02-01T08:00:00Z',3,'FAC-EQ0007.pdf','sha256:fac0007',85),
 (2,10,21,32,52,'2024-02-01T08:00:00Z',3,'FAC-EQ0007.pdf','sha256:fac0007',85),
 (3,10,22,35,55,'2024-02-01T08:00:00Z',3,'FAC-EQ0007.pdf','sha256:fac0007',85),
 (4,11,22,35,57,'2024-05-01T08:00:00Z',3,'FAC-EQ0008.pdf','sha256:fac0008',85),
 (5,12,20,30,51,'2024-07-01T08:00:00Z',3,'FAC-EQ0018.pdf','sha256:fac0018',85);
SELECT setval('factory_record_id_seq', 10);

-- 6. 追加式实装事件（只追加；EV-INST-003 为迟到凭证；EV-INST-005/007 构成冷却泵双重安装）
INSERT INTO install_event (id, event_code, equipment_id, mount_position_id, physical_part_id, part_model_id, event_type, event_time, received_at, source_contract_id, source_ref, payload_digest, trust_score, adjudication) VALUES
 (1,'EV-INST-001',10,21,52,32,'INSTALL','2024-02-01T08:30:00Z','2024-02-01T10:00:00Z',3,'VCH-2024-0201-1','sha256:ev001',75,'NORMAL'),
 (2,'EV-REM-001',10,21,52,32,'REMOVE','2024-09-10T14:00:00Z','2024-09-10T18:00:00Z',3,'VCH-2024-0910-1','sha256:ev002',75,'NORMAL'),
 (3,'EV-INST-002',10,21,54,34,'INSTALL','2024-09-10T15:00:00Z','2024-09-10T18:30:00Z',3,'VCH-2024-0910-2','sha256:ev003',75,'NORMAL'),
 (4,'EV-INST-003',10,23,56,38,'INSTALL','2024-08-20T11:00:00Z','2025-03-01T09:00:00Z',3,'VCH-LATE-20240820','sha256:ev004-late',65,'NORMAL'),
 (5,'EV-INST-004',11,22,57,35,'INSTALL','2024-05-01T09:00:00Z','2024-05-01T12:00:00Z',3,'VCH-2024-0501-1','sha256:ev005',70,'NORMAL'),
 (6,'EV-INST-005',11,23,56,38,'INSTALL','2024-10-01T09:30:00Z','2024-10-01T12:00:00Z',3,'VCH-2024-1001-1','sha256:ev006',70,'NORMAL'),
 (7,'EV-INST-006',10,22,55,35,'INSTALL','2024-02-01T08:30:00Z','2024-02-01T10:00:00Z',3,'VCH-2024-0201-2','sha256:ev007',75,'NORMAL');
SELECT setval('install_event_id_seq', 10);

-- 7. 替代规则（规则1后被撤回；规则2两条约束联动）
INSERT INTO substitution_rule (id, rule_code, rule_version, from_part_model_id, to_part_model_id, direction_note, co_substitution, effective_from, effective_to, supplier_id, drawing_revision_id, test_report_id, source_contract_id, source_ref, status, retired_at, retire_reason) VALUES
 (1,'SUB-RULE-001','v1',32,34,'仅允许 A1 -> C3 单向替代，反向不成立','[{"modelCode":"PM-PWR-500","note":"联换：电源模块需同时核对仓体规格"}]',
  '2024-03-01T00:00:00Z',NULL,2,3,1,2,'SRM-RULE-001.pdf','VERIFIED',NULL,NULL),
 (2,'SUB-RULE-002','v1',35,36,'电源模块 500 -> 501 单向替代','[]',
  '2024-08-15T00:00:00Z',NULL,1,4,2,2,'SRM-RULE-002.pdf','VERIFIED',NULL,NULL),
 (3,'SUB-RULE-003','v1',35,37,'电源模块 500 -> 502 替代申请（尺寸 3U，与 2U 仓体约束联动判定）','[]',
  '2024-08-15T00:00:00Z',NULL,2,4,3,2,'SRM-RULE-003.pdf','VERIFIED',NULL,NULL);
SELECT setval('substitution_rule_id_seq', 10);

INSERT INTO rule_constraint (id, rule_id, constraint_type, attribute_name, expected_value, compare_op, actual_source) VALUES
 (1,1,'POWER','voltage','380V','EQ','spec.voltage'),
 (2,1,'PROTOCOL','protocol','EtherCAT','EQ','spec.protocol'),
 (3,2,'POWER','voltage','380V','EQ','spec.voltage'),
 (4,2,'DIMENSION','dimension','2U','EQ','spec.dimension'),
 (5,3,'POWER','voltage','380V','EQ','spec.voltage'),
 (6,3,'DIMENSION','dimension','2U','EQ','spec.dimension');
SELECT setval('rule_constraint_id_seq', 10);
