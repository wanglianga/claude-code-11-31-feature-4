package com.mdm.platform.service;

import com.mdm.platform.repository.JdbcSupport;
import com.mdm.platform.security.CurrentUser;
import com.mdm.platform.service.spec.SpecTextParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 受控语法解析工作流（工作流1）的持久化编排：
 *  - 文档级单位体系预扫描：毫米族与英寸族混排且存在无单位数值 -> 全文档硬停止
 *  - 单条解析：缺单位/端点不明/端点倒置/证明不足 -> 待补队列；省略/嵌套例外 -> 拒绝
 *  - 跨来源冲突：同对象同属性、不同来源文档的区间端点不一致 -> 待补并登记质量事件
 * 原句永不改写；待补只追加 supplemented_text，重新解析以补齐文本为准。
 */
@Service
public class SpecParseService {

    private final JdbcSupport db;
    private final QualityEventService qualityEvents;

    public SpecParseService(JdbcSupport db, QualityEventService qualityEvents) {
        this.db = db;
        this.qualityEvents = qualityEvents;
    }

    /** 解析单条原文条目（含文档级单位体系硬停止预扫描） */
    @Transactional
    public Map<String, Object> parseStatement(long statementId) {
        Map<String, Object> st = mustStatement(statementId);
        Map<String, Object> doc = db.queryOne("SELECT * FROM spec_document WHERE id=?", st.get("document_id"));
        if (doc == null) {
            throw new IllegalStateException("条目所属资料文档不存在");
        }
        if ("REV_WITHDRAWN".equals(doc.get("status"))) {
            throw new IllegalStateException("来源修订 " + doc.get("doc_code") + " " + doc.get("revision")
                    + " 已被撤销，撤销只改变后续规则版本：不能从已撤销修订再解析产生新版本");
        }
        String status = String.valueOf(st.get("status"));
        if (!"DRAFT".equals(status) && !"PENDING_SUPPLEMENT".equals(status)) {
            throw new IllegalStateException("仅草稿/待补条目可发起解析，当前状态: " + status);
        }

        // ---- 文档级硬停止：毫米族+英寸族混排且存在无单位数值 ----
        List<Map<String, Object>> docStatements = db.query(
                "SELECT statement_code, original_text, supplemented_text FROM spec_statement WHERE document_id=?",
                st.get("document_id"));
        List<String> texts = docStatements.stream()
                .map(r -> r.get("supplemented_text") != null
                        ? String.valueOf(r.get("supplemented_text")) : String.valueOf(r.get("original_text")))
                .toList();
        SpecTextParser.UnitScan scan = SpecTextParser.scanUnitSystems(texts);
        if (scan.mixedLengthSystemsWithUnitless()) {
            String reason = "原文同时存在毫米与英寸单位体系，且数值 " + scan.unitlessNumbers()
                    + " 无单位 —— 必须停止：全文档条目拒绝解析，待单位体系裁定后重登";
            int stopped = db.update(
                    "UPDATE spec_statement SET status='REJECTED', pending_reason='MIXED_UNIT_SYSTEM', " +
                    "parse_report=parse_report||?::jsonb, updated_at=now() " +
                    "WHERE document_id=? AND status IN ('DRAFT','PENDING_SUPPLEMENT')",
                    db.toJson(Map.of("stopReason", "MIXED_UNIT_SYSTEM", "detail", reason,
                            "unitSystems", scan.systems(), "unitlessNumbers", scan.unitlessNumbers())),
                    st.get("document_id"));
            qualityEvents.record("SPEC_HARD_STOP", "CRITICAL", "SPEC_DOCUMENT",
                    ((Number) doc.get("id")).longValue(), null, null,
                    "资料 " + doc.get("doc_code") + " 毫米/英寸混排且数值无单位，解析硬停止（" + stopped + " 条）",
                    Map.of("docCode", doc.get("doc_code"), "unitSystems", scan.systems(),
                            "unitlessNumbers", scan.unitlessNumbers()));
            return db.queryOne("SELECT * FROM spec_statement WHERE id=?", statementId);
        }

        // ---- 单条受控语法解析 ----
        String text = st.get("supplemented_text") != null
                ? String.valueOf(st.get("supplemented_text")) : String.valueOf(st.get("original_text"));
        String aspect = String.valueOf(st.get("aspect"));
        SpecTextParser.Outcome outcome = SpecTextParser.parse(text, aspect);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("parsedText", text);
        report.put("originalPreserved", st.get("original_text"));
        report.put("parser", "controlled-syntax-v1");
        if (outcome.isParsed()) {
            List<Map<String, Object>> clauseMaps = new ArrayList<>();
            outcome.clauses().forEach(c -> clauseMaps.add(c.toMap()));
            report.put("clauses", clauseMaps);
            report.put("logic", outcome.logic());
            report.put("extra", outcome.extra());
            report.put("detail", outcome.detail());
            // 跨来源冲突检测（同对象同属性、不同文档的已解析条目）
            Map<String, Object> conflict = detectSourceConflict(st, outcome);
            if (conflict != null) {
                report.put("sourceConflict", conflict);
                persistOutcome(statementId, "PENDING_SUPPLEMENT", "SOURCE_CONFLICT", report);
                qualityEvents.record("SPEC_PENDING_SUPPLEMENT", "MEDIUM", "SPEC_STATEMENT", statementId,
                        null, null,
                        "条目 " + st.get("statement_code") + " 与 " + conflict.get("conflictWith")
                                + " 区间端点冲突（来源冲突），进入待补队列",
                        conflict);
                return db.queryOne("SELECT * FROM spec_statement WHERE id=?", statementId);
            }
            persistOutcome(statementId, "PARSED", null, report);
        } else if ("PENDING".equals(outcome.outcome())) {
            report.put("detail", outcome.detail());
            persistOutcome(statementId, "PENDING_SUPPLEMENT", outcome.reason(), report);
            qualityEvents.record("SPEC_PENDING_SUPPLEMENT", "MEDIUM", "SPEC_STATEMENT", statementId,
                    null, null,
                    "条目 " + st.get("statement_code") + " 进入待补队列：" + outcome.detail(),
                    Map.of("statementCode", st.get("statement_code"), "pendingReason", outcome.reason()));
        } else {
            report.put("detail", outcome.detail());
            persistOutcome(statementId, "REJECTED", outcome.reason(), report);
        }
        return db.queryOne("SELECT * FROM spec_statement WHERE id=?", statementId);
    }

