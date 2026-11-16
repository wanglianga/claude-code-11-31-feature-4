package com.mdm.platform.service.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受控语法解析器（纯函数，不依赖数据库，可独立单测）。
 *
 * 从规则原文批量抽取比较符、量纲与逻辑条件，最大限度保留原句；
 * 面对省略（同上/参见）或嵌套例外（除非/特批）时拒绝该材料 ——
 * 此类材料只能走决策表工作流人工建模。
 *
 * 拒绝（REJECTED）：省略句、嵌套例外、无法识别的受控语法。
 * 待补（PENDING）：缺单位、端点不明/倒置、跨来源冲突、前置证明不足。
 */
public final class SpecTextParser {

    private SpecTextParser() {}

    // ---------- 受控词表 ----------
    /** 量纲表面形式 -> 规范单位（长形式优先匹配） */
    private static final Map<String, String> UNIT_DICT = new LinkedHashMap<>();
    static {
        UNIT_DICT.put("毫米", "mm");   UNIT_DICT.put("mm", "mm");   UNIT_DICT.put("MM", "mm");
        UNIT_DICT.put("厘米", "cm");   UNIT_DICT.put("cm", "cm");   UNIT_DICT.put("CM", "cm");
        UNIT_DICT.put("英寸", "inch"); UNIT_DICT.put("inch", "inch"); UNIT_DICT.put("INCH", "inch");
        UNIT_DICT.put("in", "inch");   UNIT_DICT.put("IN", "inch");
        UNIT_DICT.put("米", "m");     UNIT_DICT.put("m", "m");     UNIT_DICT.put("M", "m");
        UNIT_DICT.put("千伏", "kV");   UNIT_DICT.put("kV", "kV");   UNIT_DICT.put("KV", "kV");
        UNIT_DICT.put("伏特", "V");    UNIT_DICT.put("伏", "V");     UNIT_DICT.put("V", "V");
        UNIT_DICT.put("千瓦", "kW");   UNIT_DICT.put("kW", "kW");   UNIT_DICT.put("KW", "kW");
        UNIT_DICT.put("瓦", "W");     UNIT_DICT.put("W", "W");
        UNIT_DICT.put("兆帕", "MPa");  UNIT_DICT.put("MPa", "MPa");
        UNIT_DICT.put("转每分", "rpm"); UNIT_DICT.put("rpm", "rpm");
        UNIT_DICT.put("个月", "month"); UNIT_DICT.put("月", "month");
        UNIT_DICT.put("年", "year");
        UNIT_DICT.put("U", "U");
    }
    /** 长度量纲族：毫米族=公制 METRIC，英寸族=英制 IMPERIAL（硬停止只针对这两族混排） */
    private static final Set<String> METRIC_LENGTH = Set.of("mm", "cm", "m");
    private static final Set<String> IMPERIAL_LENGTH = Set.of("inch");

    /** 受控属性词表（表面形式 -> 规范属性名；长词优先） */
    private static final Map<String, String> ATTR_DICT = new LinkedHashMap<>();
    static {
        ATTR_DICT.put("安装螺孔深度", "安装螺孔深度");
        ATTR_DICT.put("轴向长度", "轴向长度");
        ATTR_DICT.put("安装孔距", "安装孔距");
        ATTR_DICT.put("连接孔距", "连接孔距");
        ATTR_DICT.put("泵体长度", "泵体长度");
        ATTR_DICT.put("泵体轴径", "泵体轴径");
        ATTR_DICT.put("法兰外径", "法兰外径");
        ATTR_DICT.put("扬程范围", "扬程");
        ATTR_DICT.put("扬程", "扬程");
        ATTR_DICT.put("工作电压", "工作电压");
        ATTR_DICT.put("通信协议", "通信协议");
        ATTR_DICT.put("回退协议", "回退协议");
        ATTR_DICT.put("仓体规格", "仓体规格");
        ATTR_DICT.put("接口形式", "接口形式");
        ATTR_DICT.put("孔距", "孔距");
        ATTR_DICT.put("长度", "长度");
        ATTR_DICT.put("宽度", "宽度");
        ATTR_DICT.put("高度", "高度");
        ATTR_DICT.put("轴径", "轴径");
        ATTR_DICT.put("电压", "电压");
        ATTR_DICT.put("流量", "流量");
        ATTR_DICT.put("功率", "功率");
        ATTR_DICT.put("转速", "转速");
    }
    /** 枚举值属性（取值是标识符而非数值，无需单位） */
    private static final Set<String> ENUM_ATTRS = Set.of("通信协议", "回退协议", "接口形式", "仓体规格");

