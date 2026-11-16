<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="4" v-for="card in cards" :key="card.label">
        <el-card class="stat">
          <div class="stat-label">{{ card.label }}</div>
          <div class="stat-num" :style="{ color: card.color }">{{ card.value }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="page-card">
      <template #header><b>核验场景（A–E 配置快照核验 + F1–F4 规则资产化验收，均可在本平台回放）</b></template>
      <el-timeline>
        <el-timeline-item v-for="s in scenarios" :key="s.title" :type="s.type" :timestamp="s.tag">
          <el-card shadow="never">
            <div class="sc-title">{{ s.title }}</div>
            <div class="sc-desc">{{ s.desc }}</div>
            <el-button link type="primary" @click="$router.push(s.route)">前往核验 →</el-button>
          </el-card>
        </el-timeline-item>
      </el-timeline>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import http from '../api/http'

const stats = ref({ snapshots: 0, facts: 0, conflicts: 0, quality: 0 })
const cards = ref([])

const scenarios = [
  { title: 'A. 跨修订序列号区间重叠', tag: '冲突阻止发布', type: 'danger',
    desc: '设备 SN-0018 同时命中图号 DRW-MOTOR-100 的 revA/revB，编译器产生冲突事实并冻结快照，发布闸门拦截，不默认采用最新上传。',
    route: '/snapshots' },
  { title: 'B. 迟到拆装凭证', tag: '版本化重放', type: 'primary',
    desc: '冷却泵凭证事件时间 2024-08-20、2025-03-01 才接收。按接收边界重放出 v1（空位）/v2（已装）两个快照，旧结论不改写。',
    route: '/snapshots' },
  { title: 'C. 同名异件', tag: '禁止自动合并', type: 'warning',
    desc: '三个“伺服驱动单元”分属不同供应商与协议，系统只建消歧候选，由人工 KEEP_DISTINCT 永久分立。',
    route: '/entities' },
  { title: 'D. 供应商规则撤回', tag: '历史保留+更正链', type: 'info',
    desc: 'SUB-RULE-001 撤回后，已签发事实保留锁定规则版本，另发更正事实；引用快照进入待复核而非静默改写。',
    route: '/impact' },
  { title: 'E. 两条约束联动', tag: '最小证据链', type: 'success',
    desc: '电源 380V + 协议 EtherCAT（或 + 尺寸 2U）同时满足才 PASS；502 因尺寸 3U≠2U 判 FAIL，逐项约束进入证据链。',
    route: '/compatibility' },
  { title: 'F1. 单位缺失与混合单位硬停止', tag: '待补队列/硬停止', type: 'warning',
    desc: '数值缺单位的条目只进待补队列，补齐后重解析发布；毫米与英寸混排且数值无单位时全文档必须停止。',
    route: '/spec-assets' },
  { title: 'F2. 区间端点冲突', tag: '来源冲突待补', type: 'danger',
    desc: '跨来源文档对同一属性的区间端点不一致（50~55 vs 52~58）或端点倒置（30~12），只进待补队列等待裁定。',
    route: '/spec-assets' },
  { title: 'F3. 替代条件证明不足', tag: '不可满足', type: 'warning',
    desc: '缺测试类型或有效期的替代条件不可满足，不得编译发布；齐备条件经统一反例集验证后发布。',
    route: '/spec-assets' },
  { title: 'F4. 修订撤销只改变后续规则版本', tag: '版本链保留', type: 'info',
    desc: 'revA 撤销后 v1 资产内容保留并标注来源撤销，revB 编译出 v2 经版本链取代；已撤销修订禁止再解析。',
    route: '/spec-assets' }
]

onMounted(async () => {
  const [snaps, facts, conflicts, quality, specAssets, specPending] = await Promise.all([
    http.get('/snapshots'), http.get('/facts/public'),
    http.get('/entities/conflicts'), http.get('/quality-events'),
    http.get('/spec/assets'), http.get('/spec/pending')
  ])
  cards.value = [
    { label: '配置快照（版本化）', value: snaps.data.length, color: '#1d4ed8' },
    { label: '已对外发布兼容事实', value: facts.data.length, color: '#059669' },
    { label: '未裁定冲突事实', value: conflicts.data.filter(c => c.resolution_status === 'OPEN').length, color: '#dc2626' },
    { label: '质量事件', value: quality.data.length, color: '#d97706' },
    { label: '规则资产（已发布）', value: specAssets.data.filter(a => a.status === 'PUBLISHED').length, color: '#7c3aed' },
    { label: '待补队列条目', value: specPending.data.length, color: '#ea580c' }
  ]
})
</script>

<style scoped>
.stat { text-align: center; }
.stat-label { color: #64748b; font-size: 13px; }
.stat-num { font-size: 32px; font-weight: 700; margin-top: 6px; }
.sc-title { font-weight: 600; margin-bottom: 4px; }
.sc-desc { color: #475569; font-size: 13px; line-height: 1.6; margin-bottom: 6px; }
</style>