    /** 待补队列补齐：原句不改写，补齐文本追加后回到草稿待重新解析 */
    @Transactional
    public Map<String, Object> supplement(long statementId, String supplementedText, String note) {
        Map<String, Object> st = mustStatement(statementId);
        if (!"PENDING_SUPPLEMENT".equals(st.get("status"))) {
            throw new IllegalStateException("仅待补队列条目可补齐，当前状态: " + st.get("status"));
        }
        if (supplementedText == null || supplementedText.isBlank()) {
            throw new IllegalArgumentException("补齐文本不能为空（原句保留，补齐文本作为解析依据）");
        }
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("event", "SUPPLEMENTED");
        history.put("previousPendingReason", st.get("pending_reason"));
        history.put("note", note == null ? "" : note);
        history.put("operator", CurrentUser.username());
        db.update("UPDATE spec_statement SET supplemented_text=?, status='DRAFT', pending_reason=NULL, " +
                "parse_report=parse_report||?::jsonb, updated_at=now() WHERE id=?",
                supplementedText, db.toJson(Map.of("supplementHistory", List.of(history))), statementId);
        return db.queryOne("SELECT * FROM spec_statement WHERE id=?", statementId);
    }

    /**
     * 跨来源冲突：同对象+同方面、不同文档、已解析/已编译/已发布条目中的同名属性，
     * 区间端点不一致（重叠但不相同或相离）或等值不同 -> 来源冲突。
     */
    private Map<String, Object> detectSourceConflict(Map<String, Object> st, SpecTextParser.Outcome outcome) {
        List<Map<String, Object>> others = db.query(
                "SELECT s.id, s.statement_code, s.document_id, s.parse_report, d.doc_code " +
                "FROM spec_statement s JOIN spec_document d ON d.id=s.document_id " +
                "WHERE s.object_entity_id=? AND s.aspect=? AND s.document_id<>? " +
                "AND s.status IN ('PARSED','COMPILED','VALIDATED','PUBLISHED')",
                st.get("object_entity_id"), st.get("aspect"), st.get("document_id"));
        for (Map<String, Object> other : others) {
            Map<String, Object> report = db.toMap(other.get("parse_report"));
            Object clausesObj = report.get("clauses");
            if (!(clausesObj instanceof List<?> otherClauses)) continue;
            for (SpecTextParser.Clause mine : outcome.clauses()) {
                for (Object oc : otherClauses) {
                    if (!(oc instanceof Map<?, ?> om)) continue;
                    if (!mine.attr().equals(String.valueOf(om.get("attr")))) continue;
                    String conflict = compareClauses(mine, om);
                    if (conflict != null) {
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("conflictWith", other.get("statement_code"));
                        detail.put("conflictDoc", other.get("doc_code"));
                        detail.put("attr", mine.attr());
                        detail.put("incoming", mine.toMap());
                        detail.put("existing", om);
                        detail.put("reason", conflict);
                        return detail;
                    }
                }
            }
        }
        return null;
    }

