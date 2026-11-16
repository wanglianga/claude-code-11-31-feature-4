package com.mdm.platform.service.spec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受控语法解析器纯函数单测（不依赖数据库）：
 * 单位缺失进待补、毫米/英寸混排硬停止、端点倒置、嵌套例外拒绝、
 * 替代条件缺测试类型/有效期不可满足、区间与比较符抽取。
 */
class SpecTextParserTest {

    @Test
    void parsesRangeWithUnit() {
        var o = SpecTextParser.parse("主轴电机轴向长度介于 279 与 281 mm 之间。", "DIMENSION");
        assertTrue(o.isParsed());
        assertEquals(1, o.clauses().size());
        var c = o.clauses().get(0);
        assertEquals("轴向长度", c.attr());
        assertEquals("RANGE", c.op());
        assertEquals(279.0, c.low());
        assertEquals(281.0, c.high());
        assertEquals("mm", c.unit());
    }

    @Test
    void parsesComparatorAndLogicAnd() {
        var o = SpecTextParser.parse("工作电压不低于 380 V，且通信协议为 EtherCAT。", "POWER");
        assertTrue(o.isParsed());
        assertEquals(2, o.clauses().size());
        assertEquals("GE", o.clauses().get(0).op());
        assertEquals(380.0, o.clauses().get(0).value());
        assertEquals("V", o.clauses().get(0).unit());
        assertEquals("通信协议", o.clauses().get(1).attr());
        assertEquals("EtherCAT", o.clauses().get(1).valueStr());
        assertEquals("AND", o.logic());
    }

    @Test
    void missingUnitGoesToPendingQueue() {
        var o = SpecTextParser.parse("安装孔距 150。", "DIMENSION");
        assertEquals("PENDING", o.outcome());
        assertEquals("UNIT_MISSING", o.reason());
    }

    @Test
    void invertedRangeGoesToPendingQueue() {
        var o = SpecTextParser.parse("扬程范围 30 ~ 12 m。", "DIMENSION");
        assertEquals("PENDING", o.outcome());
        assertEquals("ENDPOINT_CONFLICT", o.reason());
    }

    @Test
    void nestedExceptionIsRejected() {
        var o = SpecTextParser.parse("电源仓仅适配 2U 模块，除非批次 B-77 特批 3U，否则不得超出。", "DIMENSION");
        assertEquals("REJECTED", o.outcome());
        assertEquals("NESTED_EXCEPTION", o.reason());
    }

    @Test
    void ellipsisIsRejected() {
        var o = SpecTextParser.parse("安装孔距同上。", "DIMENSION");
        assertEquals("REJECTED", o.outcome());
        assertEquals("ELLIPSIS_UNSUPPORTED", o.reason());
    }

    @Test
    void substitutionMissingProofIsUnsatisfiable() {
        var o = SpecTextParser.parse("PM-MOTOR-100A 可由 PM-MOTOR-100B 替代。", "SUBSTITUTION");
        assertEquals("PENDING", o.outcome());
        assertEquals("PROOF_MISSING", o.reason());
        assertTrue(o.detail().contains("测试类型"));
        assertTrue(o.detail().contains("有效期"));
    }

    @Test
    void substitutionWithProofParses() {
        var o = SpecTextParser.parse(
                "PM-SDU-A1 可由 PM-SDU-C3 替代，须通过 振动试验（IEC 60068-2-6），有效期 24 个月。",
                "SUBSTITUTION");
        assertTrue(o.isParsed());
        assertEquals("PM-SDU-A1", o.extra().get("fromModel"));
        assertEquals("PM-SDU-C3", o.extra().get("toModel"));
        assertEquals("IEC 60068-2-6", o.extra().get("testStandard"));
        assertEquals(2, o.clauses().size());
        assertEquals("testType", o.clauses().get(0).attr());
        assertEquals("振动试验", o.clauses().get(0).valueStr());
        assertEquals("monthsInService", o.clauses().get(1).attr());
        assertEquals(24.0, o.clauses().get(1).value());
    }

    @Test
    void mixedMmAndInchWithUnitlessNumberMustStop() {
        var scan = SpecTextParser.scanUnitSystems(List.of(
                "泵体长度为 120 mm。", "法兰外径 2.5 inch。", "连接孔距 85。"));
        assertTrue(scan.systems().contains("METRIC"));
        assertTrue(scan.systems().contains("IMPERIAL"));
        assertFalse(scan.unitlessNumbers().isEmpty());
        assertTrue(scan.mixedLengthSystemsWithUnitless(), "毫米+英寸混排且数值无单位必须停止");
    }

    @Test
    void singleSystemWithUnitlessDoesNotHardStop() {
        // 仅公制 + 无单位数值：不硬停止，由条目级 UNIT_MISSING 进待补
        var scan = SpecTextParser.scanUnitSystems(List.of(
                "主轴电机轴向长度介于 279 与 281 mm 之间。", "安装孔距 150。"));
        assertTrue(scan.systems().contains("METRIC"));
        assertFalse(scan.systems().contains("IMPERIAL"));
        assertFalse(scan.mixedLengthSystemsWithUnitless());
        assertEquals(List.of("150"), scan.unitlessNumbers());
    }

    @Test
    void modelCodesAndStandardsAreNotUnitlessNumbers() {
        var scan = SpecTextParser.scanUnitSystems(List.of(
                "PM-SDU-A1 可由 PM-SDU-C3 替代，须通过 振动试验（IEC 60068-2-6），有效期 24 个月。"));
        assertTrue(scan.unitlessNumbers().isEmpty(),
                "型号/标准号/带单位数值不得误判为无单位数值: " + scan.unitlessNumbers());
    }
}
