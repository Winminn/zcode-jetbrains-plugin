/**
 * 模型身份归一比较（review smell 收口：modelId+providerId 双字段等值比较此前在
 * useStore/TimelineSeparator 内联 10+ 处，且 providerID/providerId 大小写双形态
 * 散布在协议各端点，归一逻辑各自手写）。
 *
 * 供应商参与相等判定：同名模型跨供应商（如内置套餐 → 自定义渠道同名模型）是真实
 * 切换，不能按净零折叠（2026-09-14 ac88ae2 定案）。字段缺失按空串归一——两边都缺
 * 视为相同，单边缺失视为不同。
 */

/** 模型身份的宽容形态：store 的 {modelId, providerId}、服务端 timeline marker 的
 * {modelID, providerID}（大写 D，实测独立字段）都结构兼容 */
export interface ModelIdentity {
  modelId?: string | null
  modelID?: string | null
  providerId?: string | null
  providerID?: string | null
}

/** 同一模型判定：模型名一致（modelId ?? modelID）且供应商一致（providerId ?? providerID）*/
export function sameModel(a: ModelIdentity | null | undefined, b: ModelIdentity | null | undefined): boolean {
  if (!a || !b) return false
  const am = a.modelId ?? a.modelID
  const bm = b.modelId ?? b.modelID
  if (!am || !bm || am !== bm) return false
  return (a.providerId ?? a.providerID ?? '') === (b.providerId ?? b.providerID ?? '')
}