    private String compareClauses(SpecTextParser.Clause mine, Map<?, ?> other) {
        String otherOp = String.valueOf(other.get("op"));
        String otherUnit = other.get("unit") == null ? null
                : SpecTextParser.normalizeUnit(String.valueOf(other.get("unit")));
        if (mine.unit() != null && otherUnit != null && !mine.unit().equals(otherUnit)) {
            return "同名属性单位体系不一致（" + mine.unit() + " vs " + otherUnit + "）";
        }
        if ("RANGE".equals(mine.op()) && "RANGE".equals(otherOp)) {
            double ol = ((Number) other.get("low")).doubleValue();
            double oh = ((Number) other.get("high")).doubleValue();
            if (mine.low() == ol && mine.high() == oh) return null; // 区间完全一致：冗余而非冲突
            return "区间端点冲突：新 [" + mine.low() + "," + mine.high() + "] 与已有 ["
                    + ol + "," + oh + "] 不一致";
        }
        if ("RANGE".equals(mine.op()) && "EQ".equals(otherOp)) {
            double ov = ((Number) other.get("value")).doubleValue();
            if (ov < mine.low() || ov > mine.high()) {
                return "等值 " + ov + " 落在新区间 [" + mine.low() + "," + mine.high() + "] 之外";
            }
            return null;
        }
        if ("EQ".equals(mine.op()) && "RANGE".equals(otherOp)) {
            double ol = ((Number) other.get("low")).doubleValue();
            double oh = ((Number) other.get("high")).doubleValue();
            if (mine.value() < ol || mine.value() > oh) {
                return "新等值 " + mine.value() + " 落在已有区间 [" + ol + "," + oh + "] 之外";
            }
            return null;
        }
        if ("EQ".equals(mine.op()) && "EQ".equals(otherOp) && mine.value() != null
                && other.get("value") instanceof Number ov) {
            if (mine.value() != ov.doubleValue()) {
                return "等值冲突：" + mine.value() + " vs " + ov;
            }
        }
        return null;
    }

    private void persistOutcome(long statementId, String status, String pendingReason,
                                Map<String, Object> report) {
        db.update("UPDATE spec_statement SET status=?, pending_reason=?, " +
                "parse_report=parse_report||?::jsonb, updated_at=now() WHERE id=?",
                status, pendingReason, db.toJson(report), statementId);
    }

    private Map<String, Object> mustStatement(long id) {
        Map<String, Object> st = db.queryOne("SELECT * FROM spec_statement WHERE id=?", id);
        if (st == null) {
            throw new IllegalArgumentException("资料条目不存在: " + id);
        }
        return st;
    }

    // ---------- 查询 ----------
    public List<Map<String, Object>> listStatements(String status) {
        String base = "SELECT s.*, d.doc_code, d.revision, d.title AS doc_title, " +
                "e.entity_code AS object_code, e.display_name AS object_name " +
                "FROM spec_statement s JOIN spec_document d ON d.id=s.document_id " +
                "LEFT JOIN master_entity e ON e.id=s.object_entity_id ";
        if (status == null || status.isBlank()) {
            return db.query(base + "ORDER BY s.id");
        }
        return db.query(base + "WHERE s.status=? ORDER BY s.id", status);
    }

    public List<Map<String, Object>> pendingQueue() {
        return listStatements("PENDING_SUPPLEMENT");
    }

    public List<Map<String, Object>> listDocuments() {
        return db.query("SELECT d.*, " +
                "(SELECT count(*) FROM spec_statement s WHERE s.document_id=d.id) AS statement_count, " +
                "(SELECT count(*) FROM spec_statement s WHERE s.document_id=d.id AND s.status='PENDING_SUPPLEMENT') AS pending_count " +
                "FROM spec_document d ORDER BY d.id");
    }

    /** 文档详情：条目 + 派生资产（回链） */
    public Map<String, Object> documentDetail(long documentId) {
        Map<String, Object> doc = db.queryOne("SELECT * FROM spec_document WHERE id=?", documentId);
        if (doc == null) {
            throw new IllegalArgumentException("资料文档不存在: " + documentId);
        }
        Map<String, Object> detail = new LinkedHashMap<>(doc);
        List<Map<String, Object>> statements = db.query(
                "SELECT * FROM spec_statement WHERE document_id=? ORDER BY id", documentId);
        detail.put("statements", statements);
        detail.put("derivedAssets", db.query(
                "SELECT a.* FROM rule_asset a JOIN spec_statement s ON s.id=a.statement_id " +
                "WHERE s.document_id=? ORDER BY a.id", documentId));
        detail.put("notices", db.query(
                "SELECT * FROM spec_revision_notice WHERE document_id=? OR successor_document_id=? ORDER BY id",
                documentId, documentId));
        return detail;
    }
}
