package com.mdm.platform.service.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 谓词求值与边界测试（纯函数）。
 *
 * 规则资产 = 按优先级排序的规则行 [{rowId, conditions, effect}]，逐行匹配，
 * 首个全部条件成立的行生效；显式默认行（conditions 为空）兜底。
 *
 * 两种求值模式：
 *  - 全谓词模式（targetAttr=null）：边界测试、试算、通用反例 —— 输入按闭世界处理，
 *    条件涉及的属性在输入中缺失即视为不成立（缺证明不可满足）。
 *  - 维度限定模式（targetAttr=A）：共享反例集按目标属性逐维验证，只评估该维度
 *    与上下文条件，其余维度不在本用例考察范围。
 */
public final class PredicateEngine {

    private PredicateEngine() {}

    public static final String ACCEPT = "ACCEPT";
    public static final String REJECT = "REJECT";

    /**
     * 求值。
     * @param rows       编译后的规则行（含默认行）
     * @param input      {attrs: {attr: {value, unit}}, context: {key: value}}
     * @param targetAttr 非空时只评估该属性维度与上下文条件（共享反例集模式）
     * @return ACCEPT / REJECT
     */
    @SuppressWarnings("unchecked")
    public static String evaluate(List<Map<String, Object>> rows, Map<String, Object> input, String targetAttr) {
        Map<String, Object> attrs = input.get("attrs") instanceof Map
                ? (Map<String, Object>) input.get("attrs") : Map.of();
        Map<String, Object> context = input.get("context") instanceof Map
                ? (Map<String, Object>) input.get("context") : Map.of();

        for (Map<String, Object> row : rows) {
            List<Map<String, Object>> conditions = row.get("conditions") instanceof List
                    ? (List<Map<String, Object>>) row.get("conditions") : List.of();
            boolean isDefaultRow = Boolean.TRUE.equals(row.get("default")) || conditions.isEmpty();
            if (isDefaultRow) {
                return String.valueOf(row.getOrDefault("effect", REJECT));
            }
            boolean ok = true;
            boolean exercised = false;
            for (Map<String, Object> cond : conditions) {
                String attr = String.valueOf(cond.get("attr"));
                boolean isContext = Boolean.TRUE.equals(cond.get("context"));
                if (targetAttr != null && !isContext && !attr.equals(targetAttr)) {
                    continue; // 维度限定模式：非目标维度不在本用例考察范围
                }
                Object raw = isContext ? context.get(attr)
                        : (attrs.get(attr) instanceof Map ? ((Map<String, Object>) attrs.get(attr)).get("value") : null);
                Object inputUnit = attrs.get(attr) instanceof Map
                        ? ((Map<String, Object>) attrs.get(attr)).get("unit") : null;
                if (raw == null) {
                    ok = false; // 闭世界：条件涉及的输入缺失即不成立
                    break;
                }
                if (!matches(cond, raw, inputUnit == null ? null : String.valueOf(inputUnit))) {
                    ok = false;
                    break;
                }
                exercised = true;
            }
            if (ok && (exercised || targetAttr == null)) {
                return String.valueOf(row.getOrDefault("effect", REJECT));
            }
        }
        return REJECT; // 无默认行时安全兜底：不宽松放行
    }

    /** 单条件匹配：数值比较 / 区间 / 枚举；带单位条件要求输入单位一致（规范化后） */
    private static boolean matches(Map<String, Object> cond, Object raw, String inputUnit) {
        String op = String.valueOf(cond.getOrDefault("op", "EQ"));
        String condUnit = cond.get("unit") == null ? null
                : SpecTextParser.normalizeUnit(String.valueOf(cond.get("unit")));
        String normInputUnit = inputUnit == null ? null : SpecTextParser.normalizeUnit(inputUnit);
        if (condUnit != null && normInputUnit != null && !condUnit.equals(normInputUnit)) {
            return false; // 单位体系不符（如 mm vs inch）；输入未声明单位时不考察单位维度
        }
        if ("IN".equals(op)) {
            Object list = cond.get("valueList");
            if (list instanceof List<?> values) {
                return values.stream().anyMatch(v -> String.valueOf(v).equalsIgnoreCase(String.valueOf(raw)));
            }
            return false;
        }
        Double num = toDouble(raw);
        if ("RANGE".equals(op)) {
            if (num == null) return false;
            double low = toDouble(cond.get("low"));
            double high = toDouble(cond.get("high"));
            return num >= low && num <= high;
        }
        Object expected = cond.get("value");
        if (num != null && expected instanceof Number) {
            double exp = ((Number) expected).doubleValue();
            return switch (op) {
                case "EQ" -> num == exp;
                case "NE" -> num != exp;
                case "GE" -> num >= exp;
                case "LE" -> num <= exp;
                case "GT" -> num > exp;
                case "LT" -> num < exp;
                default -> false;
            };
        }
        // 枚举等值
        return "EQ".equals(op) && expected != null
                && String.valueOf(expected).equalsIgnoreCase(String.valueOf(raw));
    }

