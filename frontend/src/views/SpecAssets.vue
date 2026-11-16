<template>
  <div>
    <el-card class="page-card">
      <template #header>
        <b>工程资料规则资产化</b>
        <span class="hint">尺寸/接口/替代条件须先审查为规则资产（原文位置/对象/单位/边界/适用范围/前置证明/反例）才进入主数据；缺单位、端点不明、来源冲突只进待补队列；无法编译的陈述不得宽松发布</span>
      </template>
      <el-tabs v-model="tab">
        <!-- ============ 页签1：资料原文与待补队列 ============ -->
        <el-tab-pane label="资料原文与待补队列" name="docs">
          <h4>资料文档（按 文档编号+修订 登记，修订撤销只影响后续规则版本）</h4>
          <el-table :data="documents" stripe size="small">
            <el-table-column prop="doc_code" label="文档编号" width="150" class-name="mono" />
            <el-table-column prop="revision" label="修订" width="70" />
            <el-table-column prop="title" label="标题" min-width="200" />
            <el-table-column prop="source_team" label="来源组" width="110" />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="docStatusType(row.status)">{{ docStatusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130">
              <template #default="{ row }">
                <el-button v-if="row.status === 'VERIFIED'" type="danger" size="small"
                           :disabled="!auth.canWrite" @click="withdraw(row)">撤销修订</el-button>
              </template>
            </el-table-column>
          </el-table>

          <h4>原文条目（原句保留不改写；待补只追加补齐文本）
            <el-tag v-if="pending.length" type="warning" size="small" effect="dark">待补队列 {{ pending.length }}</el-tag>
          </h4>
          <el-table :data="statements" stripe size="small" :row-class-name="rowClass">
            <el-table-column prop="statement_code" label="条目号" width="110" class-name="mono" />
            <el-table-column prop="source_locator" label="原文位置" width="130" class-name="mono" />
            <el-table-column label="原句 / 补齐文本" min-width="300">
              <template #default="{ row }">
                <div>{{ row.original_text }}</div>
                <div v-if="row.supplemented_text" class="supp">补齐：{{ row.supplemented_text }}</div>
              </template>
            </el-table-column>
            <el-table-column label="方面" width="90">
              <template #default="{ row }"><el-tag size="small" effect="plain">{{ aspectText(row.aspect) }}</el-tag></template>
            </el-table-column>
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag size="small" :type="statementStatusType(row.status)">{{ statementStatusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="待补/拒绝原因" width="170">
              <template #default="{ row }">
                <span v-if="row.pending_reason" class="reason">{{ pendingReasonText(row.pending_reason) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="220" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status === 'DRAFT'" size="small" type="primary" :disabled="!auth.canWrite"
                           @click="parse(row)">解析</el-button>
                <el-button v-if="row.status === 'PENDING_SUPPLEMENT'" size="small" type="warning"
                           :disabled="!auth.canWrite" @click="openSupplement(row)">补齐</el-button>
                <el-button v-if="row.status === 'PARSED'" size="small" type="success" :disabled="!auth.canWrite"
                           @click="compile(row)">编译</el-button>
                <el-button size="small" @click="showReport(row)">解析报告</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ============ 页签2：规则资产 ============ -->
        <el-tab-pane label="规则资产与统一验证" name="assets">
          <el-table :data="assets" stripe size="small">
            <el-table-column prop="asset_code" label="资产号" width="100" class-name="mono" />
            <el-table-column prop="asset_version" label="版本" width="70" />
            <el-table-column label="工作流" width="120">
              <template #default="{ row }">
                <el-tag size="small" :type="row.workflow === 'DECISION_TABLE' ? 'warning' : ''" effect="plain">
                  {{ workflowText(row.workflow) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="statement_code" label="来源条目" width="110" class-name="mono" />
            <el-table-column prop="object_code" label="对象" width="140" class-name="mono" />
            <el-table-column label="方面" width="90">
              <template #default="{ row }"><el-tag size="small" effect="plain">{{ aspectText(row.aspect) }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="unit" label="单位" width="70" />
            <el-table-column label="状态" width="130">
              <template #default="{ row }">
                <el-tag size="small" :type="assetStatusType(row.status)">{{ assetStatusText(row.status) }}</el-tag>
                <el-tag v-if="row.source_withdrawn" size="small" type="danger" effect="plain">来源已撤销</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="统一验证" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.validation_result" size="small"
                        :type="row.validation_result === 'PASS' ? 'success' : 'danger'">
                  {{ row.validation_result }}</el-tag>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="260" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status === 'COMPILED'" size="small" type="primary" :disabled="!auth.canWrite"
                           @click="validateAsset(row)">统一验证</el-button>
                <el-button v-if="row.status === 'VALIDATED'" size="small" type="success" :disabled="!auth.canWrite"
                           @click="publish(row)">发布</el-button>
                <el-button size="small" @click="showAsset(row)">详情回链</el-button>
                <el-button size="small" @click="openEval(row)">试算</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ============ 页签3：决策表工作流 ============ -->
        <el-tab-pane label="决策表工作流" name="tables">
          <div class="bar">
            <span class="hint">语法解析器拒绝的材料（嵌套例外/省略）在此人工建模：例外行优先、默认行兜底拒绝、支持表继承；表格版本受控维护</span>
            <el-button type="primary" size="small" :disabled="!auth.canWrite" @click="tableDialog = true">新建决策表</el-button>
          </div>
          <el-table :data="tables" stripe size="small">
            <el-table-column prop="table_code" label="表号" width="130" class-name="mono" />
            <el-table-column prop="table_version" label="版本" width="70" />
            <el-table-column prop="statement_code" label="来源条目" width="110" class-name="mono" />
            <el-table-column prop="inherits_table" label="继承父表" width="110" class-name="mono" />
            <el-table-column prop="modeled_by" label="建模人" width="110" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'COMPILED' ? 'success' : 'info'">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="行数" width="70">
              <template #default="{ row }">{{ row.rows_spec.length }}</template>
            </el-table-column>
            <el-table-column label="操作" width="180">
              <template #default="{ row }">
                <el-button v-if="row.status === 'DRAFT'" size="small" type="success" :disabled="!auth.canWrite"
                           @click="compileTable(row)">编译为谓词</el-button>
                <el-button size="small" @click="showTable(row)">查看行</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ============ 页签4：反例集与验证运行 ============ -->
        <el-tab-pane label="统一反例集与验证运行" name="cases">
          <h4>统一反例集（两种工作流的产物都用它验证；ACCEPT 用例防止过度拒绝）</h4>
          <el-table :data="counterexamples" stripe size="small">
            <el-table-column prop="case_code" label="用例号" width="120" class-name="mono" />
            <el-table-column label="方面" width="90">
              <template #default="{ row }"><el-tag size="small" effect="plain">{{ aspectText(row.aspect) }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="target_attr" label="目标属性" width="100" />
            <el-table-column label="输入" min-width="240">
              <template #default="{ row }"><span class="mono small">{{ JSON.stringify(row.input_spec) }}</span></template>
            </el-table-column>
            <el-table-column label="期望" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.expected_verdict === 'ACCEPT' ? 'success' : 'danger'">
                  {{ row.expected_verdict }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="rationale" label="依据" min-width="200" />
          </el-table>
          <h4>验证运行留痕（反例集 + 资产边界测试）</h4>
          <el-table :data="runs" stripe size="small">
            <el-table-column prop="id" label="ID" width="60" />
            <el-table-column prop="asset_id" label="资产ID" width="80" />
            <el-table-column prop="validator_version" label="验证器" width="110" class-name="mono" />
            <el-table-column prop="total_cases" label="用例数" width="80" />
            <el-table-column prop="passed_cases" label="通过" width="70" />
            <el-table-column label="结果" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.result === 'PASS' ? 'success' : 'danger'">{{ row.result }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="失败明细" min-width="260">
              <template #default="{ row }">
                <span v-if="row.failures && row.failures.length" class="mono small">{{ JSON.stringify(row.failures) }}</span>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column prop="created_at" label="时间" width="160" />
          </el-table>
        </el-tab-pane>

        <!-- ============ 页签5：修订通知 ============ -->
        <el-tab-pane label="修订/撤销通知" name="notices">
          <el-table :data="notices" stripe size="small">
            <el-table-column prop="notice_code" label="通知号" width="100" class-name="mono" />
            <el-table-column label="类型" width="140">
              <template #default="{ row }">
                <el-tag size="small" type="danger">{{ row.notice_type === 'REVISION_WITHDRAW' ? '修订撤销' : '修订替换' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="被撤销修订" width="180">
              <template #default="{ row }"><span class="mono">{{ row.doc_code }} rev{{ row.revision }}</span></template>
            </el-table-column>
            <el-table-column label="继任修订" width="180">
              <template #default="{ row }">
                <span v-if="row.successor_doc_code" class="mono">{{ row.successor_doc_code }} rev{{ row.successor_revision }}</span>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column prop="note" label="说明" min-width="260" />
            <el-table-column prop="registered_by" label="登记人" width="100" />
          </el-table>
          <el-alert type="info" :closable="false" class="mt">
            撤销只改变后续规则版本：已发布资产内容保留并标注「来源已撤销」，后续版本经版本链取代；
            已撤销修订禁止再解析产生新版本。
          </el-alert>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 补齐对话框 -->
    <el-dialog v-model="suppDialog" title="待补条目补齐（原句保留，补齐文本作为解析依据）" width="560px">
      <el-alert type="warning" :closable="false" class="mb">
        <div>原句：{{ current?.original_text }}</div>
        <div>待补原因：{{ pendingReasonText(current?.pending_reason) }}</div>
      </el-alert>
      <el-input v-model="suppText" type="textarea" :rows="3" placeholder="补齐后的受控文本，如：安装孔距为 150 mm。" />
      <el-input v-model="suppNote" placeholder="补齐依据说明" class="mt" />
      <template #footer>
        <el-button @click="suppDialog = false">取消</el-button>
        <el-button type="primary" @click="doSupplement">提交补齐</el-button>
      </template>
    </el-dialog>

    <!-- 撤销对话框 -->
    <el-dialog v-model="withdrawDialog" title="登记修订撤销通知" width="520px">
      <el-alert type="error" :closable="false" class="mb">
        撤销 {{ current?.doc_code }} rev{{ current?.revision }}：已发布资产内容保留并标注来源撤销，
        后续规则版本须源自继任修订；该修订禁止再解析。
      </el-alert>
      <el-select v-model="successorId" placeholder="继任修订文档（可选）" clearable style="width: 100%">
        <el-option v-for="d in documents.filter(x => x.doc_code === current?.doc_code && x.id !== current?.id)"
                   :key="d.id" :value="d.id" :label="`${d.doc_code} rev${d.revision}`" />
      </el-select>
      <el-input v-model="withdrawNote" type="textarea" :rows="3" class="mt" placeholder="撤销原因" />
      <template #footer>
        <el-button @click="withdrawDialog = false">取消</el-button>
        <el-button type="danger" @click="doWithdraw">确认撤销</el-button>
      </template>
    </el-dialog>

    <!-- 解析报告抽屉 -->
    <el-drawer v-model="reportDrawer" size="46%" title="解析报告（受控语法抽取留痕）">
      <pre class="json">{{ pretty(current?.parse_report) }}</pre>
    </el-drawer>

    <!-- 决策表行抽屉 -->
    <el-drawer v-model="tableDrawer" size="46%" title="决策表行（例外优先 / 默认兜底 / 支持继承）">
      <pre class="json">{{ pretty(current?.rows_spec) }}</pre>
    </el-drawer>

    <!-- 新建决策表对话框 -->
    <el-dialog v-model="tableDialog" title="新建受控决策表" width="640px">
      <el-form label-width="90px" size="small">
        <el-form-item label="表号"><el-input v-model="nt.code" placeholder="DT-XXX" /></el-form-item>
        <el-form-item label="版本"><el-input v-model="nt.version" placeholder="v1" /></el-form-item>
        <el-form-item label="来源条目">
          <el-select v-model="nt.statementId" style="width: 100%">
            <el-option v-for="s in statements" :key="s.id" :value="s.id"
                       :label="`${s.statement_code}｜${s.original_text.slice(0, 40)}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="继承父表"><el-input v-model="nt.inherits" placeholder="可选：父表表号" /></el-form-item>
        <el-form-item label="表行 JSON">
          <el-input v-model="nt.rows" type="textarea" :rows="10" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tableDialog = false">取消</el-button>
        <el-button type="primary" @click="createTable">建模登记</el-button>
      </template>
    </el-dialog>

    <!-- 资产详情抽屉（回链） -->
    <el-drawer v-model="assetDrawer" size="58%" title="规则资产详情与回链">
      <div v-if="assetDetail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="资产">{{ assetDetail.asset_code }} {{ assetDetail.asset_version }}</el-descriptions-item>
          <el-descriptions-item label="工作流">{{ workflowText(assetDetail.workflow) }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="assetStatusType(assetDetail.status)">{{ assetStatusText(assetDetail.status) }}</el-tag>
            <el-tag v-if="assetDetail.source_withdrawn" size="small" type="danger" effect="plain">来源已撤销</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="统一验证">{{ assetDetail.validation_result || '—' }}</el-descriptions-item>
          <el-descriptions-item label="原文位置" :span="2">
            <span class="mono">{{ assetDetail.statement?.statement_code }}｜{{ assetDetail.statement?.source_locator }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="原句" :span="2">{{ assetDetail.statement?.original_text }}</el-descriptions-item>
          <el-descriptions-item label="来源文档" :span="2">
            <span class="mono">{{ assetDetail.document?.doc_code }} rev{{ assetDetail.document?.revision }}｜
              {{ assetDetail.document?.source_ref }}｜{{ assetDetail.document?.payload_digest }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="适用范围" :span="2">
            <span class="mono small">{{ JSON.stringify(assetDetail.scope_spec) }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <h4>编译谓词（按优先级逐行匹配，默认行兜底拒绝）</h4>
        <pre class="json">{{ pretty(assetDetail.compiled_predicate) }}</pre>
        <h4>边界测试（{{ assetDetail.boundary_tests?.length || 0 }} 条）</h4>
        <pre class="json">{{ pretty(assetDetail.boundary_tests) }}</pre>
        <h4>验证运行（统一反例集 + 边界测试）</h4>
        <el-table :data="assetDetail.validationRuns" size="small" border>
          <el-table-column prop="validator_version" label="验证器" width="110" class-name="mono" />
          <el-table-column prop="total_cases" label="用例" width="60" />
          <el-table-column prop="passed_cases" label="通过" width="60" />
          <el-table-column label="结果" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="row.result === 'PASS' ? 'success' : 'danger'">{{ row.result }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="created_at" label="时间" width="160" />
        </el-table>
        <h4>版本链（撤销只改变后续规则版本）</h4>
        <el-table :data="assetDetail.versionChain" size="small" border>
          <el-table-column prop="asset_version" label="版本" width="70" />
          <el-table-column label="状态" width="130">
            <template #default="{ row }">
              <el-tag size="small" :type="assetStatusType(row.status)">{{ assetStatusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="来源撤销" width="90">
            <template #default="{ row }">{{ row.source_withdrawn ? '是' : '—' }}</template>
          </el-table-column>
          <el-table-column prop="published_at" label="发布时间" width="160" />
        </el-table>
      </div>
    </el-drawer>

    <!-- 试算对话框 -->
    <el-dialog v-model="evalDialog" title="资产试算（全谓词模式）" width="560px">
      <el-input v-model="evalInput" type="textarea" :rows="6"
                placeholder='{"attrs":{"仓体规格":{"value":"3U"}},"context":{"batch":"B-77"}}' />
      <div v-if="evalResult" class="mt">
        <el-tag :type="evalResult.verdict === 'ACCEPT' ? 'success' : 'danger'" effect="dark" size="large">
          {{ evalResult.verdict }}</el-tag>
      </div>
      <template #footer>
        <el-button @click="evalDialog = false">关闭</el-button>
        <el-button type="primary" @click="doEval">试算</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'
import {
  statementStatusText, statementStatusType, pendingReasonText,
  assetStatusText, assetStatusType, workflowText, aspectText, docStatusText, docStatusType
} from '../utils/status'

const auth = useAuthStore()
const tab = ref('docs')
const documents = ref([]); const statements = ref([]); const pending = ref([])
const assets = ref([]); const tables = ref([]); const counterexamples = ref([])
const runs = ref([]); const notices = ref([])
const current = ref(null)
const suppDialog = ref(false); const suppText = ref(''); const suppNote = ref('')
const withdrawDialog = ref(false); const withdrawNote = ref(''); const successorId = ref(null)
const reportDrawer = ref(false); const tableDrawer = ref(false); const tableDialog = ref(false)
const assetDrawer = ref(false); const assetDetail = ref(null)
const evalDialog = ref(false); const evalInput = ref(''); const evalResult = ref(null); const evalAsset = ref(null)
const nt = ref({ code: '', version: 'v1', statementId: null, inherits: '', rows: defaultRows() })

function defaultRows() {
  return JSON.stringify([
    { rowId: 'r1-exception', conditions: [{ attr: '仓体规格', op: 'EQ', value: '3U' }, { attr: 'batch', op: 'EQ', value: 'B-77', context: true }], effect: 'ACCEPT', exception: true, note: '例外条款' },
    { rowId: 'r2-main', conditions: [{ attr: '仓体规格', op: 'EQ', value: '2U' }], effect: 'ACCEPT', note: '主规则' },
    { rowId: 'r3-default', conditions: [], effect: 'REJECT', default: true, note: '兜底拒绝' }
  ], null, 2)
}
const pretty = (o) => JSON.stringify(o, null, 2)
const rowClass = ({ row }) => row.status === 'PENDING_SUPPLEMENT' ? 'pending-row' : ''

async function load() {
  const [d, s, p, a, t, c, r, n] = await Promise.all([
    http.get('/spec/documents'), http.get('/spec/statements'), http.get('/spec/pending'),
    http.get('/spec/assets'), http.get('/spec/decision-tables'), http.get('/spec/counterexamples'),
    http.get('/spec/validation-runs'), http.get('/spec/notices')
  ])
  documents.value = d.data; statements.value = s.data; pending.value = p.data
  assets.value = a.data; tables.value = t.data; counterexamples.value = c.data
  runs.value = r.data; notices.value = n.data
}
async function parse(row) {
  const r = await http.post(`/spec/statements/${row.id}/parse`)
  ElMessage.success(`解析完成：${statementStatusText(r.data.status)}`)
  load()
}
function openSupplement(row) {
  current.value = row; suppText.value = ''; suppNote.value = ''; suppDialog.value = true
}
async function doSupplement() {
  await http.post(`/spec/statements/${current.value.id}/supplement`,
    { supplementedText: suppText.value, note: suppNote.value })
  suppDialog.value = false
  ElMessage.success('已补齐并回到草稿，请重新解析')
  load()
}
async function compile(row) {
  await http.post(`/spec/statements/${row.id}/compile`)
  ElMessage.success('已编译为规则资产（待统一验证）')
  load()
}
function showReport(row) { current.value = row; reportDrawer.value = true }
async function validateAsset(row) {
  const r = await http.post(`/spec/assets/${row.id}/validate`)
  if (r.data.validation_result === 'PASS') ElMessage.success('统一反例集验证通过，资产已验证')
  else ElMessage.error('统一验证未通过：详见验证运行记录')
  load()
}
async function publish(row) {
  await http.post(`/spec/assets/${row.id}/publish`)
  ElMessage.success('资产已发布；若存在前序版本，已按版本链取代（内容保留）')
  load()
}
async function showAsset(row) {
  assetDetail.value = (await http.get(`/spec/assets/${row.id}`)).data
  assetDrawer.value = true
}
function openEval(row) {
  evalAsset.value = row; evalResult.value = null
  evalInput.value = JSON.stringify({ attrs: {}, context: {} }, null, 2)
  evalDialog.value = true
}
async function doEval() {
  try {
    const input = JSON.parse(evalInput.value)
    evalResult.value = (await http.post(`/spec/assets/${evalAsset.value.id}/evaluate`, input)).data
  } catch (e) {
    ElMessage.error('输入 JSON 非法: ' + e.message)
  }
}
function withdraw(row) {
  current.value = row; withdrawNote.value = ''; successorId.value = null; withdrawDialog.value = true
}
async function doWithdraw() {
  await http.post(`/spec/documents/${current.value.id}/withdraw`,
    { note: withdrawNote.value, successorDocumentId: successorId.value })
  withdrawDialog.value = false
  ElMessage.success('撤销通知已登记：后续规则版本须源自继任修订')
  load()
}
async function createTable() {
  let rows
  try { rows = JSON.parse(nt.value.rows) } catch (e) {
    ElMessage.error('表行 JSON 非法: ' + e.message); return
  }
  await http.post('/spec/decision-tables', {
    tableCode: nt.value.code, tableVersion: nt.value.version, statementId: nt.value.statementId,
    rows, inheritsTable: nt.value.inherits || null
  })
  tableDialog.value = false
  ElMessage.success('决策表已登记（草稿），可编译为谓词与边界测试')
  load()
}
async function compileTable(row) {
  await http.post(`/spec/decision-tables/${row.id}/compile`)
  ElMessage.success('决策表已编译为规则资产（待统一验证）')
  load()
}
function showTable(row) { current.value = row; tableDrawer.value = true }

onMounted(load)
</script>

<style scoped>
.hint { color: #64748b; font-size: 12px; margin-left: 10px; }
h4 { margin: 18px 0 8px; }
.bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.supp { color: #b45309; font-size: 12px; }
.reason { color: #b45309; font-size: 12px; }
.mono { font-family: ui-monospace, monospace; }
.small { font-size: 11px; }
.json {
  background: #0f172a; color: #e2e8f0; padding: 12px; border-radius: 6px;
  font-size: 12px; overflow: auto; max-height: 320px;
}
.mb { margin-bottom: 10px; }
.mt { margin-top: 10px; }
:deep(.pending-row) { background: #fffbeb; }
</style>
