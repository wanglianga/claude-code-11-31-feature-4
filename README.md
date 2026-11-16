# 维修配置与备件兼容主数据血缘核验平台

面向设备制造企业的**配置主数据 / 血缘 / 兼容性事实 / 质量事件 / 版本化查询**平台。
解决"系统直接覆盖最新版 BOM，导致同一序列号在不同报表被解释成两种配置"的问题：
平台采用**版本化快照 + 追加式实装事件**，长期保留来源文件、版本索引与重放计算，
查询必须显式选择时点，已被引用的历史报告不会被静默改写。

本迭代新增**工程资料规则资产化**：尺寸、接口和替代条件不再以模糊文字直接流入主数据，
而是先审查为含**原文位置、对象、单位、边界、适用范围、前置证明和反例**的规则资产；
缺单位、端点不明或来源冲突的文本只进入待补队列；无法编译的陈述不得宽松发布。

技术栈：Vue 3 + Vite + Element Plus + Pinia ｜ Java 25 + Spring Boot 3.5 + Spring JDBC ｜ PostgreSQL 18。

---

## 一、平台能力与边界

### 交付的能力（本平台职责）
| 模块 | 职责 |
| --- | --- |
| 资料接入契约库 | 登记图号、供应商规则、实装凭证、检验数据的字段、来源、版本号、生效边界与来源可信度，并保留来源文件指纹与版本索引 |
| 实体消歧器 | 部件型号 / 实物序列号 / 装配位置 / 设备序列号 / 供应商编码**五类分开建模**；名称相似只产生消歧候选，**绝不自动合并** |
| 配置快照编译器 | 按指定维修时点重放出厂记录 + 追加式拆装凭证，汇集来源可信度，生成**位置级不可变快照**与重放指纹 |
| 规则血缘解析器 | 把替代方向、联换条件、协议/尺寸/电源约束连回图号修订、测试报告与生效版本 |
| 兼容事实求值器 | 对给定快照输出 **通过 / 不通过 / 证据不足** 及**最小证据链** |
| 影响分析器 | 规则、图号、实装记录变更后定位受影响快照、历史结论与下游数据集 |
| 发布闸门 | 草稿 / 已验证 / 待复核 / 已废止分通道对外，冲突未裁定或非 PASS 结论禁止发布 |
| **工程资料规则资产化（新）** | 资料原文登记（文档+修订）→ 受控语法解析 / 受控决策表两种工作流 → 编译谓词+边界测试 → **同一反例集统一验证** → 发布闸门 → 修订撤销只改变后续规则版本 |

### 规则资产化的两条工作流（同一产物、同一验证）
1. **受控语法解析**：从规则原文批量抽取比较符、量纲和逻辑条件，最大限度保留原句；
   面对省略（同上/参见）或嵌套例外（除非/特批）会**拒绝**更多材料。
2. **受控决策表**：工程师把原文拆进决策表（例外行优先、默认行兜底拒绝、支持表继承），
   再编译为谓词和边界测试；能准确表达例外和继承，代价是人工建模与表格版本维护。

两种产物（`rule_asset`）结构相同，都用**同一反例集 + 各自边界测试**验证，
全部通过才允许发布；修订撤销只改变后续规则版本，已发布版本内容保留可回放。

### 明确不做的事（外部业务系统职责）
不生成**领料单、维修工序、库存调拨、采购决策、工位排程**。平台只产出可回放的事实与证据。

### 关键业务纪律（已在代码中固化）
1. **序列号区间重叠**：两张图号都声称对同一序列号生效时，编译器产生冲突事实并冻结快照、阻止兼容发布，**不默认采用最新上传文件**。
2. **同一实物重叠时间出现在两台设备**：实体消歧器冻结相关实物/位置并要求凭证裁定，**不按接收顺序覆盖**。
3. **供应商收回替代规则**：影响分析器仅让引用该规则的快照/事实进入待复核；**已签发结论保留当时锁定版本，另发更正事实**，历史报告不改写。
4. **原文单位纪律（新）**：原文同时存在毫米和英寸而数值无单位时**必须停止**（全文档硬停止）；
   替代条件缺测试类型或有效期时**不可满足**；修订撤销只改变后续规则版本。