    private static Double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return o == null ? null : Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 由编译谓词生成边界测试：对每个数值条件在下界-ε/下界/上界/上界+ε 处取点，
     * 同行的其他条件用可满足值填充（例外行的上下文条件一并带入）。
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> boundaryTests(List<Map<String, Object>> rows) {
        List<Map<String, Object>> tests = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<Map<String, Object>> conditions = row.get("conditions") instanceof List
                    ? (List<Map<String, Object>>) row.get("conditions") : List.of();
            if (conditions.isEmpty()) continue;
            String rowId = String.valueOf(row.getOrDefault("rowId", "row"));
            String rowEffect = String.valueOf(row.getOrDefault("effect", ACCEPT));
            for (Map<String, Object> cond : conditions) {
                if (Boolean.TRUE.equals(cond.get("context"))) continue;
                String attr = String.valueOf(cond.get("attr"));
                String op = String.valueOf(cond.getOrDefault("op", "EQ"));
                String unit = cond.get("unit") == null ? null : String.valueOf(cond.get("unit"));
                List<double[]> points = new ArrayList<>(); // {value, expectSatisfy}
                switch (op) {
                    case "RANGE" -> {
                        double low = toDouble(cond.get("low"));
                        double high = toDouble(cond.get("high"));
                        double eps = Math.max(1, Math.abs(high - low)) * 0.001;
                        points.add(new double[]{round6(low - eps), 0});
                        points.add(new double[]{low, 1});
                        points.add(new double[]{high, 1});
                        points.add(new double[]{round6(high + eps), 0});
                    }
                    case "GE" -> {
                        double v = toDouble(cond.get("value"));
                        double eps = Math.max(1, Math.abs(v)) * 0.001;
                        points.add(new double[]{round6(v - eps), 0});
                        points.add(new double[]{v, 1});
                    }
                    case "LE" -> {
                        double v = toDouble(cond.get("value"));
                        double eps = Math.max(1, Math.abs(v)) * 0.001;
                        points.add(new double[]{v, 1});
                        points.add(new double[]{round6(v + eps), 0});
                    }
                    case "GT" -> {
                        double v = toDouble(cond.get("value"));
                        double eps = Math.max(1, Math.abs(v)) * 0.001;
                        points.add(new double[]{v, 0});
                        points.add(new double[]{round6(v + eps), 1});
                    }
                    case "LT" -> {
                        double v = toDouble(cond.get("value"));
                        double eps = Math.max(1, Math.abs(v)) * 0.001;
                        points.add(new double[]{round6(v - eps), 1});
                        points.add(new double[]{v, 0});
                    }
                    case "EQ" -> {
                        if (cond.get("value") instanceof Number) {
                            double v = toDouble(cond.get("value"));
                            double eps = Math.max(1, Math.abs(v)) * 0.001;
                            points.add(new double[]{v, 1});
                            points.add(new double[]{round6(v + eps), 0});
                        } else {
                            // 枚举等值：命中接受 / 未声明值拒绝
                            addEnumTest(tests, rowId, rowEffect, conditions, cond, row);
                            continue;
                        }
                    }
                    default -> { }
                }
                for (double[] p : points) {
                    boolean satisfy = p[1] == 1;
                    Map<String, Object> input = baseInputWithSiblings(conditions, row);
                    setAttr(input, attr, p[0], unit);
                    // 期望：该行生效且本条件满足 -> 行结论；否则继续匹配后续行，保守期望 REJECT
                    String expect = satisfy ? rowEffect : REJECT;
                    Map<String, Object> test = new LinkedHashMap<>();
                    test.put("name", rowId + "/" + attr + "@" + p[0] + (unit == null ? "" : unit));
                    test.put("input", input);
                    test.put("expect", expect);
                    test.put("basis", "边界测试：" + rowId + " 条件 " + attr + " " + op
                            + (satisfy ? " 界内点" : " 界外点"));
                    tests.add(test);
                }
            }
        }
        return tests;
    }

    private static void addEnumTest(List<Map<String, Object>> tests, String rowId, String rowEffect,
                                    List<Map<String, Object>> conditions, Map<String, Object> cond,
                                    Map<String, Object> row) {
        String attr = String.valueOf(cond.get("attr"));
        Map<String, Object> hitInput = baseInputWithSiblings(conditions, row);
        setAttr(hitInput, attr, cond.get("value"), null);
        tests.add(Map.of("name", rowId + "/" + attr + "@enum-hit", "input", hitInput,
                "expect", rowEffect, "basis", "边界测试：枚举命中"));
        Map<String, Object> missInput = baseInputWithSiblings(conditions, row);
        setAttr(missInput, attr, "__UNDEFINED__", null);
        tests.add(Map.of("name", rowId + "/" + attr + "@enum-miss", "input", missInput,
                "expect", REJECT, "basis", "边界测试：未声明枚举值"));
    }

    /** 构造输入：同行其他条件用可满足值填充（上下文条件带入 context） */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> baseInputWithSiblings(List<Map<String, Object>> conditions,
                                                             Map<String, Object> row) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("attrs", new LinkedHashMap<String, Object>());
        input.put("context", new LinkedHashMap<String, Object>());
        for (Map<String, Object> c : conditions) {
            String attr = String.valueOf(c.get("attr"));
            Object fill = satisfyingValue(c);
            String unit = c.get("unit") == null ? null : String.valueOf(c.get("unit"));
            if (Boolean.TRUE.equals(c.get("context"))) {
                ((Map<String, Object>) input.get("context")).put(attr, fill);
            } else {
                setAttr(input, attr, fill, unit);
            }
        }
        return input;
    }

    private static Object satisfyingValue(Map<String, Object> cond) {
        return switch (String.valueOf(cond.getOrDefault("op", "EQ"))) {
            case "RANGE" -> (toDouble(cond.get("low")) + toDouble(cond.get("high"))) / 2.0;
            case "GT" -> toDouble(cond.get("value")) + Math.max(1, Math.abs(toDouble(cond.get("value")))) * 0.001;
            case "LT" -> toDouble(cond.get("value")) - Math.max(1, Math.abs(toDouble(cond.get("value")))) * 0.001;
            default -> cond.get("value");
        };
    }

    @SuppressWarnings("unchecked")
    private static void setAttr(Map<String, Object> input, String attr, Object value, String unit) {
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("value", value);
        if (unit != null) cell.put("unit", unit);
        ((Map<String, Object>) input.get("attrs")).put(attr, cell);
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }
}
