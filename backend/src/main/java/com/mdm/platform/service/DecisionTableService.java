package com.mdm.platform.service;

import com.mdm.platform.repository.JdbcSupport;
import com.mdm.platform.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 受控决策表工作流（工作流2）：
 * 工程师将原文（尤其是含例外/继承、被语法解析器拒绝的材料）拆进受控决策表，
 * 编译为谓词与边界测试。决策表本身有版本维护（table_code + table_version），
 * 重新编译产生新的规则资产版本；例外行优先求值，默认行兜底拒绝（不宽松发布）。
 */
@Service
public class DecisionTableService {

    private final JdbcSupport db;
    private final RuleAssetService ruleAssets;

    public DecisionTableService(JdbcSupport db, RuleAssetService ruleAssets) {
        this.db = db;
        this.ruleAssets = ruleAssets;
    }

    /** 建模登记决策表（草稿）。被解析器拒绝/待补的条目也可建模 —— 这正是决策表的价值。 */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> createTable(String tableCode, String tableVersion, long statementId,
                                           List<Map<String, Object>> rows, String inheritsTable) {
        Map<String, Object> st = db.queryOne("SELECT * FROM spec_statement WHERE id=?", statementId);
        if (st == null) {
            throw new IllegalArgumentException("资料条目不存在: " + statementId);
        }
        Map<String, Object> doc = db.queryOne("SELECT * FROM spec_document WHERE id=?", st.get("document_id"));
        if (doc != null && "REV_WITHDRAWN".equals(doc.get("status"))) {
            throw new IllegalStateException("来源修订已撤销，不能基于已撤销修订建模新版本");
        }
        String status = String.valueOf(st.get("status"));
        if (!List.of("DRAFT", "PENDING_SUPPLEMENT", "REJECTED", "MODELED", "PUBLISHED").contains(status)) {
            throw new IllegalStateException("当前条目状态不允许建模决策表: " + status);
        }
        validateRows(rows);
        if (db.count("SELECT count(*) FROM decision_table WHERE table_code=? AND table_version=?",
                tableCode, tableVersion) > 0) {
            throw new IllegalStateException("决策表版本已存在（表格版本受控维护）: " + tableCode + " " + tableVersion);
        }
        long id = db.insertReturningId(
                "INSERT INTO decision_table(table_code,table_version,statement_id,rows_spec,inherits_table," +
                "modeled_by,status) VALUES (?,?,?,?::jsonb,?,?,'DRAFT') RETURNING id",
                tableCode, tableVersion, statementId, db.toJson(rows), inheritsTable,
                CurrentUser.username());
        // 已发布条目建模新版本时不回退其状态（当前发布版本继续有效）
        if (!"PUBLISHED".equals(status)) {
            db.update("UPDATE spec_statement SET status='MODELED', updated_at=now() WHERE id=?", statementId);
        }
        return db.queryOne("SELECT * FROM decision_table WHERE id=?", id);
    }