---

## 二、目录结构

```
31/
├── docker-compose.yml          # 一键编排：postgres + backend + frontend(nginx)
├── backend/                    # Spring Boot 工程
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── resources/
│       │   ├── application.yml
│       │   └── db/schema.sql      # 全部表结构（幂等，含约束迁移）
│       │       db/seed.sql        # 场景 A-E 基础主数据
│       │       db/seed_spec.sql   # 场景 F1-F4 原始资料（文档/条目/反例集/决策表）
│       └── java/com/mdm/platform/
│           ├── config/         # 初始化器、Jackson/PG 序列化
│           ├── security/       # 令牌认证、角色拦截
│           ├── repository/     # JdbcSupport
│           ├── service/        # 各模块核心逻辑
│           │   └── spec/       # 受控语法解析器 + 谓词求值引擎（纯函数，可单测）
│           └── web/            # REST 控制器
└── frontend/                   # Vue3 工程
    ├── Dockerfile
    ├── nginx.conf
    └── src/views/              # 11 个业务页面（含规则资产 SpecAssets.vue）
```

---

## 三、本地启动

### 方式 A：Docker Compose 一键启动（推荐，需本机有 Docker）

```bash
cd /workspace/31
docker compose up --build
```

- 前端：http://localhost:8080
- 后端 API：http://localhost:18080/api
- PostgreSQL：localhost:5432（库/用户/密码 `ccdev`）
- 后端首次启动自动建表、灌入种子数据，并按真实服务逻辑重放全部场景生成派生数据。

> 重置演示数据：`RESET_ON_START=true docker compose up backend`（或在库中 drop schema 后重启）。

### 方式 B：本地运行时开发启动（本仓库实际验证方式）

前置：JDK 25、Maven 3.9、Node 22、PostgreSQL 18（库 `ccdev`，账号/密码 `ccdev`）。

```bash
# 1. 后端（默认连 127.0.0.1:5432/ccdev，账号密码 ccdev）
cd backend
mvn -DskipTests package
DB_PASSWORD=ccdev java -jar target/mdm-platform.jar
# 服务监听 http://127.0.0.1:8080/api ，启动即自动建表+种子+场景重放

# 2. 前端（新终端）
cd frontend
npm install --registry=https://registry.npmmirror.com
npm run dev
# 打开 http://127.0.0.1:5173 ，vite 已配置 /api 代理到 8080
```

生产模式前端：`npm run build` 后用任意静态服务器托管 `dist/`（容器内用 nginx，配置见 `frontend/nginx.conf`）。

---

## 四、测试账号

| 角色 | 账号 | 密码 | 权限 |
| --- | --- | --- | --- |
| 管理员 | `admin` | `admin123` | 全部读写、发布闸门、裁定 |
| 工程师 | `engineer` | `eng123` | 业务数据读写、编译、求值（无系统管理限制外的写权限） |
| 只读查询 | `viewer` | `view123` | 仅查询，写操作返回 403 |

密码以 `SHA-256("mdm-salt::" + 明文)` 存储于 `app_user.password_hash`。

---

## 五、样例场景与验证方式

样例叙事数据见 `backend/src/main/resources/db/seed.sql`（场景 A-E）与
`backend/src/main/resources/db/seed_spec.sql`（场景 F1-F4，仅原始资料）。
派生快照/事实/冲突/影响/质量事件/规则资产由 `DemoDataInitializer` 启动时调用
**真实服务逻辑**重放生成，因此每个结论都能在接口与页面回放。

