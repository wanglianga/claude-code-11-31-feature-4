package com.mdm.platform.service;

import com.mdm.platform.repository.JdbcSupport;
import com.mdm.platform.security.CurrentUser;
import com.mdm.platform.service.spec.PredicateEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则资产服务：
 *  - 两种工作流（受控语法解析 / 受控决策表）的统一产物：编译谓词 + 边界测试 + 适用范围
 *  - 统一反例集验证：两种产物用同一反例集 + 各自边界测试，全部通过才 VALIDATED
 *  - 发布闸门：仅 VALIDATED 可发布；无法编译/验证不过的陈述不得宽松发布
 *  - 修订撤销：只改变后续规则版本 —— 已发布版本内容不改写（标注 source_withdrawn），
 *    后续版本经 supersedes_asset_id 串联，发布时旧版本置 SUPERSEDED
 */
@Service
public class RuleAssetService {

    public static final String VALIDATOR_VERSION = "validator-v1";

    private final JdbcSupport db;
    private final QualityEventService qualityEvents;

    public RuleAssetService(JdbcSupport db, QualityEventService qualityEvents) {
        this.db = db;
        this.qualityEvents = qualityEvents;
    }

    // =====================================================================
    // 资产编译（工作流1：从解析结果编译）
    // =====================================================================
    @Transactional
    public Map<String, Object> compileFromParse(long statementId) {
        Map<String, Object> st = mustStatement(statementId);
        if (!"PARSED".equals(st.get("status"))) {
            throw new IllegalStateException("仅解析成功（PARSED）的条目可编译为规则资产，当前状态: "
                    + st.get("status") + "（无法编译的陈述不得宽松发布）");
        }
        Map<String, Object> report = db.toMap(st.get("parse_report"));
        Object clausesObj = report.get("clauses");
        if (!(clausesObj instanceof List<?> clauses) || clauses.isEmpty()) {
            throw new IllegalStateException("解析报告缺少条件子句，无法编译");
        }
        // AND：全部子句进同一规则行；IN 子句保持枚举列表
        List<Map<String, Object>> conditions = new ArrayList<>();
        for (Object c : clauses) {
            if (!(c instanceof Map<?, ?> cm)) continue;
            Map<String, Object> cond = new LinkedHashMap<>();
            cm.forEach((k, v) -> cond.put(String.valueOf(k), v));
            cond.remove("raw");
            conditions.add(cond);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(Map.of("rowId", "r1-main", "conditions", conditions, "effect", "ACCEPT",
                "note", "受控语法解析抽取的 AND 条件组"));
        rows.add(Map.of("rowId", "r-default", "conditions", List.of(), "effect", "REJECT",
                "default", true, "note", "兜底拒绝（不宽松放行）"));
        @SuppressWarnings("unchecked")
        Map<String, Object> extra = report.get("extra") instanceof Map
                ? (Map<String, Object>) report.get("extra") : Map.of();
        return createAsset(st, "SYNTAX_PARSE", null, rows, extra);
    }

    // =====================================================================
    // 资产创建（两种工作流共用）：版本链 + 边界测试 + 适用范围
    // =====================================================================
    @SuppressWarnings("unchecked")
    public Map<String, Object> createAsset(Map<String, Object> st, String workflow, Long decisionTableId,
                                           List<Map<String, Object>> rows, Map<String, Object> extra) {
        Map<String, Object> doc = db.queryOne("SELECT * FROM spec_document WHERE id=?", st.get("document_id"));
        if (doc == null) {
            throw new IllegalStateException("条目所属资料文档不存在");
        }
        if ("REV_WITHDRAWN".equals(doc.get("status"))) {
            throw new IllegalStateException("来源修订已被撤销，不能从已撤销修订编译新版本（撤销只改变后续规则版本）");
        }
        // 版本链：同文档族 + 对象 + 方面 + 主属性
        String primaryAttr = primaryAttr(rows);
        Map<String, Object> chainHead = db.queryOne(
                "SELECT a.* FROM rule_asset a JOIN spec_statement s ON s.id=a.statement_id " +
                "JOIN spec_document d ON d.id=s.document_id " +
                "WHERE d.doc_code=? AND a.object_entity_id=? AND a.aspect=? " +
                "AND a.scope_spec->>'primaryAttr'=? " +
                "ORDER BY a.created_at DESC, a.id DESC LIMIT 1",
                doc.get("doc_code"), st.get("object_entity_id"), st.get("aspect"), primaryAttr);
        String assetCode;
        String version;
        Long supersedesId = null;
        if (chainHead == null) {
            long count = db.count("SELECT count(*) FROM rule_asset");
            assetCode = String.format("RA-%04d", count + 1);
            version = "v1";
        } else {
            assetCode = String.valueOf(chainHead.get("asset_code"));
            int next = (int) db.count(
                    "SELECT count(*) FROM rule_asset WHERE asset_code=?", assetCode) + 1;
            version = "v" + next;
            if ("PUBLISHED".equals(chainHead.get("status")) || "VALIDATED".equals(chainHead.get("status"))) {
                supersedesId = ((Number) chainHead.get("id")).longValue();
            }
        }
        List<Map<String, Object>> boundaryTests = PredicateEngine.boundaryTests(rows);
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("primaryAttr", primaryAttr);
        scope.put("objectEntityId", st.get("object_entity_id"));
        scope.put("docCode", doc.get("doc_code"));
        scope.put("docRevision", doc.get("revision"));
        scope.put("sourceLocator", st.get("source_locator"));
        scope.putAll(extra);
        String unit = rows.stream()
                .flatMap(r -> ((List<Map<String, Object>>) r.getOrDefault("conditions", List.of())).stream())
                .map(c -> c.get("unit")).filter(u -> u != null)
                .map(String::valueOf).findFirst().orElse(null);

        long assetId = db.insertReturningId(
                "INSERT INTO rule_asset(asset_code,asset_version,statement_id,workflow,decision_table_id," +
                "object_entity_id,aspect,unit,compiled_predicate,boundary_tests,scope_spec,status," +
                "supersedes_asset_id) VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,'COMPILED',?) RETURNING id",
                assetCode, version, st.get("id"), workflow, decisionTableId,
                st.get("object_entity_id"), st.get("aspect"), unit,
                db.toJson(rows), db.toJson(boundaryTests), db.toJson(scope), supersedesId);
        // 已发布条目编译新版本时不回退其状态（当前发布版本继续有效，直至被后续版本取代）
        if (!"PUBLISHED".equals(st.get("status"))) {
            db.update("UPDATE spec_statement SET status='COMPILED', updated_at=now() WHERE id=?", st.get("id"));
        }
        return getAsset(assetId);
    }

    private String primaryAttr(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            Object conds = row.get("conditions");
            if (conds instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> m && m.get("attr") != null) {
                    return String.valueOf(m.get("attr"));
                }
            }
        }
        return "_";
    }

    // =====================================================================
    // 统一反例集验证（两种工作流的产物用同一反例集 + 各自边界测试）
    // =====================================================================
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> validate(long assetId) {
        Map<String, Object> asset = mustAsset(assetId);
        String status = String.valueOf(asset.get("status"));
        if (!"COMPILED".equals(status) && !"VALIDATED".equals(status)) {
            throw new IllegalStateException("仅已编译资产可验证，当前状态: " + status);
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) (List<?>) db.toList(asset.get("compiled_predicate"));
        List<Map<String, Object>> boundaryTests =
                (List<Map<String, Object>>) (List<?>) db.toList(asset.get("boundary_tests"));
        Set<String> condAttrs = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            Object conds = row.get("conditions");
            if (conds instanceof List<?> list) {
                for (Object c : list) {
                    if (c instanceof Map<?, ?> m && !Boolean.TRUE.equals(m.get("context"))) {
                        condAttrs.add(String.valueOf(m.get("attr")));
                    }
                }
            }
        }
        // 适用的共享反例：同方面 且（通用 或 目标属性在本资产条件内）
        List<Map<String, Object>> cases = db.query(
                "SELECT * FROM counterexample WHERE aspect=? ORDER BY id", asset.get("aspect"));
        List<Map<String, Object>> failures = new ArrayList<>();
        int total = 0;
        int passed = 0;
        for (Map<String, Object> ce : cases) {
            String targetAttr = ce.get("target_attr") == null ? null : String.valueOf(ce.get("target_attr"));
            if (targetAttr != null && !condAttrs.contains(targetAttr)) {
                continue; // 本资产不考察该维度
            }
            total++;
            Map<String, Object> inputSpec = db.toMap(ce.get("input_spec"));
            Map<String, Object> input = normalizeCaseInput(inputSpec);
            String actual = PredicateEngine.evaluate(rows, input, targetAttr);
            String expected = String.valueOf(ce.get("expected_verdict"));
            if (expected.equals(actual)) {
                passed++;
            } else {
                failures.add(Map.of("kind", "COUNTEREXAMPLE", "case", ce.get("case_code"),
                        "expected", expected, "actual", actual,
                        "rationale", ce.get("rationale")));
            }
        }
        // 边界测试（全谓词模式）
        for (Map<String, Object> bt : boundaryTests) {
            total++;
            Map<String, Object> input = (Map<String, Object>) bt.get("input");
            String actual = PredicateEngine.evaluate(rows, input, null);
            String expected = String.valueOf(bt.get("expect"));
            if (expected.equals(actual)) {
                passed++;
            } else {
                failures.add(Map.of("kind", "BOUNDARY_TEST", "case", bt.get("name"),
                        "expected", expected, "actual", actual,
                        "rationale", bt.get("basis")));
            }
        }
        String result = failures.isEmpty() ? "PASS" : "FAIL";
        db.update("INSERT INTO asset_validation_run(asset_id,validator_version,total_cases,passed_cases," +
                "failures,result,run_by) VALUES (?,?,?,?,?::jsonb,?,?)",
                assetId, VALIDATOR_VERSION, total, passed, db.toJson(failures), result,
                CurrentUser.username());
        if ("PASS".equals(result)) {
            db.update("UPDATE rule_asset SET status='VALIDATED', validation_result='PASS' WHERE id=?", assetId);
            db.update("UPDATE spec_statement SET status='VALIDATED', updated_at=now() WHERE id=? AND status<>'PUBLISHED'",
                    asset.get("statement_id"));
        } else {
            db.update("UPDATE rule_asset SET validation_result='FAIL' WHERE id=?", assetId);
        }
        return getAsset(assetId);
    }

    /** 反例输入归一化：{attr,value,unit,context} 或 {testType,monthsInService,...} -> 引擎输入 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeCaseInput(Map<String, Object> spec) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        Map<String, Object> context = new LinkedHashMap<>();
        if (spec.containsKey("attr")) {
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("value", spec.get("value"));
            if (spec.get("unit") != null) cell.put("unit", spec.get("unit"));
            attrs.put(String.valueOf(spec.get("attr")), cell);
        } else {
            for (Map.Entry<String, Object> e : spec.entrySet()) {
                switch (e.getKey()) {
                    case "context" -> {
                        if (e.getValue() instanceof Map<?, ?> cm) {
                            cm.forEach((k, v) -> context.put(String.valueOf(k), v));
                        }
                    }
                    case "fromModel", "toModel" -> context.put(e.getKey(), e.getValue());
                    default -> {
                        Map<String, Object> cell = new LinkedHashMap<>();
                        cell.put("value", e.getValue());
                        attrs.put(e.getKey(), cell);
                    }
                }
            }
        }
        if (spec.get("context") instanceof Map<?, ?> cm) {
            cm.forEach((k, v) -> context.put(String.valueOf(k), v));
        }
        return Map.of("attrs", attrs, "context", context);
    }

    // =====================================================================
    // 发布闸门：仅统一验证通过的资产可发布；发布触发后续版本取代
    // =====================================================================
    @Transactional
    public Map<String, Object> publish(long assetId) {
        Map<String, Object> asset = mustAsset(assetId);
        if (!"VALIDATED".equals(asset.get("status"))) {
            throw new IllegalStateException("发布闸门拦截：仅通过统一反例集验证（VALIDATED）的资产可发布，当前状态 "
                    + asset.get("status") + "（验证结果: " + asset.get("validation_result") + "）");
        }
        db.update("UPDATE rule_asset SET status='PUBLISHED', published_at=now(), published_by=? WHERE id=?",
                CurrentUser.username(), assetId);
        db.update("UPDATE spec_statement SET status='PUBLISHED', updated_at=now() WHERE id=?",
                asset.get("statement_id"));
        // 后续版本取代：旧版本内容保留不改写，仅状态置 SUPERSEDED 并登记质量事件
        if (asset.get("supersedes_asset_id") != null) {
            long oldId = ((Number) asset.get("supersedes_asset_id")).longValue();
            Map<String, Object> old = db.queryOne("SELECT * FROM rule_asset WHERE id=?", oldId);
            if (old != null && !"SUPERSEDED".equals(old.get("status"))) {
                db.update("UPDATE rule_asset SET status='SUPERSEDED' WHERE id=?", oldId);
                db.update("UPDATE spec_statement SET status='SUPERSEDED', updated_at=now() WHERE id=?",
                        old.get("statement_id"));
                qualityEvents.record("RULE_ASSET_SUPERSEDED", "LOW", "RULE_ASSET", oldId, null, null,
                        "规则资产 " + old.get("asset_code") + " " + old.get("asset_version")
                                + " 被后续版本 " + asset.get("asset_version") + " 取代（旧版本内容保留可回放）",
                        Map.of("assetCode", old.get("asset_code"),
                                "oldVersion", old.get("asset_version"),
                                "newVersion", asset.get("asset_version")));
            }
        }
        return getAsset(assetId);
    }

    // =====================================================================
    // 修订撤销：只改变后续规则版本
    // =====================================================================
    @Transactional
    public Map<String, Object> withdrawDocument(long documentId, String note, Long successorDocumentId) {
        Map<String, Object> doc = db.queryOne("SELECT * FROM spec_document WHERE id=?", documentId);
        if (doc == null) {
            throw new IllegalArgumentException("资料文档不存在: " + documentId);
        }
        if ("REV_WITHDRAWN".equals(doc.get("status"))) {
            throw new IllegalStateException("该修订已处于撤销状态");
        }
        long count = db.count("SELECT count(*) FROM spec_revision_notice");
        String noticeCode = String.format("NT-%04d", count + 1);
        long noticeId = db.insertReturningId(
                "INSERT INTO spec_revision_notice(notice_code,document_id,notice_type,successor_document_id," +
                "note,registered_by) VALUES (?,?,?,?,?,?) RETURNING id",
                noticeCode, documentId, "REVISION_WITHDRAW", successorDocumentId,
                note, CurrentUser.username());
        db.update("UPDATE spec_document SET status='REV_WITHDRAWN' WHERE id=?", documentId);
        // 已发布/已验证资产：内容不改写，仅标注来源已撤销（历史结论仍可回放）
        List<Map<String, Object>> affected = db.query(
                "SELECT a.id, a.asset_code, a.asset_version FROM rule_asset a " +
                "JOIN spec_statement s ON s.id=a.statement_id " +
                "WHERE s.document_id=? AND a.status IN ('VALIDATED','PUBLISHED')", documentId);
        for (Map<String, Object> a : affected) {
            db.update("UPDATE rule_asset SET source_withdrawn=TRUE WHERE id=?", a.get("id"));
        }
        // 该修订下未完成的草稿/待补条目：不能再从已撤销修订产生新版本
        int rejected = db.update(
                "UPDATE spec_statement SET status='REJECTED', pending_reason='SOURCE_WITHDRAWN', " +
                "updated_at=now() WHERE document_id=? AND status IN ('DRAFT','PENDING_SUPPLEMENT')",
                documentId);
        qualityEvents.record("SPEC_REVISION_WITHDRAWN", "HIGH", "SPEC_DOCUMENT", documentId, null, null,
                "资料修订 " + doc.get("doc_code") + " " + doc.get("revision")
                        + " 被撤销：已发布资产 " + affected.size() + " 个保留内容并标注来源撤销，"
                        + rejected + " 个未完成条目停止流转；后续规则版本须源自继任修订",
                Map.of("noticeCode", noticeCode, "docCode", doc.get("doc_code"),
                        "revision", doc.get("revision"), "affectedAssets", affected,
                        "successorDocumentId", successorDocumentId == null ? "" : successorDocumentId,
                        "note", note == null ? "" : note));
        return db.queryOne("SELECT * FROM spec_revision_notice WHERE id=?", noticeId);
    }

    // =====================================================================
    // 试算（调试用，全谓词模式）
    // =====================================================================
    @SuppressWarnings("unchecked")
    public Map<String, Object> evaluateAsset(long assetId, Map<String, Object> input) {
        Map<String, Object> asset = mustAsset(assetId);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) (List<?>) db.toList(asset.get("compiled_predicate"));
        String verdict = PredicateEngine.evaluate(rows, input == null ? Map.of() : input, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assetCode", asset.get("asset_code"));
        result.put("assetVersion", asset.get("asset_version"));
        result.put("verdict", verdict);
        result.put("input", input);
        return result;
    }

    // =====================================================================
    // 查询（回链：资产 -> 条目 -> 文档/修订/通知/验证记录/版本链）
    // =====================================================================
    public Map<String, Object> getAsset(long assetId) {
        Map<String, Object> asset = db.queryOne("SELECT * FROM rule_asset WHERE id=?", assetId);
        if (asset == null) {
            throw new IllegalArgumentException("规则资产不存在: " + assetId);
        }
        return asset;
    }

    public Map<String, Object> assetDetail(long assetId) {
        Map<String, Object> asset = getAsset(assetId);
        Map<String, Object> detail = new LinkedHashMap<>(asset);
        Map<String, Object> st = db.queryOne("SELECT * FROM spec_statement WHERE id=?", asset.get("statement_id"));
        detail.put("statement", st);
        if (st != null) {
            detail.put("document", db.queryOne("SELECT * FROM spec_document WHERE id=?", st.get("document_id")));
        }
        if (asset.get("decision_table_id") != null) {
            detail.put("decisionTable",
                    db.queryOne("SELECT * FROM decision_table WHERE id=?", asset.get("decision_table_id")));
        }
        detail.put("validationRuns", db.query(
                "SELECT * FROM asset_validation_run WHERE asset_id=? ORDER BY id DESC", assetId));
        // 版本链（同 asset_code 全部版本）
        detail.put("versionChain", db.query(
                "SELECT id, asset_version, status, source_withdrawn, published_at, supersedes_asset_id " +
                "FROM rule_asset WHERE asset_code=? ORDER BY id", asset.get("asset_code")));
        return detail;
    }

    public List<Map<String, Object>> listAssets(String status) {
        String base = "SELECT a.*, s.statement_code, s.source_locator, e.entity_code AS object_code " +
                "FROM rule_asset a JOIN spec_statement s ON s.id=a.statement_id " +
                "LEFT JOIN master_entity e ON e.id=a.object_entity_id ";
        if (status == null || status.isBlank()) {
            return db.query(base + "ORDER BY a.id");
        }
        return db.query(base + "WHERE a.status=? ORDER BY a.id", status);
    }

    public List<Map<String, Object>> listCounterexamples() {
        return db.query("SELECT * FROM counterexample ORDER BY id");
    }

    public List<Map<String, Object>> listValidationRuns(Long assetId) {
        if (assetId == null) {
            return db.query("SELECT * FROM asset_validation_run ORDER BY id DESC LIMIT 100");
        }
        return db.query("SELECT * FROM asset_validation_run WHERE asset_id=? ORDER BY id DESC", assetId);
    }

    public List<Map<String, Object>> listNotices() {
        return db.query("SELECT n.*, d.doc_code, d.revision, sd.doc_code AS successor_doc_code, " +
                "sd.revision AS successor_revision " +
                "FROM spec_revision_notice n JOIN spec_document d ON d.id=n.document_id " +
                "LEFT JOIN spec_document sd ON sd.id=n.successor_document_id ORDER BY n.id");
    }

    private Map<String, Object> mustStatement(long id) {
        Map<String, Object> st = db.queryOne("SELECT * FROM spec_statement WHERE id=?", id);
        if (st == null) {
            throw new IllegalArgumentException("资料条目不存在: " + id);
        }
        return st;
    }

    private Map<String, Object> mustAsset(long id) {
        Map<String, Object> asset = db.queryOne("SELECT * FROM rule_asset WHERE id=?", id);
        if (asset == null) {
            throw new IllegalArgumentException("规则资产不存在: " + id);
        }
        return asset;
    }
}
