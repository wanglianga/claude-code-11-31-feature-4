-- =====================================================================
-- 特性4 种子数据：工程资料规则资产化（仅原始资料，派生数据由服务重放生成）
--   F1 单位缺失：ST-100A-02/ST-100A-04 缺单位 -> 待补队列
--   F1b 混合单位硬停止：DOC-SPEC-MIX 同含 mm 与 inch 且数值无单位 -> 必须停止
--   F2 区间端点冲突：ST-200B-01 跨来源区间冲突；ST-200B-02 端点倒置
--   F3 证明不足：ST-SUB-01 缺测试类型与有效期 -> 不可满足；ST-SUB-02 齐备对照
--   F4 撤销通知：DOC-SPEC-SDU revA 撤销只改变后续规则版本（v1 保留，v2 取代）
--   对照工作流：ST-PWR-01 嵌套例外被解析器拒绝 -> 决策表建模编译发布
-- =====================================================================

-- 1. 工程资料原文登记（文档编号+修订 唯一）
INSERT INTO spec_document (id, doc_code, revision, title, doc_type, source_team, source_contract_id, source_ref, payload_digest, status, registered_by) VALUES
 (1,'DOC-SPEC-100A','A','主轴电机100A技术规格书','PART_SPEC','工程数据组',1,'plm/spec/100A-revA.pdf','sha256:spec100aA','VERIFIED','engineer'),
 (2,'DOC-SPEC-MIX','A','冷却泵安装接口规范（出口机型）','INTERFACE_SPEC','工程数据组',1,'plm/spec/mix-revA.pdf','sha256:specMixA','VERIFIED','engineer'),
 (3,'DOC-SPEC-200','A','冷却泵200技术规格书','PART_SPEC','工程数据组',1,'plm/spec/200-revA.pdf','sha256:spec200A','VERIFIED','engineer'),
 (4,'DOC-SPEC-200B','A','冷却泵200安装规范（供应商版）','INSTALL_SPEC','采购认证组',2,'srm/spec/200B-revA.pdf','sha256:spec200bA','VERIFIED','engineer'),
 (5,'DOC-SPEC-SUB','A','替代条件认证规范','SUBSTITUTION_SPEC','采购认证组',2,'srm/spec/sub-revA.pdf','sha256:specSubA','VERIFIED','engineer'),
 (6,'DOC-SPEC-SDU','A','伺服驱动单元接口规范','INTERFACE_SPEC','工程数据组',1,'plm/spec/sdu-revA.pdf','sha256:specSduA','VERIFIED','engineer'),
 (7,'DOC-SPEC-SDU','B','伺服驱动单元接口规范','INTERFACE_SPEC','工程数据组',1,'plm/spec/sdu-revB.pdf','sha256:specSduB','VERIFIED','engineer'),
 (8,'DOC-SPEC-PWR','A','电源仓适配规范（含特批条款）','INSTALL_SPEC','工程数据组',1,'plm/spec/pwr-revA.pdf','sha256:specPwrA','VERIFIED','engineer');
SELECT setval('spec_document_id_seq', 20);

-- 2. 资料原文条目（原句保留；对象引用 master_entity 固定主键）
INSERT INTO spec_statement (id, statement_code, document_id, source_locator, original_text, object_entity_id, aspect, status, created_by) VALUES
 (1,'ST-100A-01',1,'§4.1 表1 第2行','主轴电机轴向长度介于 279 与 281 mm 之间。',30,'DIMENSION','DRAFT','engineer'),
 (2,'ST-100A-02',1,'§4.2 表2 第1行','安装孔距 150。',30,'DIMENSION','DRAFT','engineer'),
 (3,'ST-100A-03',1,'§5.1 第1段','工作电压不低于 380 V，且通信协议为 EtherCAT。',30,'POWER','DRAFT','engineer'),
 (4,'ST-100A-04',1,'§4.2 表2 第3行','安装螺孔深度 12。',30,'DIMENSION','DRAFT','engineer'),
 (5,'ST-MIX-01',2,'§2.1','泵体长度为 120 mm。',38,'DIMENSION','DRAFT','engineer'),
 (6,'ST-MIX-02',2,'§2.2','法兰外径 2.5 inch。',38,'DIMENSION','DRAFT','engineer'),
 (7,'ST-MIX-03',2,'§2.3','连接孔距 85。',38,'DIMENSION','DRAFT','engineer'),
 (8,'ST-200-01',3,'§3.1','泵体轴径 50 ~ 55 mm。',38,'DIMENSION','DRAFT','engineer'),
 (9,'ST-200B-01',4,'§1.2','泵体轴径 52 ~ 58 mm。',38,'DIMENSION','DRAFT','engineer'),
 (10,'ST-200B-02',4,'§1.3','扬程范围 30 ~ 12 m。',38,'DIMENSION','DRAFT','engineer'),
 (11,'ST-SUB-01',5,'§2.1','PM-MOTOR-100A 可由 PM-MOTOR-100B 替代。',30,'SUBSTITUTION','DRAFT','engineer'),
 (12,'ST-SUB-02',5,'§2.2','PM-SDU-A1 可由 PM-SDU-C3 替代，须通过 振动试验（IEC 60068-2-6），有效期 24 个月。',32,'SUBSTITUTION','DRAFT','engineer'),
 (13,'ST-PWR-01',8,'§3.1','电源仓仅适配 2U 模块，除非批次 B-77 特批 3U，否则不得超出。',35,'DIMENSION','DRAFT','engineer'),
 (14,'ST-SDU-101',6,'§2.1','通信协议为 EtherCAT。',32,'PROTOCOL','DRAFT','engineer'),
 (15,'ST-SDU-201',7,'§2.1','通信协议为 EtherCAT，且回退协议为 CANopen。',32,'PROTOCOL','DRAFT','engineer');
