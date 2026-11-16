package com.mdm.platform.config;

import com.mdm.platform.repository.JdbcSupport;
import com.mdm.platform.service.CompatibilityService;
import com.mdm.platform.service.DecisionTableService;
import com.mdm.platform.service.ImpactAnalysisService;
import com.mdm.platform.service.IngestionService;
import com.mdm.platform.service.ReleaseGateService;
import com.mdm.platform.service.RuleAssetService;
import com.mdm.platform.service.SnapshotService;
import com.mdm.platform.service.SpecParseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 启动初始化：
 *  1. 始终执行 schema.sql（幂等 DDL，含历史表的约束迁移）；空库灌 seed.sql，
 *     spec_document 为空时灌 seed_spec.sql（特性4 原始资料）
 *  2. 按真实服务逻辑重放样例：配置快照五大场景 + 工程资料规则资产化四大验收场景
 * 所有派生数据都经服务产生，保证任一结论可在接口上回放。
 */
@Component
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private final DataSource dataSource;
    private final JdbcSupport db;
    private final SnapshotService snapshotService;
    private final CompatibilityService compatibilityService;
    private final ReleaseGateService releaseGateService;
    private final IngestionService ingestionService;
    private final ImpactAnalysisService impactAnalysisService;
    private final SpecParseService specParseService;
    private final DecisionTableService decisionTableService;
    private final RuleAssetService ruleAssetService;

    @Value("${app.demo.auto-init:true}")
    private boolean autoInit;

    @Value("${app.demo.reset-on-start:false}")
    private boolean resetOnStart;

    public DemoDataInitializer(DataSource dataSource, JdbcSupport db,
                               SnapshotService snapshotService,
                               CompatibilityService compatibilityService,
                               ReleaseGateService releaseGateService,
                               IngestionService ingestionService,
                               ImpactAnalysisService impactAnalysisService,
                               SpecParseService specParseService,
                               DecisionTableService decisionTableService,
                               RuleAssetService ruleAssetService) {
        this.dataSource = dataSource;
        this.db = db;
        this.snapshotService = snapshotService;
        this.compatibilityService = compatibilityService;
        this.releaseGateService = releaseGateService;
        this.ingestionService = ingestionService;
        this.impactAnalysisService = impactAnalysisService;
        this.specParseService = specParseService;
        this.decisionTableService = decisionTableService;
        this.ruleAssetService = ruleAssetService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!autoInit) {
            log.info("自动初始化已关闭(app.demo.auto-init=false)，跳过");
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            // schema.sql 全幂等（CREATE IF NOT EXISTS + 约束迁移），始终执行以支持原地升级
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/schema.sql"));
            if (resetOnStart) {
                truncateAll(conn);
            }
            if (db.count("SELECT count(*) FROM app_user") == 0) {
                log.info("灌入基础种子 seed.sql ...");
                ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/seed.sql"));
            }
            if (db.count("SELECT count(*) FROM spec_document") == 0) {
                log.info("灌入规则资产原始资料 seed_spec.sql ...");
                ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/seed_spec.sql"));
            }
        }
        if (db.count("SELECT count(*) FROM config_snapshot") == 0) {
            replayScenarios();
        } else {
            log.info("派生样例已存在，跳过配置快照场景重放");
        }
        if (db.count("SELECT count(*) FROM rule_asset") == 0) {
            replaySpecScenarios();
        } else {
            log.info("规则资产样例已存在，跳过资料资产化场景重放");
        }
    }

    private void truncateAll(Connection conn) throws Exception {
        String[] tables = {"release_audit_log","impact_analysis","quality_event","compatibility_fact",
                "conflict_fact","snapshot_position","config_snapshot","rule_constraint","substitution_rule",
                "inspection_record","factory_record","install_event","drawing_revision","drawing",
                "entity_alias_candidate","master_entity","ingestion_contract_version","ingestion_contract",
                "app_user",
                "asset_validation_run","rule_asset","decision_table","counterexample",
                "spec_revision_notice","spec_statement","spec_document"};
        try (var st = conn.createStatement()) {
            for (String t : tables) {
                st.execute("TRUNCATE TABLE " + t + " RESTART IDENTITY CASCADE");
            }
        }
    }

    // =====================================================================
    // 特性4：工程资料规则资产化 四大验收场景（全部经真实服务逻辑重放）
    // =====================================================================
    private void replaySpecScenarios() {
        log.info("==== 开始重放规则资产化验收场景（F1 单位缺失 / F1b 混合单位停止 / F2 端点冲突 / F3 证明不足 / F4 撤销通知） ====");

        // ---------- 第一轮：全量受控语法解析 ----------
        // F1: ST-100A-02 / ST-100A-04 缺单位 -> 待补队列
        // F1b: DOC-SPEC-MIX 毫米+英寸混排且数值无单位 -> 全文档硬停止
        // F2: ST-200B-01 跨来源区间端点冲突 -> 待补；ST-200B-02 端点倒置 -> 待补
        // F3: ST-SUB-01 缺测试类型与有效期 -> 证明不足待补；ST-SUB-02 齐备 -> PARSED
        // 对照: ST-PWR-01 嵌套例外 -> 解析器拒绝（转决策表工作流）
        for (long statementId = 1; statementId <= 14; statementId++) {
            // 文档级硬停止会把同文档其余草稿一并拒绝，跳过错过的条目
            Map<String, Object> cur = db.queryOne("SELECT status FROM spec_statement WHERE id=?", statementId);
            if (cur != null && "DRAFT".equals(String.valueOf(cur.get("status")))) {
                specParseService.parseStatement(statementId);
            }
        }
        log.info("第一轮解析完成：待补 {} 条，拒绝 {} 条，解析成功 {} 条",
                db.count("SELECT count(*) FROM spec_statement WHERE status='PENDING_SUPPLEMENT'"),
                db.count("SELECT count(*) FROM spec_statement WHERE status='REJECTED'"),
                db.count("SELECT count(*) FROM spec_statement WHERE status='PARSED'"));

        // ---------- F1 补齐回路：ST-100A-02 工程师补齐单位后重新解析 ----------
        specParseService.supplement(2L, "安装孔距为 150 mm。", "工程数据组核对图纸原件，补全量纲单位 mm");
        specParseService.parseStatement(2L);
        log.info("F1: ST-100A-02 补齐单位后重新解析 -> {}",
                db.queryOne("SELECT status FROM spec_statement WHERE id=2").get("status"));

        // ---------- 编译（工作流1 产物） ----------
        List<Map<String, Object>> parsed = db.query(
                "SELECT id, statement_code FROM spec_statement WHERE status='PARSED' ORDER BY id");
        for (Map<String, Object> st : parsed) {
            ruleAssetService.compileFromParse(((Number) st.get("id")).longValue());
        }
        log.info("语法解析产物编译完成，共 {} 个规则资产", db.count("SELECT count(*) FROM rule_asset"));

        // ---------- 决策表工作流（工作流2）：ST-PWR-01 嵌套例外的准确表达 ----------
        var dtAsset = decisionTableService.compile(1L);
        log.info("决策表 DT-PWR-U 编译完成 -> 资产 {} {}", dtAsset.get("asset_code"), dtAsset.get("asset_version"));

        // ---------- 统一反例集验证（两种产物用同一反例集） ----------
        List<Map<String, Object>> compiled = db.query("SELECT id FROM rule_asset WHERE status='COMPILED' ORDER BY id");
        for (Map<String, Object> a : compiled) {
            ruleAssetService.validate(((Number) a.get("id")).longValue());
        }
        log.info("统一反例集验证完成：VALIDATED {} 个，验证失败 {} 个",
                db.count("SELECT count(*) FROM rule_asset WHERE status='VALIDATED'"),
                db.count("SELECT count(*) FROM rule_asset WHERE validation_result='FAIL'"));

        // ---------- 发布闸门：仅 VALIDATED 可发布 ----------
        List<Map<String, Object>> validated = db.query("SELECT id FROM rule_asset WHERE status='VALIDATED' ORDER BY id");
        for (Map<String, Object> a : validated) {
            ruleAssetService.publish(((Number) a.get("id")).longValue());
        }
        log.info("发布完成：PUBLISHED {} 个规则资产", db.count("SELECT count(*) FROM rule_asset WHERE status='PUBLISHED'"));

        // ---------- F4 撤销通知：DOC-SPEC-SDU revA 撤销，只改变后续规则版本 ----------
        ruleAssetService.withdrawDocument(6L,
                "供应商通知 revA 接口规范存在协议回退条款遗漏，撤销该修订，由 revB 继任", 7L);
        log.info("F4: revA 撤销通知已登记，v1 资产保留内容并标注来源撤销");

        // 撤销后试图从已撤销修订再解析 -> 必须被拒绝（撤销只改变后续规则版本）
        try {
            specParseService.parseStatement(14L);
            log.warn("F4: 已撤销修订的解析未被拦截，不符合预期");
        } catch (IllegalStateException ex) {
            log.info("F4: 已撤销修订的解析被正确拦截 -> {}", ex.getMessage());
        }

        // 继任修订 revB 产生后续版本 v2，发布后 v1 进入 SUPERSEDED（内容保留可回放）
        specParseService.parseStatement(15L);
        var v2 = ruleAssetService.compileFromParse(15L);
        long v2Id = ((Number) v2.get("id")).longValue();
        ruleAssetService.validate(v2Id);
        ruleAssetService.publish(v2Id);
        log.info("F4: 后续版本 {} {} 已发布，v1 状态 -> SUPERSEDED（历史内容保留）",
                v2.get("asset_code"), v2.get("asset_version"));

        log.info("==== 规则资产化场景重放完成：条目 {} 条（待补 {} / 拒绝 {} / 发布 {}），资产 {} 个，验证运行 {} 次，质量事件 {} 条 ====",
                db.count("SELECT count(*) FROM spec_statement"),
                db.count("SELECT count(*) FROM spec_statement WHERE status='PENDING_SUPPLEMENT'"),
                db.count("SELECT count(*) FROM spec_statement WHERE status='REJECTED'"),
                db.count("SELECT count(*) FROM spec_statement WHERE status='PUBLISHED'"),
                db.count("SELECT count(*) FROM rule_asset"),
                db.count("SELECT count(*) FROM asset_validation_run"),
                db.count("SELECT count(*) FROM quality_event"));
    }

    private void replayScenarios() {
        log.info("==== 开始重放五大核验场景 ====");

        // 设备/位置/型号 ID（与 seed.sql 固定主键一致）
        final long EQ7 = 10, EQ8 = 11, EQ18 = 12;
        final long POS_SERVO_X = 21, POS_POWER_BOX = 22;
        final long MODEL_SDU_C3 = 34, MODEL_PWR_501 = 36, MODEL_PWR_502 = 37;
        final long RULE1 = 1;

        // ---------- 场景 B：迟到拆装凭证 ----------
        OffsetDateTime beforeLate = OffsetDateTime.parse("2024-12-01T00:00:00Z");
        var snapBeforeLate = snapshotService.compile(new SnapshotService.CompileRequest(
                EQ7, beforeLate, beforeLate, "system-replay"));
        long snapBeforeLateId = ((Number) snapBeforeLate.get("id")).longValue();
        log.info("场景B-1: 迟到凭证接收前快照 {} 编译完成", snapBeforeLate.get("snapshot_no"));

        OffsetDateTime afterLate = OffsetDateTime.parse("2025-03-02T00:00:00Z");
        var snapAfterLate = snapshotService.compile(new SnapshotService.CompileRequest(
                EQ7, OffsetDateTime.parse("2024-12-01T00:00:00Z"), afterLate, "system-replay"));
        log.info("场景B-2: 迟到凭证纳入后新版本快照 {} 编译完成，旧快照保留", snapAfterLate.get("snapshot_no"));

        // ---------- 场景 E + D：伺服替代（两约束联动 PASS）并发布，随后规则撤回 ----------
        OffsetDateTime beforeReplacement = OffsetDateTime.parse("2024-08-01T00:00:00Z");
        var snapAug = snapshotService.compile(new SnapshotService.CompileRequest(
                EQ7, beforeReplacement, beforeReplacement, "system-replay"));
        long snapAugId = ((Number) snapAug.get("id")).longValue();
        var servoFact = compatibilityService.evaluate(new CompatibilityService.EvaluationRequest(
                snapAugId, POS_SERVO_X, MODEL_SDU_C3));
        long servoFactId = ((Number) servoFact.get("id")).longValue();
        log.info("场景E: 伺服 A1->C3 求值结论 {}", servoFact.get("verdict"));
        if ("PASS".equals(servoFact.get("verdict"))) {
            releaseGateService.transition(servoFactId, "publish", "两约束(电源+协议)联动通过，签发对外");
            log.info("场景D-1: 伺服兼容事实已发布为 VERIFIED");
        }

        // ---------- 场景 E：电源 500->501 PASS；500->502 因尺寸 3U≠2U 联动 FAIL ----------
        var powerPass = compatibilityService.evaluate(new CompatibilityService.EvaluationRequest(
                snapBeforeLateId, POS_POWER_BOX, MODEL_PWR_501));
        long powerPassId = ((Number) powerPass.get("id")).longValue();
        if ("PASS".equals(powerPass.get("verdict"))) {
            releaseGateService.transition(powerPassId, "publish", "电源+尺寸联动通过");
        }
        var powerFail = compatibilityService.evaluate(new CompatibilityService.EvaluationRequest(
                snapBeforeLateId, POS_POWER_BOX, MODEL_PWR_502));
        log.info("场景E: 电源 500->501={} / 500->502={}（尺寸约束拦截）",
                powerPass.get("verdict"), powerFail.get("verdict"));

        // ---------- 场景 D：供应商撤回规则1 ----------
        ingestionService.retireRule(RULE1, "供应商通知：C3 批次 E2411 协议栈缺陷，收回替代授权");
        var ruleImpact = impactAnalysisService.analyzeRuleChange(RULE1, "RETIRE",
                "供应商收回 SUB-RULE-001，历史结论保留锁定版本并另发更正");
        log.info("场景D-2: 规则撤回影响分析完成，受影响事实 {}，更正链已生成",
                ruleImpact.get("affected_fact_ids"));

        var reEval = compatibilityService.evaluate(new CompatibilityService.EvaluationRequest(
                snapAugId, POS_SERVO_X, MODEL_SDU_C3));
        log.info("场景D-3: 撤回后历史时点重评结论 {}（锁定规则版本 {}）",
                reEval.get("verdict"), reEval.get("rule_version_locked"));

        // ---------- 场景 A：跨修订序列号区间重叠 -> 冲突事实阻止发布 ----------
        var conflictSnap = snapshotService.compile(new SnapshotService.CompileRequest(
                EQ18, OffsetDateTime.parse("2024-07-15T00:00:00Z"), null, "system-replay"));
        long conflictSnapId = ((Number) conflictSnap.get("id")).longValue();
        log.info("场景A: 设备0018 快照 has_conflict={} status={}",
                conflictSnap.get("has_conflict"), conflictSnap.get("status"));
        try {
            var blocked = compatibilityService.evaluate(new CompatibilityService.EvaluationRequest(
                    conflictSnapId, 20, MODEL_SDU_C3));
            releaseGateService.transition(((Number) blocked.get("id")).longValue(), "publish",
                    "尝试在冲突快照发布");
            log.warn("场景A: 闸门未拦截，不符合预期");
        } catch (IllegalStateException ex) {
            log.info("场景A: 发布闸门正确拦截 -> {}", ex.getMessage());
        }

        // ---------- 场景：同一实物重叠时间出现在两台设备 -> 冻结 ----------
        var eq8Snap = snapshotService.compile(new SnapshotService.CompileRequest(
                EQ8, OffsetDateTime.parse("2024-06-01T00:00:00Z"), null, "system-replay"));
        log.info("实体冻结: 设备0008 快照 status={}，双重安装冲突已登记", eq8Snap.get("status"));

        impactAnalysisService.analyzeInstallEventChange(4L);
        impactAnalysisService.analyzeDrawingChange(2L);

        log.info("==== 五大场景重放完成，共生成快照 {} 个，兼容事实 {} 条，质量事件 {} 条 ====",
                db.count("SELECT count(*) FROM config_snapshot"),
                db.count("SELECT count(*) FROM compatibility_fact"),
                db.count("SELECT count(*) FROM quality_event"));
    }
}
