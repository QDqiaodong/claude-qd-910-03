<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const props = defineProps({
  params: { type: Object, default: () => ({}) },
  tabKey: { type: String, default: '' }
})
const emit = defineEmits(['openTab'])

const board = ref({ vessels: [], clearances: [] })
const detail = ref(null)
const loading = ref(false)
const acting = ref(false)
const operator = ref(localStorage.getItem('launch-operator') || '')

const clearanceId = computed(() => Number(props.params?.clearanceId || 0))
const vessels = computed(() => board.value.vessels || [])
const clearances = computed(() => board.value.clearances || [])
const sheet = computed(() => detail.value?.clearance || null)
const snapshot = computed(() => detail.value?.snapshot || null)
const checks = computed(() => detail.value?.checks || [])

/** 当前有效的阻塞明细：取最近一次带校验结论的记录（生成 / 复核 / 签认 / 确认） */
const effectiveCheck = computed(() => {
  const list = checks.value.filter((c) => c.verdict)
  return list.length ? list[list.length - 1] : null
})
const effectiveBlockers = computed(() => effectiveCheck.value?.blockers || [])
const snapBlocks = computed(() => snapshot.value?.blocks || [])
const snapPunches = computed(() => snapshot.value?.punchItems || [])
const snapWarnings = computed(() => snapshot.value?.warnings || [])
const snapSummary = computed(() => snapshot.value?.summary || {})

function verdictColor(verdict) {
  return verdict === 'READY' ? 'var(--pass)' : 'var(--fail)'
}

function sheetStatusColor(status) {
  if (status === 'CONFIRMED') return 'var(--pass)'
  if (status === 'VOID') return 'var(--steel)'
  return 'var(--warn)'
}

function contractColor(status) {
  if (status === 'SETTLED') return 'var(--pass)'
  if (status === 'PERFORMING') return 'var(--slipway)'
  if (status === 'SIGNED') return 'var(--drydock)'
  return 'var(--steel)'
}

function ensureOperator() {
  operator.value = operator.value.trim()
  if (!operator.value) {
    ElMessage.warning('请先在右上角填写操作人')
    return false
  }
  localStorage.setItem('launch-operator', operator.value)
  return true
}

function openClearance(c) {
  emit('openTab', {
    path: '/launch',
    title: '放行单 ' + c.clearanceNo,
    sub: c.vesselName,
    params: { clearanceId: c.id }
  })
}

function openVessel(item) {
  emit('openTab', {
    path: '/vessel',
    title: item.vessel.vesselNo + ' ' + item.vessel.vesselName,
    sub: item.vessel.ownerName,
    params: { vesselId: item.vessel.id }
  })
}

/** 动作统一入口：进行中拦截重复点击，后端行级锁兜底 */
async function run(action) {
  if (acting.value) return
  acting.value = true
  try {
    await action()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    acting.value = false
  }
}

async function generate(item) {
  if (!ensureOperator()) return
  await run(async () => {
    const data = await api.launch.generate(item.vessel.id, operator.value)
    ElMessage.success(`放行单 ${data.clearance.clearanceNo} 已生成（${data.clearance.verdictName}）`)
    await loadBoard()
    openClearance(data.clearance)
  })
}

async function recheck() {
  if (!ensureOperator()) return
  await run(async () => {
    const data = await api.launch.recheck(sheet.value.id, operator.value)
    ElMessage.success(`复核完成：${data.clearance.latestVerdictName}（生成快照不变，已追加复核记录）`)
    await loadDetail()
  })
}