SELECT setval('spec_statement_id_seq', 20);

-- 3. 统一反例集（两种工作流的产物都用它验证；ACCEPT 用例防止过度拒绝）
INSERT INTO counterexample (id, case_code, aspect, target_attr, input_spec, expected_verdict, rationale) VALUES
 (1,'CE-LEN-001','DIMENSION','轴向长度','{"attr":"轴向长度","value":278.9,"unit":"mm"}','REJECT','低于区间下界必须拒绝'),
 (2,'CE-LEN-002','DIMENSION','轴向长度','{"attr":"轴向长度","value":281.1,"unit":"mm"}','REJECT','高于区间上界必须拒绝'),
 (3,'CE-LEN-003','DIMENSION','轴向长度','{"attr":"轴向长度","value":280,"unit":"mm"}','ACCEPT','区间内必须接受（防止过度拒绝）'),
 (4,'CE-LEN-004','DIMENSION','轴向长度','{"attr":"轴向长度","value":280,"unit":"inch"}','REJECT','单位体系与规则不符必须拒绝'),
 (5,'CE-DIST-001','DIMENSION','安装孔距','{"attr":"安装孔距","value":149.9,"unit":"mm"}','REJECT','偏离标称孔距必须拒绝'),
 (6,'CE-DIST-002','DIMENSION','安装孔距','{"attr":"安装孔距","value":150,"unit":"mm"}','ACCEPT','标称孔距必须接受'),
 (7,'CE-VOLT-001','POWER','工作电压','{"attr":"工作电压","value":379.9,"unit":"V"}','REJECT','低于电压下界必须拒绝'),
 (8,'CE-VOLT-002','POWER','工作电压','{"attr":"工作电压","value":380,"unit":"V"}','ACCEPT','达到电压下界必须接受'),
 (9,'CE-PROT-001','PROTOCOL','通信协议','{"attr":"通信协议","value":"CANopen"}','REJECT','主协议非 EtherCAT 必须拒绝'),
 (10,'CE-PROT-002','PROTOCOL','回退协议','{"attr":"回退协议","value":"CANopen"}','ACCEPT','声明的回退协议必须接受'),
 (11,'CE-PROT-003','PROTOCOL','回退协议','{"attr":"回退协议","value":"Modbus"}','REJECT','未声明的回退协议必须拒绝'),
 (12,'CE-U-001','DIMENSION','仓体规格','{"attr":"仓体规格","value":"2U"}','ACCEPT','2U 模块必须接受'),
 (13,'CE-U-002','DIMENSION','仓体规格','{"attr":"仓体规格","value":"3U"}','REJECT','3U 超出仓体必须拒绝'),
 (14,'CE-U-003','DIMENSION','仓体规格','{"attr":"仓体规格","value":"3U","context":{"batch":"B-77"}}','ACCEPT','批次 B-77 特批 3U 必须接受（例外条款）'),
 (15,'CE-U-004','DIMENSION','仓体规格','{"attr":"仓体规格","value":"4U","context":{"batch":"B-77"}}','REJECT','特批不无限扩大，4U 仍须拒绝'),
 (16,'CE-SUB-001','SUBSTITUTION',NULL,'{"fromModel":"PM-MOTOR-100A","toModel":"PM-MOTOR-100B"}','REJECT','缺测试类型与有效期的替代条件不可满足'),
 (17,'CE-SUB-002','SUBSTITUTION',NULL,'{"fromModel":"PM-MOTOR-100A","toModel":"PM-MOTOR-100B","testType":"振动试验"}','REJECT','缺有效期的替代条件不可满足'),
 (18,'CE-SUB-003','SUBSTITUTION',NULL,'{"fromModel":"PM-SDU-A1","toModel":"PM-SDU-C3","testType":"振动试验","monthsInService":12}','ACCEPT','测试类型齐备且在有效期内必须接受'),
 (19,'CE-SUB-004','SUBSTITUTION',NULL,'{"fromModel":"PM-SDU-A1","toModel":"PM-SDU-C3","testType":"振动试验","monthsInService":36}','REJECT','超出有效期必须拒绝');
SELECT setval('counterexample_id_seq', 30);

-- 4. 受控决策表（工作流2 原始建模数据；编译由服务完成）
--    ST-PWR-01 原文含嵌套例外（除非…否则），受控语法解析器拒绝，
--    由工程师拆进决策表：例外行优先，默认行兜底拒绝（不宽松发布）。
INSERT INTO decision_table (id, table_code, table_version, statement_id, rows_spec, inherits_table, modeled_by, status) VALUES
 (1,'DT-PWR-U','v1',13,
  '[{"rowId":"r1-exception","conditions":[{"attr":"仓体规格","op":"EQ","value":"3U"},{"attr":"batch","op":"EQ","value":"B-77","context":true}],"effect":"ACCEPT","exception":true,"note":"批次 B-77 特批 3U（原文例外条款）"},
    {"rowId":"r2-main","conditions":[{"attr":"仓体规格","op":"EQ","value":"2U"}],"effect":"ACCEPT","exception":false,"note":"电源仓仅适配 2U 模块"},
    {"rowId":"r3-default","conditions":[],"effect":"REJECT","default":true,"note":"否则不得超出（兜底拒绝）"}]',
  NULL,'engineer','DRAFT');
SELECT setval('decision_table_id_seq', 10);