    private static final String NUM = "(\\d+(?:\\.\\d+)?)";
    private static final String UNIT_CLASS =
            "毫米|厘米|英寸|inch|INCH|in|IN|mm|MM|cm|CM|m|M|kV|KV|伏特|伏|V|kW|KW|千瓦|瓦|W|MPa|兆帕|rpm|转每分|个月|月|年|U";
    private static final Pattern RANGE_BETWEEN =
            Pattern.compile("介于\\s*" + NUM + "\\s*[与和]\\s*" + NUM + "\\s*(" + UNIT_CLASS + ")?\\s*之间");
    private static final Pattern RANGE_TILDE =
            Pattern.compile(NUM + "\\s*(?:~|～|—|–|至|到)\\s*" + NUM + "\\s*(" + UNIT_CLASS + ")?");
    private static final Pattern COMPARATOR = Pattern.compile(
            "(不低于|不小于|至少|≥|>=|大于|>|不超过|不大于|至多|≤|<=|小于|<)\\s*" + NUM + "\\s*(" + UNIT_CLASS + ")?");
    private static final Pattern EQ_NUMERIC =
            Pattern.compile("(?:为|等于|＝|=)\\s*" + NUM + "\\s*(" + UNIT_CLASS + ")?");
    private static final Pattern BARE_NUMERIC =
            Pattern.compile("(?<![A-Za-z0-9\\-_.])" + NUM + "\\s*(" + UNIT_CLASS + ")?(?![A-Za-z0-9\\-_.])");
    private static final Pattern ELLIPSIS = Pattern.compile("同上|同左|同前|参见上|如前所述");
    private static final Pattern NESTED_EXCEPTION = Pattern.compile("除非|除了|例外|特批");
    private static final Pattern SUBST_PATTERN =
            Pattern.compile("(PM-[A-Za-z0-9\\-]+)\\s*可由\\s*(PM-[A-Za-z0-9\\-]+)\\s*替代");
    private static final Pattern TEST_TYPE_PATTERN =
            Pattern.compile("须通过\\s*([^\\s，,。]+?(?:试验|测试))(?:（([^）]+)）)?");
    private static final Pattern VALIDITY_PATTERN =
            Pattern.compile("有效期\\s*(\\d+(?:\\.\\d+)?)\\s*(个?月|年)");