### 场景 A-E（配置快照核验）
| 场景 | 数据抓手 | 预期可观察结果 |
| --- | --- | --- |
| **A 跨修订序列号重叠** | 图号 `DRW-MOTOR-100` revA(`SN-0001~0020`) 与 revB(`SN-0015~0030`) 重叠；设备 `EQ-CNC-2024-0018`(SN-0018) | 快照 `SNP-0018-*` `has_conflict=true/status=FROZEN`，产生 `DRAWING_RANGE_OVERLAP` 冲突事实；对其发布兼容事实被闸门拦截 |
| **B 迟到拆装凭证** | 事件 `EV-INST-003`：冷却泵安装事件时间 `2024-08-20`，接收时间 `2025-03-01` | 同一设备同一维修时点编译出 `SNP-0007-20241201-v1`（接收边界前，冷却位 VACANT）与 `v2`（纳入迟到凭证，冷却位已装）；旧快照保留不改写，并产生 `LATE_VOUCHER` 质量事件 |
| **C 同名异件** | 三个"伺服驱动单元" `PM-SDU-A1`(EtherCAT/380V)、`PM-SDU-B2`(CANopen/220V)、`PM-SDU-C3` | 实体消歧页有 PENDING 候选，仅能人工 `KEEP_DISTINCT`；系统无任何自动合并入口 |
| **D 规则撤回** | 规则 `SUB-RULE-001`（A1→C3）被撤回 | 已发布事实保留 `rule_version_locked=v1`，另生成 `supersedes` 更正事实；引用快照进入 REVIEW；产生 `RULE_RETIRE`/`FACT_CORRECTION` 事件；历史时点重评仍 PASS 且标注规则已撤回 |
| **E 两约束联动** | 电源规则 `SUB-RULE-002`(电压380V+尺寸2U) / `SUB-RULE-003`(候选 `PM-PWR-502` 实际尺寸3U) | `500→501` 判 **PASS**；`500→502` 因尺寸约束失败判 **FAIL**；证据链逐项列出两条约束的 expected/actual/passed |

附加：实物 `PP-PUMP-0001` 在重叠时间段分别安装于设备 0007/0008，触发 `PHYSICAL_PART_DOUBLE_MOUNT`，
实物与相关位置被冻结，需在"实体消歧"页做凭证裁定。

### 场景 F1-F4（工程资料规则资产化验收：状态 / 测试 / 回链）
| 场景 | 数据抓手 | 预期可观察结果（状态 / 测试 / 回链） |
| --- | --- | --- |
| **F1 单位缺失** | `ST-100A-02`「安装孔距 150。」、`ST-100A-04`「安装螺孔深度 12。」 | 缺单位条目进入**待补队列**（`UNIT_MISSING`）；`ST-100A-02` 工程师补齐为「安装孔距为 150 mm。」后重新解析→编译→验证→发布，原句保留、补齐文本单列；`ST-100A-04` 留在待补队列 |
| **F1b 混合单位硬停止** | `DOC-SPEC-MIX` 同含「120 mm」「2.5 inch」与无单位「连接孔距 85」 | 毫米与英寸混排且数值无单位 → **必须停止**：全文档 3 条 `REJECTED/MIXED_UNIT_SYSTEM`，登记 `SPEC_HARD_STOP` 质量事件（CRITICAL） |
| **F2 区间端点冲突** | `ST-200-01`「泵体轴径 50~55 mm」vs `ST-200B-01`「52~58 mm」（不同来源文档）；`ST-200B-02`「扬程 30~12 m」端点倒置 | 跨来源端点不一致 → 待补（`SOURCE_CONFLICT`，回链冲突对方条目）；端点倒置 → 待补（`ENDPOINT_CONFLICT`） |
| **F3 替代条件证明不足** | `ST-SUB-01`「PM-MOTOR-100A 可由 PM-MOTOR-100B 替代。」（缺测试类型与有效期）；`ST-SUB-02` 齐备（振动试验 IEC 60068-2-6 + 有效期 24 个月） | 缺证明 → **不可满足**，只进待补（`PROOF_MISSING`）；齐备者编译发布，反例 `CE-SUB-001~004` 在统一验证中逐项核对（缺测试类型/缺有效期/超期均 REJECT，齐备且期内 ACCEPT） |
| **F4 修订撤销** | `DOC-SPEC-SDU` revA 撤销，revB 继任 | 撤销通知登记（`NT-0001`）；v1 资产内容保留并标注 `source_withdrawn`，状态 `SUPERSEDED`；revB 条目解析出 **v2** 经版本链取代；已撤销修订再解析返回 409；登记 `SPEC_REVISION_WITHDRAWN` / `RULE_ASSET_SUPERSEDED` 事件 |
| **对照：两种工作流** | `ST-PWR-01`「…除非批次 B-77 特批 3U…」嵌套例外 | 语法解析器**拒绝**（`NESTED_EXCEPTION`）→ 工程师建模决策表 `DT-PWR-U`（例外行优先+默认行兜底）→ 同一反例集验证通过 → 发布；试算：`3U+B-77→ACCEPT`、`3U→REJECT`、`2U→ACCEPT`、`4U→REJECT` |