async function sign() {
  if (!ensureOperator()) return
  try {
    await ElMessageBox.confirm(
      '签认前会以最新数据复核全部放行条件，通过后记录签认人与时间；签认后若复核发现条件回退，签认将被自动撤销。',
      `质量签认 ${sheet.value.clearanceNo}`,
      { confirmButtonText: '确认签认', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  await run(async () => {
    await api.launch.sign(sheet.value.id, operator.value)
    ElMessage.success('放行单已签认')
    await loadDetail()
  })
}

async function confirm() {
  if (!ensureOperator()) return
  try {
    await ElMessageBox.confirm(
      `确认下水放行 ${sheet.value.vesselNo} ${sheet.value.vesselName}？确认后船舶置为「已下水」、占用船台同步释放，操作人与时间留痕，且不可撤销。`,
      '确认下水放行',
      { confirmButtonText: '确认放行', cancelButtonText: '再想想', type: 'warning' }
    )
  } catch {
    return
  }
  await run(async () => {
    const data = await api.launch.confirm(sheet.value.id, operator.value)
    ElMessage.success(`${data.clearance.vesselName} 已下水，船台 ${data.clearance.berthNo} 已释放`)
    await loadDetail()
  })
}

async function voidSheet() {
  if (!ensureOperator()) return
  let reason = ''
  try {
    const res = await ElMessageBox.prompt(
      '作废后原单与生成快照保留备查，已签确认随单失效；如需继续放行请使用「作废重开」。',
      `作废放行单 ${sheet.value.clearanceNo}`,
      {
        confirmButtonText: '确认作废',
        cancelButtonText: '取消',
        inputPlaceholder: '作废原因（必填）',
        inputValidator: (v) => (v && v.trim() ? true : '请填写作废原因')
      }
    )
    reason = res.value.trim()
  } catch {
    return
  }
  await run(async () => {
    await api.launch.void(sheet.value.id, operator.value, reason)
    ElMessage.success('放行单已作废，快照保留备查')
    await loadDetail()
  })
}

async function regenerate() {
  if (!ensureOperator()) return
  let reason = ''
  try {
    const res = await ElMessageBox.prompt(
      '将作废当前放行单并按最新数据重开新单：原签认失效、生成快照留痕、待确认事项以新单为准，新单回引本单号。',
      `作废重开 ${sheet.value.clearanceNo}`,
      {
        confirmButtonText: '作废并重开',
        cancelButtonText: '取消',
        inputPlaceholder: '作废原因（必填）',
        inputValidator: (v) => (v && v.trim() ? true : '请填写作废原因')
      }
    )
    reason = res.value.trim()
  } catch {
    return
  }
  await run(async () => {
    const data = await api.launch.regenerate(sheet.value.id, operator.value, reason)
    ElMessage.success(`已重开放行单 ${data.clearance.clearanceNo}，需重新签认`)
    await loadDetail()
    openClearance(data.clearance)
  })
}

async function loadBoard() {
  board.value = await api.launch.board()
}

async function loadDetail() {
  detail.value = clearanceId.value ? await api.launch.detail(clearanceId.value) : null
}

async function load() {
  loading.value = true
  try {
    if (clearanceId.value) {
      await loadDetail()
    } else {
      await loadBoard()
    }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

watch(clearanceId, () => {
  if (clearanceId.value) {
    loadDetail().catch((e) => ElMessage.error(e.message))
  } else {
    detail.value = null
    loadBoard().catch((e) => ElMessage.error(e.message))
  }
})

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title" v-if="!clearanceId">下水放行台</span>
      <span class="view-title" v-else>放行单 {{ sheet?.clearanceNo || clearanceId }}</span>
      <span class="view-tip" v-if="!clearanceId">在厂船舶放行条件一览；阻塞逐条列明，放行单生成后保留快照，整改可复核或作废重开</span>
      <span class="view-tip" v-else>生成快照不可改，复核 / 签认 / 确认 / 作废全程留痕</span>
      <div class="op-box">
        <el-input v-model="operator" size="small" placeholder="操作人（必填）" style="width: 130px" />
        <el-button size="small" @click="load">刷新</el-button>
      </div>
    </div>

    <!-- ============ 放行台：在厂船舶一览 ============ -->
    <template v-if="!clearanceId">
      <section class="panel">
        <div class="panel-title">
          在厂船舶放行一览
          <span class="view-tip">共 {{ vessels.length }} 条在厂船舶 · 同一船同时只允许一张待确认放行单</span>
        </div>
        <div class="lv-grid">
          <div v-for="item in vessels" :key="item.vessel.id" class="lv-card" :class="{ ready: item.ready }">
            <div class="lv-head">
              <b class="link" @click="openVessel(item)">{{ item.vessel.vesselNo }} {{ item.vessel.vesselName }}</b>
              <span class="tag-line" v-if="item.berth">
                <i class="dot" style="background: var(--slipway)"></i>{{ item.berth.berthNo }} {{ item.berth.berthName }}
              </span>
              <span class="tag-line" v-else><i class="dot" style="background: var(--steel)"></i>未在台</span>
            </div>
            <div class="lv-meta">
              <template v-if="item.contract">
                合同 {{ item.contract.contractNo }} ·
                <b :style="{ color: contractColor(item.contract.status) }">{{ item.contract.statusName }}</b>
              </template>
              <span v-else class="bad">未登记修造合同</span>
            </div>
            <div class="lv-chips">
              <span class="lv-chip" :class="{ bad: item.summary.erectedCount < item.summary.blockCount }">
                搭载 {{ item.summary.erectedCount }}/{{ item.summary.blockCount }}
              </span>
              <span class="lv-chip" :class="{ bad: item.summary.surveyValidCount < item.summary.blockCount }">
                交验有效 {{ item.summary.surveyValidCount }}/{{ item.summary.blockCount }}
              </span>
              <span class="lv-chip" :class="{ bad: item.summary.criticalOpenCount > 0 }">
                严重遗留 {{ item.summary.criticalOpenCount }}
              </span>
              <span class="lv-chip ok" v-if="item.ready">具备放行条件</span>
              <span class="lv-chip bad" v-else>阻塞 {{ item.blockers.length }} 项</span>
            </div>
            <div class="lv-blockers" v-if="!item.ready">
              <div v-for="(b, i) in item.blockers.slice(0, 3)" :key="i" class="lv-blocker">· {{ b.message }}</div>
              <div class="lv-blocker dim" v-if="item.blockers.length > 3">
                … 共 {{ item.blockers.length }} 条，生成放行单可查看全部并留痕
              </div>
            </div>
            <div class="lv-actions">
              <template v-if="item.pendingClearance">
                <span class="tag-line">
                  {{ item.pendingClearance.clearanceNo }} ·
                  {{ item.pendingClearance.latestVerdictName || item.pendingClearance.verdictName }}
                </span>
                <el-button size="small" type="primary" @click="openClearance(item.pendingClearance)">打开放行单</el-button>
              </template>
              <el-button v-else size="small" type="primary" plain :disabled="acting" @click="generate(item)">
                生成放行单
              </el-button>
              <el-button
                v-if="item.latestClearance && !item.pendingClearance"
                size="small" text type="primary"
                @click="openClearance(item.latestClearance)"
              >
                最近单 {{ item.latestClearance.clearanceNo }}（{{ item.latestClearance.statusName }}）
              </el-button>
            </div>
          </div>
          <div class="empty-tip" v-if="!vessels.length">当前没有在厂船舶</div>
        </div>
      </section>

      <section class="panel">
        <div class="panel-title">
          放行单台账
          <span class="view-tip">共 {{ clearances.length }} 张 · 作废单保留备查，确认记录可追溯</span>
        </div>
        <el-table :data="clearances" size="small" v-if="clearances.length">
          <el-table-column label="单号" width="160">
            <template #default="{ row }">
              <span class="link" @click="openClearance(row)">{{ row.clearanceNo }}</span>
            </template>
          </el-table-column>
          <el-table-column label="船舶" width="150">
            <template #default="{ row }">{{ row.vesselNo }} {{ row.vesselName }}</template>
          </el-table-column>
          <el-table-column prop="berthNo" label="船台" width="80">
            <template #default="{ row }">{{ row.berthNo || '-' }}</template>
          </el-table-column>
          <el-table-column label="生成结论" width="90">
            <template #default="{ row }">
              <b :style="{ color: verdictColor(row.verdict) }">{{ row.verdictName }}</b>
            </template>
          </el-table-column>
          <el-table-column label="最近复核" width="90">
            <template #default="{ row }">{{ row.latestVerdictName || '-' }}</template>
          </el-table-column>
          <el-table-column label="签认" width="100">
            <template #default="{ row }">{{ row.signedBy || '未签认' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <span class="tag-line" :style="{ background: sheetStatusColor(row.status), color: '#fff' }">
                {{ row.statusName }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="生成" width="200">
            <template #default="{ row }">{{ row.generatedBy }} · {{ row.generatedAt }}</template>
          </el-table-column>
          <el-table-column label="确认 / 作废">
            <template #default="{ row }">
              <span v-if="row.status === 'CONFIRMED'">{{ row.confirmedBy }} · {{ row.confirmedAt }}</span>
              <span v-else-if="row.status === 'VOID'">{{ row.voidedBy }} · {{ row.voidedAt }}</span>
              <span v-else>-</span>
            </template>
          </el-table-column>
        </el-table>
        <div class="empty-tip" v-else>还没有放行单，从上方在厂船舶生成</div>
      </section>
    </template>

    <!-- ============ 放行单详情 ============ -->
    <template v-if="clearanceId && sheet">
      <section class="panel">
        <div class="sheet-head">
          <div class="sheet-stamp" :class="'st-' + String(sheet.status).toLowerCase()">{{ sheet.statusName }}</div>
          <div class="sheet-title">
            <div class="st-no">
              {{ sheet.clearanceNo }}
              <span class="tag-line" :style="{ background: verdictColor(sheet.verdict), color: '#fff' }">
                生成结论：{{ sheet.verdictName }}
              </span>
              <span
                v-if="sheet.latestVerdict && sheet.latestVerdict !== sheet.verdict"
                class="tag-line"
                :style="{ background: verdictColor(sheet.latestVerdict), color: '#fff' }"
              >
                最近复核：{{ sheet.latestVerdictName }}
              </span>
            </div>
            <div class="st-sub">
              {{ sheet.vesselNo }} {{ sheet.vesselName }} ·
              {{ sheet.berthNo ? sheet.berthNo + ' ' + (sheet.berthName || '') : '未在台' }}
            </div>
          </div>
        </div>
        <div class="sheet-grid">
          <div>
            <span class="k">所属合同</span>
            <b>{{ sheet.contractNo || '（无）' }}<template v-if="sheet.contractNo"> · {{ sheet.contractStatusName }}</template></b>
          </div>
          <div><span class="k">生成</span><b>{{ sheet.generatedBy }} · {{ sheet.generatedAt }}</b></div>
          <div>
            <span class="k">质量签认</span>
            <b>{{ sheet.signedBy ? sheet.signedBy + ' · ' + sheet.signedAt : '未签认' }}</b>
          </div>
          <div v-if="sheet.status === 'CONFIRMED'">
            <span class="k">确认放行</span><b>{{ sheet.confirmedBy }} · {{ sheet.confirmedAt }}</b>
          </div>
          <template v-if="sheet.status === 'VOID'">
            <div><span class="k">作废</span><b>{{ sheet.voidedBy }} · {{ sheet.voidedAt }}</b></div>
            <div><span class="k">作废原因</span><b>{{ sheet.voidReason }}</b></div>
          </template>
          <div v-if="sheet.previousNo"><span class="k">前序单号</span><b>{{ sheet.previousNo }}（已作废）</b></div>
          <div v-if="detail?.supersededBy">
            <span class="k">重开单号</span>
            <b class="link" @click="openClearance(detail.supersededBy)">{{ detail.supersededBy.clearanceNo }}</b>
          </div>
        </div>
        <div class="sheet-actions" v-if="sheet.status === 'PENDING'">
          <el-button size="small" :disabled="acting" @click="recheck">复核（沿用整改）</el-button>
          <el-button size="small" type="primary" plain :disabled="acting || !!sheet.signedBy" @click="sign">
            {{ sheet.signedBy ? '已签认' : '质量签认' }}
          </el-button>
          <el-button size="small" type="primary" :disabled="acting || !sheet.signedBy" @click="confirm">
            确认下水放行
          </el-button>
          <el-button size="small" type="danger" plain :disabled="acting" @click="voidSheet">作废</el-button>
          <el-button size="small" type="danger" :disabled="acting" @click="regenerate">作废重开</el-button>
          <span class="view-tip" v-if="!sheet.signedBy">确认放行前需先由质量人员签认</span>
        </div>
        <div class="sheet-actions" v-else-if="sheet.status === 'VOID' && detail?.supersededBy">
          <el-button size="small" type="primary" plain @click="openClearance(detail.supersededBy)">
            打开重开单 {{ detail.supersededBy.clearanceNo }}
          </el-button>
        </div>
      </section>

      <section class="panel" v-if="effectiveCheck">
        <div class="panel-title">
          当前阻塞明细
          <span class="view-tip">
            来源：{{ effectiveCheck.checkTypeName }} · {{ effectiveCheck.operator }} · {{ effectiveCheck.createdAt }}
          </span>
          <span class="tag-line" :style="{ background: verdictColor(effectiveCheck.verdict), color: '#fff', marginLeft: 'auto' }">
            {{ effectiveCheck.verdictName }}
          </span>
        </div>
        <el-table :data="effectiveBlockers" size="small" v-if="effectiveBlockers.length">
          <el-table-column label="类型" width="110">
            <template #default="{ row }"><span class="tag-line blk">{{ row.typeName }}</span></template>
          </el-table-column>
          <el-table-column label="阻塞对象" width="180">
            <template #default="{ row }"><b>{{ row.refNo }}</b></template>
          </el-table-column>
          <el-table-column prop="message" label="说明" />
        </el-table>
        <div class="ready-tip" v-else>全部放行条件均已满足，可签认后确认下水</div>
        <div class="warn-list" v-if="snapWarnings.length">
          <div class="warn-title">生成时提示项（不阻塞放行，需跟踪）</div>
          <div v-for="(w, i) in snapWarnings" :key="i" class="warn-line">· {{ w.message }}</div>
        </div>
      </section>

      <section class="panel" v-if="snapshot">
        <div class="panel-title">
          生成时快照（不可变）
          <span class="view-tip">
            生成于 {{ sheet.generatedAt }} · 用于追溯当时为什么{{ sheet.verdict === 'READY' ? '允许' : '拒绝' }}下水
          </span>
        </div>
        <div class="snap-stats">
          <div class="mini-card"><div class="k">分段总数</div><div class="v">{{ snapSummary.blockCount }}</div></div>
          <div class="mini-card"><div class="k">完成搭载</div><div class="v">{{ snapSummary.erectedCount }}</div></div>
          <div class="mini-card"><div class="k">交验有效</div><div class="v">{{ snapSummary.surveyValidCount }}</div></div>
          <div class="mini-card">
            <div class="k">严重遗留未闭环</div>
            <div class="v" :class="{ bad: snapSummary.criticalOpenCount > 0 }">{{ snapSummary.criticalOpenCount }}</div>
          </div>
          <div class="mini-card"><div class="k">全部未闭环遗留</div><div class="v">{{ snapSummary.punchOpenCount }}</div></div>
        </div>
        <div class="panel-sub">分段搭载与交验（{{ snapBlocks.length }}）</div>
        <el-table :data="snapBlocks" size="small" v-if="snapBlocks.length">
          <el-table-column prop="blockCode" label="分段编码" width="160" />
          <el-table-column prop="blockName" label="名称" width="110" />
          <el-table-column prop="blockTypeName" label="类型" width="90" />
          <el-table-column label="阶段（生成时）" width="110">
            <template #default="{ row }"><span :class="{ bad: !row.erected }">{{ row.statusName }}</span></template>
          </el-table-column>
          <el-table-column label="有效结论（生成时）">
            <template #default="{ row }">
              <span :class="{ bad: !row.surveyValid }">
                {{ row.latestSurveyResultName }}<template v-if="row.latestSurveyNo"> · {{ row.latestSurveyNo }}</template>
              </span>
            </template>
          </el-table-column>
        </el-table>
        <div class="empty-tip" v-else>生成时该船没有分段档案</div>
        <template v-if="snapPunches.length">
          <div class="panel-sub">遗留项（{{ snapPunches.length }}）</div>
          <el-table :data="snapPunches" size="small">
            <el-table-column prop="punchNo" label="编号" width="170" />
            <el-table-column prop="title" label="标题" />
            <el-table-column prop="blockCode" label="分段" width="150" />
            <el-table-column prop="severityName" label="严重度" width="80" />
            <el-table-column label="状态（生成时）" width="110">
              <template #default="{ row }"><span :class="{ bad: !row.closed }">{{ row.statusName }}</span></template>
            </el-table-column>
            <el-table-column prop="owner" label="责任人" width="90" />
          </el-table>
        </template>
      </section>

      <section class="panel">
        <div class="panel-title">
          复核与操作记录
          <span class="view-tip">{{ checks.length }} 条 · 生成 / 复核 / 签认 / 确认 / 作废全程留痕</span>
        </div>
        <div class="ck-list">
          <div v-for="c in checks" :key="c.id" class="ck-item">
            <div class="ck-top">
              <span class="tag-line ck-type">{{ c.checkTypeName }}</span>
              <span
                v-if="c.verdict"
                class="tag-line"
                :style="{ background: verdictColor(c.verdict), color: '#fff' }"
              >{{ c.verdictName }}</span>
              <b>{{ c.operator }}</b>
              <span class="ck-time">{{ c.createdAt }}</span>
            </div>
            <div class="ck-note">{{ c.note }}</div>
            <div class="ck-blockers" v-if="c.blockers && c.blockers.length">
              <div v-for="(b, i) in c.blockers" :key="i" class="lv-blocker">· {{ b.message }}</div>
            </div>
          </div>
          <div class="empty-tip" v-if="!checks.length">暂无记录</div>
        </div>
      </section>
    </template>
    <div class="empty-tip" v-if="clearanceId && !sheet && !loading">放行单不存在或已删除</div>
  </div>
</template>

<style scoped>
.op-box { margin-left: auto; display: flex; gap: 8px; align-items: center; }

.link { color: var(--primary-deep); font-weight: 700; text-decoration: underline dotted; cursor: pointer; }
.bad { color: var(--fail); }
.dim { color: var(--ink-dim); }

/* ---- 放行台船舶卡 ---- */
.lv-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(330px, 1fr));
  gap: 12px;
}
.lv-card {
  border: 1px solid var(--line-strong);
  border-left: 4px solid var(--fail);
  border-radius: 10px;
  padding: 10px 12px;
  background: #fff;
}
.lv-card.ready { border-left-color: var(--pass); }
.lv-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.lv-meta { font-size: 11.5px; color: var(--ink-soft); margin-top: 5px; }
.lv-chips { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 7px; }
.lv-chip {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--primary-wash);
  color: var(--ink-soft);
  border: 1px solid var(--line);
}
.lv-chip.bad { background: #fdecea; color: var(--fail); border-color: #f5c6c6; }
.lv-chip.ok { background: #e8f5e9; color: var(--pass); border-color: #c8e6c9; }
.lv-blockers { margin-top: 7px; }
.lv-blocker { font-size: 11.5px; color: var(--fail); line-height: 1.6; }
.lv-blocker.dim { color: var(--ink-dim); }
.lv-actions { display: flex; align-items: center; gap: 8px; margin-top: 9px; flex-wrap: wrap; }

/* ---- 放行单头 ---- */
.sheet-head { display: flex; align-items: center; gap: 14px; }
.sheet-stamp {
  width: 74px; height: 74px;
  border: 3px double currentColor;
  border-radius: 50%;
  display: grid; place-items: center;
  font-size: 15px; font-weight: 900;
  transform: rotate(-10deg);
  flex: 0 0 auto;
}
.sheet-stamp.st-pending { color: var(--warn); }
.sheet-stamp.st-confirmed { color: var(--pass); }
.sheet-stamp.st-void { color: var(--steel); }
.st-no { font-size: 16px; font-weight: 800; color: var(--primary-deep); display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.st-sub { font-size: 12px; color: var(--ink-soft); margin-top: 4px; }

.sheet-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 8px 16px;
  margin-top: 12px;
}
.sheet-grid .k { font-size: 11px; color: var(--ink-dim); display: block; }
.sheet-grid b { font-size: 12.5px; }

.sheet-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--line-strong);
}

.tag-line.blk { background: #fdecea; color: var(--fail); }
.tag-line.ck-type { background: var(--primary-wash); color: var(--primary-deep); font-weight: 700; }

.ready-tip {
  padding: 14px;
  text-align: center;
  color: var(--pass);
  font-weight: 700;
  background: #e8f5e9;
  border-radius: 8px;
}
.warn-list { margin-top: 10px; border-top: 1px dashed var(--line); padding-top: 8px; }
.warn-title { font-size: 11.5px; font-weight: 700; color: var(--warn); margin-bottom: 4px; }
.warn-line { font-size: 11.5px; color: var(--ink-soft); line-height: 1.7; }

.snap-stats {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(130px, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}
.panel-sub { font-size: 12.5px; font-weight: 700; color: var(--ink); margin: 10px 0 8px; }

.ck-list { display: flex; flex-direction: column; gap: 8px; }
.ck-item {
  border: 1px solid var(--line);
  border-left: 3px solid var(--primary-soft);
  border-radius: 8px;
  padding: 8px 10px;
  background: #fbfcfc;
}
.ck-top { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ck-time { margin-left: auto; font-size: 11px; color: var(--ink-dim); }
.ck-note { font-size: 12px; color: var(--ink-soft); margin-top: 4px; }
.ck-blockers { margin-top: 5px; }
</style>
