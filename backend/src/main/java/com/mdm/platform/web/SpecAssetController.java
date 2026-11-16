package com.mdm.platform.web;

import com.mdm.platform.service.DecisionTableService;
import com.mdm.platform.service.RuleAssetService;
import com.mdm.platform.service.SpecParseService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 工程资料规则资产化接口：
 * 资料原文 -> 受控解析/决策表 -> 统一反例集验证 -> 发布闸门 -> 修订撤销（只影响后续版本）。
 */
@RestController
@RequestMapping("/spec")
public class SpecAssetController {

    private final SpecParseService parseService;
    private final DecisionTableService decisionTables;
    private final RuleAssetService ruleAssets;

    public SpecAssetController(SpecParseService parseService, DecisionTableService decisionTables,
                               RuleAssetService ruleAssets) {
        this.parseService = parseService;
        this.decisionTables = decisionTables;
        this.ruleAssets = ruleAssets;
    }

    // ---------- 资料原文与条目 ----------
    @GetMapping("/documents")
    public ApiResult<List<Map<String, Object>>> documents() {
        return ApiResult.ok(parseService.listDocuments());
    }

    @GetMapping("/documents/{id}")
    public ApiResult<Map<String, Object>> documentDetail(@PathVariable long id) {
        return ApiResult.ok(parseService.documentDetail(id));
    }

    @GetMapping("/statements")
    public ApiResult<List<Map<String, Object>>> statements(@RequestParam(required = false) String status) {
        return ApiResult.ok(parseService.listStatements(status));
    }

    @GetMapping("/pending")
    public ApiResult<List<Map<String, Object>>> pendingQueue() {
        return ApiResult.ok(parseService.pendingQueue());
    }

    @PostMapping("/statements/{id}/parse")
    public ApiResult<Map<String, Object>> parse(@PathVariable long id) {
        return ApiResult.ok(parseService.parseStatement(id));
    }

    @PostMapping("/statements/{id}/supplement")
    public ApiResult<Map<String, Object>> supplement(@PathVariable long id,
                                                     @RequestBody Map<String, String> body) {
        return ApiResult.ok(parseService.supplement(id, body.get("supplementedText"), body.get("note")));
    }

    @PostMapping("/statements/{id}/compile")
    public ApiResult<Map<String, Object>> compileFromParse(@PathVariable long id) {
        return ApiResult.ok(ruleAssets.compileFromParse(id));
    }

    // ---------- 决策表工作流 ----------
    @GetMapping("/decision-tables")
    public ApiResult<List<Map<String, Object>>> tables() {
        return ApiResult.ok(decisionTables.listTables());
    }

    @PostMapping("/decision-tables")
    @SuppressWarnings("unchecked")
    public ApiResult<Map<String, Object>> createTable(@RequestBody Map<String, Object> body) {
        Object rows = body.get("rows");
        if (!(rows instanceof List)) {
            throw new IllegalArgumentException("rows 必须为数组（决策表行）");
        }
        return ApiResult.ok(decisionTables.createTable(
                String.valueOf(body.get("tableCode")),
                String.valueOf(body.getOrDefault("tableVersion", "v1")),
                ((Number) body.get("statementId")).longValue(),
                (List<Map<String, Object>>) rows,
                body.get("inheritsTable") == null ? null : String.valueOf(body.get("inheritsTable"))));
    }

    @PostMapping("/decision-tables/{id}/compile")
    public ApiResult<Map<String, Object>> compileTable(@PathVariable long id) {
        return ApiResult.ok(decisionTables.compile(id));
    }

    // ---------- 规则资产 ----------
    @GetMapping("/assets")
    public ApiResult<List<Map<String, Object>>> assets(@RequestParam(required = false) String status) {
        return ApiResult.ok(ruleAssets.listAssets(status));
    }

    @GetMapping("/assets/{id}")
    public ApiResult<Map<String, Object>> assetDetail(@PathVariable long id) {
        return ApiResult.ok(ruleAssets.assetDetail(id));
    }

    @PostMapping("/assets/{id}/validate")
    public ApiResult<Map<String, Object>> validate(@PathVariable long id) {
        return ApiResult.ok(ruleAssets.validate(id));
    }

    @PostMapping("/assets/{id}/publish")
    public ApiResult<Map<String, Object>> publish(@PathVariable long id) {
        return ApiResult.ok(ruleAssets.publish(id));
    }

    @PostMapping("/assets/{id}/evaluate")
    public ApiResult<Map<String, Object>> evaluate(@PathVariable long id,
                                                   @RequestBody(required = false) Map<String, Object> input) {
        return ApiResult.ok(ruleAssets.evaluateAsset(id, input));
    }

    // ---------- 反例集 / 验证记录 / 修订通知 ----------
    @GetMapping("/counterexamples")
    public ApiResult<List<Map<String, Object>>> counterexamples() {
        return ApiResult.ok(ruleAssets.listCounterexamples());
    }

    @GetMapping("/validation-runs")
    public ApiResult<List<Map<String, Object>>> validationRuns(@RequestParam(required = false) Long assetId) {
        return ApiResult.ok(ruleAssets.listValidationRuns(assetId));
    }

    @PostMapping("/documents/{id}/withdraw")
    public ApiResult<Map<String, Object>> withdraw(@PathVariable long id,
                                                   @RequestBody Map<String, Object> body) {
        Long successor = body.get("successorDocumentId") == null ? null
                : ((Number) body.get("successorDocumentId")).longValue();
        return ApiResult.ok(ruleAssets.withdrawDocument(id,
                body.get("note") == null ? null : String.valueOf(body.get("note")), successor));
    }

    @GetMapping("/notices")
    public ApiResult<List<Map<String, Object>>> notices() {
        return ApiResult.ok(ruleAssets.listNotices());
    }
}