**统一反例集**：19 条共享用例（`counterexample` 表）同时验证语法解析产物与决策表产物；
每个资产另带编译期生成的**边界测试**（下界-ε/下界/上界/上界+ε）。验证运行全部留痕于
`asset_validation_run`，只有 `VALIDATED` 资产可发布（违规资产演示：E2E 现场构造放行 4U 的
决策表，统一验证判 FAIL，发布被 409 拦截——**不能把无法编译/验证不过的陈述宽松发布**）。

### 建议的页面核验路径
1. 用 `admin` 登录 → **核验总览**看统计与场景导航。
2. **规则资产**页签 1：资料文档与原文条目 —— 待补队列高亮，逐条看原文位置/原句/待补原因；
   对 `ST-MIX-*` 看硬停止原因；打开 `ST-100A-02` 解析报告看补齐历史。
3. **规则资产**页签 2：资产列表 —— 打开 `RA-0006 v2` 详情回链（条目→文档修订→验证运行→版本链），
   对决策表资产 `RA-0007` 点"试算"输入 `{"attrs":{"仓体规格":{"value":"3U"}},"context":{"batch":"B-77"}}` 得 ACCEPT。
4. **规则资产**页签 3/4/5：决策表行、统一反例集与验证运行留痕、修订撤销通知。
5. **快照与时点查询 / 兼容与发布闸门 / 影响分析 / 质量事件 / 实体消歧**：回放场景 A-E。

### 主要 REST 接口（均需 `Authorization: Bearer <token>`，登录接口除外）
```
POST /api/auth/login
GET  /api/contracts | /api/contracts/versions
GET  /api/entities?entityType=&keyword= | /api/entities/alias-candidates | /api/entities/conflicts
POST /api/entities/alias-candidates/{id}/decide | /api/entities/conflicts/{id}/adjudicate
GET  /api/drawings | /api/drawing-revisions | /api/inspections | /api/factory-records
GET  /api/install-events | POST /api/install-events
GET  /api/rules | /api/rules/{id}/lineage | POST /api/rules/{id}/retire
GET  /api/constraints
POST /api/snapshots/compile | GET /api/snapshots | GET /api/snapshots/{id}
GET  /api/equipment/{id}/as-of?pointInTime=
POST /api/compatibility/evaluate | GET /api/compatibility/facts-all
POST /api/facts/{id}/gate | GET /api/facts/public | GET /api/release/audit-logs
POST /api/impact/rule/{id} | /api/impact/drawing-revision/{id} | /api/impact/install-event/{id}
GET  /api/impact | GET /api/quality-events | POST /api/quality-events/{id}/close
# ---- 工程资料规则资产化（特性4） ----
GET  /api/spec/documents | /api/spec/documents/{id}
GET  /api/spec/statements?status= | GET /api/spec/pending
POST /api/spec/statements/{id}/parse | /api/spec/statements/{id}/supplement | /api/spec/statements/{id}/compile
GET  /api/spec/decision-tables | POST /api/spec/decision-tables | POST /api/spec/decision-tables/{id}/compile
GET  /api/spec/assets?status= | GET /api/spec/assets/{id}
POST /api/spec/assets/{id}/validate | /api/spec/assets/{id}/publish | /api/spec/assets/{id}/evaluate
GET  /api/spec/counterexamples | GET /api/spec/validation-runs
POST /api/spec/documents/{id}/withdraw | GET /api/spec/notices
```

---

## 六、版本化与不可篡改是怎么落地的