    /** 抽取的单条条件 */
    public record Clause(String attr, String op, Double value, Double low, Double high,
                         String valueStr, List<String> valueList, String unit, String raw) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("attr", attr);
            m.put("op", op);
            if (value != null) m.put("value", value);
            if (low != null) m.put("low", low);
            if (high != null) m.put("high", high);
            if (valueStr != null) m.put("value", valueStr);
            if (valueList != null) m.put("valueList", valueList);
            if (unit != null) m.put("unit", unit);
            m.put("raw", raw);
            return m;
        }
    }

    /** 解析结果：PARSED / PENDING（待补） / REJECTED（拒绝） */
    public record Outcome(String outcome, String reason, List<Clause> clauses, String logic,
                          Map<String, Object> extra, String detail) {
        public boolean isParsed() { return "PARSED".equals(outcome); }
        public static Outcome parsed(List<Clause> clauses, String logic, Map<String, Object> extra, String detail) {
            return new Outcome("PARSED", null, clauses, logic, extra, detail);
        }
        public static Outcome pending(String reason, String detail) {
            return new Outcome("PENDING", reason, List.of(), null, Map.of(), detail);
        }
        public static Outcome rejected(String reason, String detail) {
            return new Outcome("REJECTED", reason, List.of(), null, Map.of(), detail);
        }
    }

    /** 文档级单位体系扫描结果 */
    public record UnitScan(Set<String> systems, List<String> unitlessNumbers) {
        public boolean mixedLengthSystemsWithUnitless() {
            return systems.contains("METRIC") && systems.contains("IMPERIAL") && !unitlessNumbers.isEmpty();
        }
    }

    public static String normalizeUnit(String surface) {
        if (surface == null) return null;
        String u = UNIT_DICT.get(surface);
        return u != null ? u : surface;
    }

    /**
     * 文档级单位体系扫描：先遮蔽"带单位的数值/区间"，再统计裸数值（无单位）。
     * 毫米族与英寸族同时出现且存在无单位数值 -> 必须停止（mixedLengthSystemsWithUnitless）。
     */
    public static UnitScan scanUnitSystems(List<String> texts) {
        Set<String> systems = new LinkedHashSet<>();
        List<String> unitless = new ArrayList<>();
        Pattern unitAfterNum = Pattern.compile(NUM + "\\s*(" + UNIT_CLASS + ")");
        for (String text : texts) {
            if (text == null) continue;
            // 1) 单位体系识别（仅长度两族参与硬停止判定）
            Matcher um = unitAfterNum.matcher(text);
            while (um.find()) {
                String norm = normalizeUnit(um.group(2));
                if (METRIC_LENGTH.contains(norm)) systems.add("METRIC");
                if (IMPERIAL_LENGTH.contains(norm)) systems.add("IMPERIAL");
            }
            // 2) 遮蔽带单位的数值区域：区间 -> 比较符+值+单位 -> 值+单位
            String masked = maskAll(RANGE_BETWEEN, text);
            masked = maskAll(RANGE_TILDE, masked);
            masked = maskAll(COMPARATOR, masked);
            masked = maskAll(unitAfterNum, masked);
            // 3) 剩余裸数值 = 无单位数值（型号/标准号/批次中的数字片段由环视排除）
            Matcher bm = BARE_NUMERIC.matcher(masked);
            while (bm.find()) {
                unitless.add(bm.group(1));
            }
        }
        return new UnitScan(systems, unitless);
    }

    private static String maskAll(Pattern p, String text) {
        Matcher m = p.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, " ".repeat(m.end() - m.start()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 解析单条原文（替代条件走专门分支）。
     * aspect: DIMENSION / POWER / PROTOCOL / INTERFACE / SUBSTITUTION
     */
    public static Outcome parse(String text, String aspect) {
        if (text == null || text.isBlank()) {
            return Outcome.rejected("EMPTY_TEXT", "原文为空，无法解析");
        }
        // 1) 省略句：受控语法不猜测上下文，直接拒绝
        if (ELLIPSIS.matcher(text).find()) {
            return Outcome.rejected("ELLIPSIS_UNSUPPORTED",
                    "原文含省略表述（同上/同左/参见），受控语法不猜测上下文，拒绝解析；请补全原句或改用决策表建模");
        }
        // 2) 嵌套例外：解析器拒绝，决策表工作流可准确表达
        if (NESTED_EXCEPTION.matcher(text).find()) {
            return Outcome.rejected("NESTED_EXCEPTION",
                    "原文含嵌套例外（除非/特批/例外），受控语法解析拒绝该材料；请改用受控决策表建模例外与继承");
        }
        if ("SUBSTITUTION".equals(aspect)) {
            return parseSubstitution(text);
        }
        // 3) 逻辑切分：且/并且/；/，连接的条件为 AND
        String[] segments = text.split("[，,；;。]|且|并且");
        List<Clause> clauses = new ArrayList<>();
        String firstPending = null;
        String firstPendingDetail = null;
        for (String seg : segments) {
            String s = seg.trim();
            if (s.isEmpty()) continue;
            ClauseParse cp = parseClause(s, aspect);
            if (cp.pendingReason != null) {
                if (firstPending == null) {
                    firstPending = cp.pendingReason;
                    firstPendingDetail = cp.detail;
                }
                continue;
            }
            if (cp.rejectedReason != null) {
                return Outcome.rejected(cp.rejectedReason, cp.detail);
            }
            clauses.add(cp.clause);
        }
        if (firstPending != null) {
            return Outcome.pending(firstPending, firstPendingDetail);
        }
        if (clauses.isEmpty()) {
            return Outcome.rejected("UNSUPPORTED_GRAMMAR",
                    "未识别出任何受控语法条款（属性/比较符/量纲不在受控词表），拒绝解析");
        }
        return Outcome.parsed(clauses, "AND", Map.of(),
                "抽取 " + clauses.size() + " 个条件（AND 连接），原句保留于条目原文");
    }

    /** 替代条件：必须含测试类型与有效期，缺一则证明不足（不可满足） */
    private static Outcome parseSubstitution(String text) {
        Matcher sm = SUBST_PATTERN.matcher(text);
        if (!sm.find()) {
            return Outcome.rejected("UNSUPPORTED_GRAMMAR",
                    "替代条件须为「<型号A> 可由 <型号B> 替代」句式，未匹配，拒绝解析");
        }
        String fromModel = sm.group(1);
        String toModel = sm.group(2);
        List<String> missing = new ArrayList<>();
        String testType = null;
        String testStandard = null;
        Double validityMonths = null;

        Matcher tm = TEST_TYPE_PATTERN.matcher(text);
        if (tm.find()) {
            testType = tm.group(1);
            testStandard = tm.group(2);
        } else {
            missing.add("测试类型");
        }
        Matcher vm = VALIDITY_PATTERN.matcher(text);
        if (vm.find()) {
            validityMonths = Double.parseDouble(vm.group(1));
            if ("年".equals(vm.group(2))) {
                validityMonths = validityMonths * 12;
            }
        } else {
            missing.add("有效期");
        }
        if (!missing.isEmpty()) {
            return Outcome.pending("PROOF_MISSING",
                    "替代条件缺" + String.join("、", missing) + "，前置证明不足，该替代条件不可满足；只进入待补队列");
        }
        List<Clause> clauses = List.of(
                new Clause("testType", "EQ", null, null, null, testType, null, null, "须通过 " + testType),
                new Clause("monthsInService", "LE", validityMonths, null, null, null, null, "month",
                        "有效期 " + validityMonths.longValue() + " 个月"));
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("fromModel", fromModel);
        extra.put("toModel", toModel);
        if (testStandard != null) extra.put("testStandard", testStandard);
        return Outcome.parsed(clauses, "AND", extra,
                "替代条件齐备：测试类型=" + testType + "，有效期=" + validityMonths.longValue() + " 个月");
    }

    private static class ClauseParse {
        Clause clause;
        String pendingReason;
        String rejectedReason;
        String detail;
    }

    private static ClauseParse parseClause(String segment, String aspect) {
        ClauseParse cp = new ClauseParse();
        // 属性识别（受控词表，长词优先）
        String attr = null;
        for (Map.Entry<String, String> e : ATTR_DICT.entrySet()) {
            if (segment.contains(e.getKey())) { attr = e.getValue(); break; }
        }
        if (attr == null) {
            cp.rejectedReason = "UNSUPPORTED_GRAMMAR";
            cp.detail = "分句「" + segment + "」未命中受控属性词表，拒绝解析";
            return cp;
        }
        boolean enumAttr = ENUM_ATTRS.contains(attr);
        boolean unitRequired = !enumAttr;

        // 区间：介于 A 与 B unit 之间 / A ~ B unit
        Matcher m = RANGE_BETWEEN.matcher(segment);
        boolean found = m.find();
        if (!found) {
            m = RANGE_TILDE.matcher(segment);
            found = m.find();
        }
        if (found) {
            double low = Double.parseDouble(m.group(1));
            double high = Double.parseDouble(m.group(2));
            String unit = normalizeUnit(m.group(3));
            if (low > high) {
                cp.pendingReason = "ENDPOINT_CONFLICT";
                cp.detail = "区间端点倒置：下界 " + low + " > 上界 " + high + "，端点冲突，进入待补队列";
                return cp;
            }
            if (unitRequired && unit == null) {
                cp.pendingReason = "UNIT_MISSING";
                cp.detail = "区间 [" + low + ", " + high + "] 缺量纲单位，进入待补队列";
                return cp;
            }
            cp.clause = new Clause(attr, "RANGE", null, low, high, null, null, unit, segment);
            return cp;
        }
        // 区间句式残缺（介于/范围/~ 出现但无法匹配完整区间） -> 端点不明
        if (segment.contains("介于") || segment.contains("~") || segment.contains("～")
                || segment.contains("范围")) {
            cp.pendingReason = "ENDPOINT_UNCLEAR";
            cp.detail = "区间句式残缺或端点不明（「" + segment + "」），进入待补队列";
            return cp;
        }
        // 比较符
        Matcher cm = COMPARATOR.matcher(segment);
        if (cm.find()) {
            String op = switch (cm.group(1)) {
                case "不低于", "不小于", "至少", "≥", ">=" -> "GE";
                case "不超过", "不大于", "至多", "≤", "<=" -> "LE";
                case "大于", ">" -> "GT";
                case "小于", "<" -> "LT";
                default -> "EQ";
            };
            double value = Double.parseDouble(cm.group(2));
            String unit = normalizeUnit(cm.group(3));
            if (unitRequired && unit == null) {
                cp.pendingReason = "UNIT_MISSING";
                cp.detail = "比较值 " + value + " 缺量纲单位，进入待补队列";
                return cp;
            }
            cp.clause = new Clause(attr, op, value, null, null, null, null, unit, segment);
            return cp;
        }
        // 枚举：为 X 或 Y
        if (enumAttr) {
            int idx = segment.indexOf("为");
            if (idx >= 0) {
                List<String> values = new ArrayList<>();
                Matcher em = Pattern.compile("([A-Za-z][A-Za-z0-9\\-]*)")
                        .matcher(segment.substring(idx + 1));
                while (em.find()) values.add(em.group(1));
                if (!values.isEmpty()) {
                    if (values.size() > 1) {
                        cp.clause = new Clause(attr, "IN", null, null, null, null, values, null, segment);
                    } else {
                        cp.clause = new Clause(attr, "EQ", null, null, null, values.get(0), null, null, segment);
                    }
                    return cp;
                }
            }
        }
        // 等值数值：为 150 mm / 安装孔距 150
        Matcher qm = EQ_NUMERIC.matcher(segment);
        boolean eqFound = qm.find();
        Matcher nm = eqFound ? qm : BARE_NUMERIC.matcher(segment);
        if (eqFound || nm.find()) {
            double value = Double.parseDouble(nm.group(1));
            String unit = normalizeUnit(nm.group(2));
            if (unitRequired && unit == null) {
                cp.pendingReason = "UNIT_MISSING";
                cp.detail = "数值 " + value + " 缺量纲单位（属性「" + attr + "」要求单位），进入待补队列";
                return cp;
            }
            cp.clause = new Clause(attr, "EQ", value, null, null, null, null, unit, segment);
            return cp;
        }
        cp.rejectedReason = "UNSUPPORTED_GRAMMAR";
        cp.detail = "分句「" + segment + "」未识别出比较符/量纲/枚举取值，拒绝解析";
        return cp;
    }
}
