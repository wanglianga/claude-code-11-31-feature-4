// 闸门/事实/快照状态展示映射
export function statusText(s) {
  return {
    DRAFT: '草稿', VERIFIED: '已验证', RETIRED: '已废止', REVIEW: '待复核', FROZEN: '冻结'
  }[s] || s
}
export function statusType(s) {
  return {
    DRAFT: 'info', VERIFIED: 'success', RETIRED: 'danger', REVIEW: 'warning', FROZEN: 'danger'
  }[s] || 'info'
}
export function verdictText(v) {
  return { PASS: '通过', FAIL: '不通过', INSUFFICIENT_EVIDENCE: '证据不足' }[v] || v
}
export function verdictType(v) {
  return { PASS: 'success', FAIL: 'danger', INSUFFICIENT_EVIDENCE: 'warning' }[v] || 'info'
}
export function entityTypeText(t) {
  return {
    PART_MODEL: '部件型号', PHYSICAL_PART: '实物序列号', MOUNT_POSITION: '装配位置',
    EQUIPMENT: '设备序列号', SUPPLIER: '供应商编码'
  }[t] || t
}

// ---- 特性4：资料条目 / 规则资产状态 ----
export function statementStatusText(s) {
  return {
    DRAFT: '草稿', PENDING_SUPPLEMENT: '待补队列', PARSED: '已解析', MODELED: '已建模',
    COMPILED: '已编译', VALIDATED: '已验证', PUBLISHED: '已发布',
    REJECTED: '已拒绝', SUPERSEDED: '被后续版本取代'
  }[s] || s
}
export function statementStatusType(s) {
  return {
    DRAFT: 'info', PENDING_SUPPLEMENT: 'warning', PARSED: '', MODELED: '',
    COMPILED: '', VALIDATED: 'success', PUBLISHED: 'success',
    REJECTED: 'danger', SUPERSEDED: 'info'
  }[s] || 'info'
}
export function pendingReasonText(r) {
  return {
    UNIT_MISSING: '缺单位', ENDPOINT_UNCLEAR: '端点不明', ENDPOINT_CONFLICT: '区间端点冲突',
    SOURCE_CONFLICT: '来源冲突', PROOF_MISSING: '前置证明不足',
    MIXED_UNIT_SYSTEM: '毫米/英寸混排硬停止', NESTED_EXCEPTION: '嵌套例外(解析器拒绝)',
    ELLIPSIS_UNSUPPORTED: '省略表述(解析器拒绝)', UNSUPPORTED_GRAMMAR: '受控语法外',
    SOURCE_WITHDRAWN: '来源修订已撤销', EMPTY_TEXT: '原文为空'
  }[r] || r
}
export function assetStatusText(s) {
  return {
    COMPILED: '已编译', VALIDATED: '已验证', PUBLISHED: '已发布',
    REJECTED: '已拒绝', SUPERSEDED: '被后续版本取代'
  }[s] || s
}
export function assetStatusType(s) {
  return {
    COMPILED: '', VALIDATED: 'success', PUBLISHED: 'success',
    REJECTED: 'danger', SUPERSEDED: 'info'
  }[s] || 'info'
}
export function workflowText(w) {
  return { SYNTAX_PARSE: '受控语法解析', DECISION_TABLE: '受控决策表' }[w] || w
}
export function aspectText(a) {
  return {
    DIMENSION: '尺寸', INTERFACE: '接口', POWER: '电源', PROTOCOL: '协议', SUBSTITUTION: '替代条件'
  }[a] || a
}
export function docStatusText(s) {
  return { VERIFIED: '在效', REV_WITHDRAWN: '修订已撤销' }[s] || s
}
export function docStatusType(s) {
  return { VERIFIED: 'success', REV_WITHDRAWN: 'danger' }[s] || 'info'
}