- `install_event` **只追加**：无 update/delete 接口，凭证号唯一；事件时间与接收时间分离，迟到凭证靠接收边界识别。
- `config_snapshot` / `snapshot_position` **只插入不更新**：每次编译产生新版本号，保存 `source_event_ids`、`source_factory_ids` 与 `replay_digest`（重放指纹）。
- 兼容性事实带 `rule_version_locked`；规则撤回后原事实不被改写，更正通过新事实 + `supersedes_fact_id` 串联。
- 所有来源记录保存 `source_ref` + `payload_digest`（文件指纹），契约版本索引长期保留。
- **规则资产（新）**：`spec_statement.original_text` 原句不改写，待补只追加 `supplemented_text`；
  `rule_asset` 按 `asset_code + asset_version` 版本化，修订撤销只标注 `source_withdrawn` 并
  经 `supersedes_asset_id` 版本链影响后续版本；验证运行与撤销通知全部留痕。

---

## 七、实际执行过的验证结论

以下为在本隔离环境**实际执行**并通过的验证（截至交付时）：

1. `mvn test` 通过：16 个单元测试（含受控语法解析器 11 个纯函数用例：区间/比较符/逻辑抽取、
   缺单位待补、端点倒置、嵌套例外与省略拒绝、替代条件缺证明不可满足、毫米+英寸混排硬停止、
   型号/标准号不误判为无单位数值）。
2. `mvn -DskipTests package` 成功，产出 `backend/target/mdm-platform.jar`（Spring Boot 3.5.16 / JDK 25）。
3. `npm run build` 成功，产出 `frontend/dist`（Vite + Vue3 生产包，含规则资产页）。
4. 后端连接本地 PostgreSQL 18 成功，自动建表（含质量事件类型约束迁移）、灌种子并重放全部场景：
   - 场景 A-E：5 个配置快照、6 条兼容事实、冲突冻结与闸门拦截、迟到凭证 v1/v2、更正链均正确；
   - 场景 F1-F4：第一轮解析 **待补 5 / 拒绝 4 / 解析成功 5**；补齐回路后 **7 个资产全部通过统一验证并发布**；
     混合单位文档 3 条硬停止；撤销通知后 v1 `SUPERSEDED+source_withdrawn`、v2 发布、
     已撤销修订再解析被 409 拦截；最终 15 条条目（待补 4 / 拒绝 3 / 发布 7 / 被取代 1）、
     8 个规则资产、8 次验证运行、13 条质量事件。
5. REST 冒烟通过：登录鉴权（错误密码 401）、VIEWER 写操作 403、版本化时点查询、规则血缘、
   证据链 jsonb 原生输出、`/api/spec/*` 全系列（待补队列/解析/补齐/编译/统一验证/发布/试算/撤销/通知）。
6. 端到端断言脚本 `backend/verify_e2e.py`（仅依赖 Python 标准库，对运行中的服务做 **44 项断言**，
   其中特性4 验收 23 项）全部通过，可重复执行：
   ```bash
   # 先启动后端，再运行
   python3 backend/verify_e2e.py
   ```
   脚本会现场构造一张"放行 4U"的违规决策表，验证统一反例集判其 FAIL 且发布被 409 拦截
   （幂等：重复执行时复用已有失败资产断言）。
7. 前端 `vite build` 编译无错误，11 个页面与路由、角色按钮禁用均已接线；
   `vite dev` 启动后 `/api` 代理与规则资产页接口联调通过。

**未在本环境执行的验证**：当前容器未安装 Docker，故 `docker compose up --build` 的容器化一键启动
**未在本环境实跑**；Dockerfile/compose 已按多阶段构建与官方基础镜像编写，需在具备 Docker 的环境执行确认。

---

## 八、配置项（application.yml / 环境变量）

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5432/ccdev` | 数据库连接 |
| `DB_USER` / `DB_PASSWORD` | `ccdev` / `ccdev` | 数据库账号 |
| `AUTO_INIT` | `true` | 启动自动建表+种子+场景重放 |
| `RESET_ON_START` | `false` | 置 `true` 启动时清空重建演示数据 |