    /** 表格结构校验：恰含一行默认行；条件齐全；效果受控 */
    @SuppressWarnings("unchecked")
    private void validateRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("决策表至少包含一行");
        }
        int defaults = 0;
        List<String> rowIds = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object rowId = row.get("rowId");
            if (rowId == null || String.valueOf(rowId).isBlank()) {
                throw new IllegalArgumentException("每行必须含 rowId");
            }
            if (rowIds.contains(String.valueOf(rowId))) {
                throw new IllegalArgumentException("rowId 重复: " + rowId);
            }
            rowIds.add(String.valueOf(rowId));
            Object effect = row.get("effect");
            if (!"ACCEPT".equals(effect) && !"REJECT".equals(effect)) {
                throw new IllegalArgumentException("行 " + rowId + " 效果必须为 ACCEPT/REJECT");
            }
            Object conds = row.get("conditions");
            List<Map<String, Object>> conditions = conds instanceof List
                    ? (List<Map<String, Object>>) conds : List.of();
            boolean isDefault = Boolean.TRUE.equals(row.get("default")) || conditions.isEmpty();
            if (isDefault) {
                defaults++;
                if (!"REJECT".equals(effect)) {
                    throw new IllegalArgumentException("默认行必须兜底 REJECT（不宽松发布）");
                }
            }
            for (Map<String, Object> c : conditions) {
                if (c.get("attr") == null || c.get("op") == null) {
                    throw new IllegalArgumentException("行 " + rowId + " 条件缺 attr/op");
                }
                String op = String.valueOf(c.get("op"));
                if (!List.of("EQ", "NE", "GE", "LE", "GT", "LT", "RANGE", "IN").contains(op)) {
                    throw new IllegalArgumentException("行 " + rowId + " 非法比较符: " + op);
                }
                if ("RANGE".equals(op) && (c.get("low") == null || c.get("high") == null)) {
                    throw new IllegalArgumentException("行 " + rowId + " RANGE 条件缺端点");
                }
            }
        }
        if (defaults != 1) {
            throw new IllegalArgumentException("决策表必须恰含一行默认行（兜底拒绝），当前 " + defaults + " 行");
        }
    }

    /** 编译决策表 -> 规则资产（谓词 + 边界测试）；继承父表行（本表优先） */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> compile(long tableId) {
        Map<String, Object> table = db.queryOne("SELECT * FROM decision_table WHERE id=?", tableId);
        if (table == null) {
            throw new IllegalArgumentException("决策表不存在: " + tableId);
        }
        if (!"DRAFT".equals(table.get("status"))) {
            throw new IllegalStateException("仅草稿决策表可编译，当前状态: " + table.get("status"));
        }
        Map<String, Object> st = db.queryOne("SELECT * FROM spec_statement WHERE id=?",
                table.get("statement_id"));
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Object> ownRows = db.toList(table.get("rows_spec"));
        for (Object r : ownRows) {
            rows.add(new LinkedHashMap<>((Map<String, Object>) r));
        }
        // 继承：父表行追加在后（本表行优先，父表行兜底于默认行之前）
        if (table.get("inherits_table") != null) {
            Map<String, Object> parent = db.queryOne(
                    "SELECT * FROM decision_table WHERE table_code=? AND status='COMPILED' " +
                    "ORDER BY id DESC LIMIT 1", table.get("inherits_table"));
            if (parent == null) {
                throw new IllegalStateException("继承的父表不存在或未编译: " + table.get("inherits_table"));
            }
            List<Map<String, Object>> inherited = new ArrayList<>();
            for (Object r : db.toList(parent.get("rows_spec"))) {
                Map<String, Object> row = new LinkedHashMap<>((Map<String, Object>) r);
                row.put("inherited", true);
                row.put("rowId", "inh-" + row.get("rowId"));
                inherited.add(row);
            }
            // 默认行始终最后：先取出本表默认行 -> 继承行 -> 默认行
            Map<String, Object> defaultRow = null;
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> r = rows.get(i);
                Object conds = r.get("conditions");
                boolean emptyConds = !(conds instanceof List<?> l) || l.isEmpty();
                if (Boolean.TRUE.equals(r.get("default")) || emptyConds) {
                    defaultRow = rows.remove(i);
                    break;
                }
            }
            rows.addAll(inherited);
            if (defaultRow != null) {
                rows.add(defaultRow);
            }
        }
        Map<String, Object> asset = ruleAssets.createAsset(st, "DECISION_TABLE", tableId, rows, Map.of(
                "tableCode", table.get("table_code"),
                "tableVersion", table.get("table_version"),
                "inherits", table.get("inherits_table") == null ? "" : table.get("inherits_table")));
        db.update("UPDATE decision_table SET status='COMPILED' WHERE id=?", tableId);
        return asset;
    }

    public List<Map<String, Object>> listTables() {
        return db.query("SELECT t.*, s.statement_code FROM decision_table t " +
                "JOIN spec_statement s ON s.id=t.statement_id ORDER BY t.id");
    }
}
