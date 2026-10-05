<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const props = defineProps({
  params: { type: Object, default: () => ({}) },
  tabKey: { type: String, default: '' }
})
const emit = defineEmits(['openTab'])

const board = ref({ vessels: [], owners: [], contracts: [], typeMeta: [], types: [] })
const detail = ref(null)
const clearances = ref([])
const loading = ref(false)
const acting = ref(false)
const dialog = ref(false)
const operator = ref(localStorage.getItem('contract-operator') || '')

/** 付款结算弹窗：合同 + 节点（实时状态 / 阻塞 / 冻结证据）+ 只追加的操作记录 */
const settlement = ref(null)
const settlementLoading = ref(false)

const form = ref({
  vesselNo: '', regNo: '', vesselName: '', vesselType: 'BULK', ownerId: null,
  lengthM: 180, widthM: 30, depthM: 16, grossTonnage: 30000, displacementT: 70000,
  buildYear: 2026, note: ''
})

const vessels = computed(() => board.value.vessels || [])
const contracts = computed(() => board.value.contracts || [])
const vesselId = computed(() => Number(props.params?.vesselId || 0))

/** 付款结算弹窗开关与派生数据 */
const settlementVisible = computed({
  get: () => settlement.value !== null,
  set: (v) => {
    if (!v) settlement.value = null
  }
})
const settlementTitle = computed(() =>
  settlement.value ? `付款结算 · ${settlement.value.contract?.contractNo || ''}` : '付款结算')
const paidNodes = computed(() => (settlement.value?.nodes || []).filter((n) => n.status === 'PAID'))

function statusColor(status) {
  if (status === 'DELIVERED') return 'var(--pass)'
  if (status === 'LAUNCHED') return 'var(--slipway)'
  return 'var(--warn)'
}

function contractColor(status) {
  if (status === 'SETTLED') return 'var(--pass)'
  if (status === 'PERFORMING') return 'var(--slipway)'
  if (status === 'SIGNED') return 'var(--drydock)'
  return 'var(--steel)'
}

/** 付款结算状态色：全部已付 / 部分已付 / 待结算 / 未进入 */
function paymentColor(state) {
  if (state === 'PAID') return 'var(--pass)'
  if (state === 'PARTIAL') return 'var(--slipway)'
  if (state === 'PENDING') return 'var(--warn)'
  return 'var(--steel)'
}

function contractsOf(vesselId) {
  return contracts.value.filter((c) => c.vesselId === vesselId)
}

function ensureOperator() {
  operator.value = operator.value.trim()
  if (!operator.value) {
    ElMessage.warning('请先在右上角填写操作人（付款确认与状态推进都要留痕）')
    return false
  }
  localStorage.setItem('contract-operator', operator.value)
  return true
}

/** 结算动作统一入口：进行中拦截重复点击，后端行级锁兜底并发 / 重试 */
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

/** 一条船一条时间轴：建档 → 合同签订 → 分段开工 → 交船期 */
function timeline(vessel) {
  const nodes = []
  nodes.push({ label: '船舶建档', date: String(vessel.createdAt || '').slice(0, 10), done: true })
  const list = contractsOf(vessel.id)
  if (list.length) {
    const signed = list.filter((c) => c.signDate).sort((a, b) => String(a.signDate).localeCompare(String(b.signDate)))[0]
    nodes.push({ label: '合同签订 ' + signed.contractNo, date: signed.signDate, done: isPast(signed.signDate) })
  }
  if (vessel.status !== 'IN_YARD' || vessel.status === 'LAUNCHED') {
    nodes.push({ label: '下水出坞', date: '', done: vessel.status !== 'IN_YARD' })
  }
  const delivery = list.filter((c) => c.deliveryDate).sort((a, b) => String(a.deliveryDate).localeCompare(String(b.deliveryDate)))[0]
  if (delivery) {
    nodes.push({ label: '交船期', date: delivery.deliveryDate, done: vessel.status === 'DELIVERED' })
  }
  return nodes
}

function isPast(date) {
  if (!date) return false
  return String(date) <= new Date().toISOString().slice(0, 10)
}

/** 后端 ISO 时间 → 页面可读格式 */
function fmtTime(value) {
  return String(value || '').replace('T', ' ').slice(0, 19) || '-'
}

function openVessel(vessel) {
  emit('openTab', {
    path: '/vessel',
    title: vessel.vesselNo + ' ' + vessel.vesselName,
    sub: vessel.ownerName,
    params: { vesselId: vessel.id }
  })
}

function openBlock(code, id) {
  if (!id) return
  emit('openTab', { path: '/block', title: code, params: { blockId: id } })
}

function openClearance(row) {
  emit('openTab', {
    path: '/launch',
    title: '放行单 ' + row.clearanceNo,
    sub: row.vesselName,
    params: { clearanceId: row.id }
  })
}

function openLaunchBoard() {
  emit('openTab', { path: '/launch', title: '下水放行', params: {} })
}

async function advanceContract(contract) {
  if (contract.status === 'PERFORMING' && !ensureOperator()) return
  await run(async () => {
    const updated = await api.vessel.advanceContract(contract.id, operator.value || undefined)
    ElMessage.success(`${updated.contractNo} → ${updated.statusName}`)
    if (updated.status === 'PERFORMING') {
      ElMessage.success('已进入结算流程，付款节点按合同金额 / 报验结论 / 船舶状态完成首次判定')
    }
    await reload()
  })
}

/** 打开付款结算弹窗：节点、阻塞原因、冻结证据、操作记录都来自后端同一份计算 */
async function openSettlement(contract) {
  settlementLoading.value = true
  try {
    settlement.value = await api.vessel.contractSettlement(contract.id)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    settlementLoading.value = false
  }
}

async function refreshSettlement() {
  if (!settlement.value?.contract?.id) return
  await run(async () => {
    settlement.value = await api.vessel.contractSettlement(settlement.value.contract.id)
    await reload()
  })
}

/** 手工复核：报验补录 / 撤回 / 让步改判后也可主动按最新数据重算待结算节点 */
async function recheckSettlement() {
  if (!ensureOperator()) return
  await run(async () => {
    settlement.value = await api.vessel.recheckSettlement(settlement.value.contract.id, operator.value)
    ElMessage.success('已按最新合同金额 / 报验结论 / 船舶状态复核（已付款节点保持不变）')
    await reload()
  })
}

/** 确认付款：后端重新整链校验，通过才扣款更新；失败回滚并保留驳回痕迹 */
async function confirmPayment(node) {
  if (!ensureOperator()) return
  try {
    await ElMessageBox.confirm(
      `确认支付节点「${node.nodeName}」 ${node.amountDue} 万元（合同金额 × ${node.ratio}%）？` +
      '后端将重新核对合同金额、报验结论、船舶状态与前序节点，条件不满足会整体驳回、不扣款。',
      `确认付款 ${node.nodeNo}`,
      { confirmButtonText: '确认付款', cancelButtonText: '再想想', type: 'warning' }
    )
  } catch {
    return
  }
  await run(async () => {
    const data = await api.vessel.confirmPayment(node.id, operator.value)
    settlement.value = data
    ElMessage.success(`${node.nodeName} 已付款 ${node.amountDue} 万元，操作者与计算依据已留痕`)
    await reload()
  })
}

async function createVessel() {
  loading.value = true
  try {
    await api.vessel.create(form.value)
    ElMessage.success('船舶已建档')
    dialog.value = false
    await loadBoard()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function loadBoard() {
  board.value = await api.vessel.board()
}

async function loadDetail() {
  detail.value = vesselId.value ? await api.vessel.detail(vesselId.value) : null
}

async function loadClearances() {
  clearances.value = vesselId.value ? await api.launch.ofVessel(vesselId.value) : []
}

async function reload() {
  await Promise.all([loadBoard(), loadDetail(), loadClearances()])
}

async function load() {
  loading.value = true
  try {
    await reload()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

watch(vesselId, () => {
  if (vesselId.value) {
    loadDetail().catch((e) => ElMessage.error(e.message))
    loadClearances().catch((e) => ElMessage.error(e.message))
  } else {
    detail.value = null
    clearances.value = []
  }
})

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title" v-if="!vesselId">船舶与修造合同 · 时间轴</span>
      <span class="view-title" v-else>{{ detail?.vessel?.vesselNo }} {{ detail?.vessel?.vesselName }}</span>
      <span class="view-tip">付款节点按合同金额、报验结论、船舶状态由后端实时结算；条件不满足停留待结算并逐条列明阻塞</span>
      <el-input
        v-model="operator" size="small" placeholder="操作人（推进 / 付款留痕）"
        style="margin-left:auto;width:180px"
      />
      <el-button size="small" type="primary" @click="dialog = true">新增船舶</el-button>
    </div>

    <!-- 单船分页 -->
    <div v-if="vesselId && detail">
      <section class="panel">
        <div class="panel-title">
          {{ detail.vessel.vesselName }}
          <span class="tag-line"><i class="dot" :style="{ background: statusColor(detail.vessel.status) }"></i>{{ detail.vessel.statusName }}</span>
          <span class="view-tip">{{ detail.vessel.vesselTypeName }} · {{ detail.vessel.ownerName }}</span>
        </div>
        <div class="vs-grid">
          <div><span class="k">船号 / 登记号</span><b>{{ detail.vessel.vesselNo }} / {{ detail.vessel.regNo }}</b></div>
          <div><span class="k">主尺度</span><b>{{ detail.vessel.lengthM }}×{{ detail.vessel.widthM }}×{{ detail.vessel.depthM }} m</b></div>
          <div><span class="k">总吨</span><b>{{ detail.vessel.grossTonnage }}</b></div>
          <div><span class="k">排水量</span><b>{{ detail.vessel.displacementT }} t</b></div>
          <div><span class="k">建造年份</span><b>{{ detail.vessel.buildYear }}</b></div>
          <div><span class="k">分段数</span><b>{{ detail.blocks.length }}</b></div>
          <div>
            <span class="k">当前船台</span>
            <b v-if="detail.berth">{{ detail.berth.berthNo }} · {{ detail.berth.berthName }}</b>
            <b v-else>未上台（待排区）</b>
          </div>
        </div>
      </section>

      <div class="contract-cards">
        <div
          v-for="contract in detail.contracts" :key="contract.id"
          class="contract-card" :style="{ borderTopColor: contractColor(contract.status) }"
        >
          <div class="cc-head">
            <b>{{ contract.contractNo }}</b>
            <span class="tag-line" :style="{ background: contractColor(contract.status), color: '#fff' }">{{ contract.statusName }}</span>
          </div>
          <div class="cc-type">{{ contract.contractTypeName }} · {{ contract.ownerName }}</div>
          <div class="cc-amount">{{ contract.amount }} <span>万元</span></div>
          <!-- 付款结算联动：履行中 / 已结算合同显示同一后端口径的状态与阻塞 -->
          <div v-if="contract.settlementStarted" class="cc-settle">
            <div class="cc-settle-head">
              <span class="settle-dot" :style="{ background: paymentColor(contract.paymentState) }"></span>
              <b :style="{ color: paymentColor(contract.paymentState) }">{{ contract.paymentStateName }}</b>
              <span class="cc-line">已付 {{ contract.settledAmount }} 万 · 节点 {{ contract.paidNodeCount }}/{{ contract.totalNodeCount }}</span>
              <span class="link cc-more" @click="openSettlement(contract)">详情/付款 ›</span>
            </div>
            <div v-if="contract.paymentState !== 'PAID' && contract.blockers && contract.blockers.length" class="cc-blockers">
              <div v-for="(b, i) in contract.blockers" :key="i" class="cc-blocker">· {{ b.message }}</div>
            </div>
            <div v-if="contract.settlementWarnings && contract.settlementWarnings.length" class="cc-warn">
              <div v-for="(w, i) in contract.settlementWarnings" :key="i">· {{ w }}</div>
            </div>
          </div>
          <div class="cc-line">签订 {{ contract.signDate || '-' }} · 交船 {{ contract.deliveryDate || '-' }}</div>
          <div class="cc-line" v-if="contract.settleRef">结算依据：{{ contract.settleRef }}</div>
          <div class="cc-note">{{ contract.note }}</div>
          <div class="cc-actions">
            <el-button size="small" type="primary" plain :disabled="acting" @click="advanceContract(contract)">
              推进状态
            </el-button>
            <el-button
              v-if="contract.settlementStarted" size="small" type="primary"
              :disabled="acting || settlementLoading" @click="openSettlement(contract)"
            >
              付款节点{{ contract.totalNodeCount ? `（${contract.paidNodeCount}/${contract.totalNodeCount}）` : '' }}
            </el-button>
          </div>
        </div>
        <div class="empty-tip" v-if="!detail.contracts.length">这艘船还没有合同</div>
      </div>

      <section class="panel">
        <div class="panel-title">本船分段（{{ detail.blocks.length }}）</div>
        <el-table :data="detail.blocks" size="small" v-if="detail.blocks.length">
          <el-table-column label="编码" width="150">
            <template #default="{ row }">
              <span class="link" @click="openBlock(row.blockCode, row.id)">{{ row.blockCode }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="blockName" label="名称" />
          <el-table-column prop="blockTypeName" label="类型" width="100" />
          <el-table-column prop="weightT" label="重量(t)" width="90" />
          <el-table-column prop="segSeq" label="顺序" width="70" />
          <el-table-column prop="statusName" label="状态" width="90" />
        </el-table>
        <div class="empty-tip" v-else>还没有分段</div>
      </section>

      <section class="panel">
        <div class="panel-title">
          移泊记录
          <span class="view-tip">本船每一次移泊的原船台 → 目标船台 · 操作时间 · 结果，与船台总览同源</span>
        </div>
        <el-table :data="detail.moveLogs || []" size="small" v-if="(detail.moveLogs || []).length">
          <el-table-column label="原船台" width="100">
            <template #default="{ row }">{{ row.fromBerthNo || '待排区' }}</template>
          </el-table-column>
          <el-table-column prop="toBerthNo" label="目标船台" width="100" />
          <el-table-column label="操作时间" width="160">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="结果" width="80">
            <template #default="{ row }">{{ row.resultName }}</template>
          </el-table-column>
          <el-table-column prop="reason" label="说明" show-overflow-tooltip />
        </el-table>
        <div class="empty-tip" v-else>还没有移泊记录</div>
      </section>

      <section class="panel">
        <div class="panel-title">
          下水放行
          <span class="view-tip">放行单全程留痕；确认后船舶置为已下水、占用船台同步释放</span>
          <el-button size="small" type="primary" plain style="margin-left:auto" @click="openLaunchBoard">前往放行台</el-button>
        </div>
        <el-table :data="clearances" size="small" v-if="clearances.length">
          <el-table-column label="单号" width="160">
            <template #default="{ row }">
              <span class="link" @click="openClearance(row)">{{ row.clearanceNo }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">{{ row.statusName }}</template>
          </el-table-column>
          <el-table-column label="生成结论" width="90">
            <template #default="{ row }">{{ row.verdictName }}</template>
          </el-table-column>
          <el-table-column label="签认" width="110">
            <template #default="{ row }">{{ row.signedBy || '未签认' }}</template>
          </el-table-column>
          <el-table-column label="确认放行" width="200">
            <template #default="{ row }">
              <span v-if="row.status === 'CONFIRMED'">{{ row.confirmedBy }} · {{ row.confirmedAt }}</span>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="生成">
            <template #default="{ row }">{{ row.generatedBy }} · {{ row.generatedAt }}</template>
          </el-table-column>
        </el-table>
        <div class="empty-tip" v-else>还没有放行单，可前往放行台生成</div>
      </section>
    </div>

    <!-- 时间轴总览 -->
    <div v-else>
      <div class="grid-2">
        <section class="panel">
          <div class="panel-title">
            船舶时间轴
            <span class="view-tip">共 {{ vessels.length }} 条船 · {{ board.owners?.length || 0 }} 家船东</span>
          </div>
          <div class="tl-wrap">
            <div v-for="vessel in vessels" :key="vessel.id" class="vs-item" @click="openVessel(vessel)">
              <div class="vs-head">
                <span class="vs-no">{{ vessel.vesselNo }}</span>
                <b>{{ vessel.vesselName }}</b>
                <span class="tag-line"><i class="dot" :style="{ background: statusColor(vessel.status) }"></i>{{ vessel.statusName }}</span>
                <span class="vs-owner">{{ vessel.ownerName }}</span>
              </div>
              <div class="vs-meta">{{ vessel.vesselTypeName }} · {{ vessel.lengthM }}×{{ vessel.widthM }} m · {{ vessel.displacementT }} t · {{ vessel.regNo }}</div>
              <div class="vs-nodes">
                <span
                  v-for="(node, idx) in timeline(vessel)" :key="idx"
                  class="vs-node" :class="{ done: node.done }"
                >
                  {{ node.label }}<i v-if="node.date">· {{ node.date }}</i>
                </span>
              </div>
            </div>
            <div class="empty-tip" v-if="!vessels.length">还没有船舶</div>
          </div>
        </section>

        <section class="panel">
          <div class="panel-title">
            合同信息卡
            <span class="view-tip">草拟 → 已签 → 履行中（进入结算）→ 已结算（付款节点全部完成）</span>
          </div>
          <div class="contract-cards single">
            <div v-for="contract in contracts" :key="contract.id" class="contract-card" :style="{ borderTopColor: contractColor(contract.status) }">
              <div class="cc-head">
                <b>{{ contract.contractNo }}</b>
                <span class="tag-line" :style="{ background: contractColor(contract.status), color: '#fff' }">{{ contract.statusName }}</span>
              </div>
              <div class="cc-type">{{ contract.contractTypeName }} · {{ contract.vesselNo }} {{ contract.vesselName }}</div>
              <div class="cc-amount">{{ contract.amount }} <span>万元</span></div>
              <div v-if="contract.settlementStarted" class="cc-settle">
                <div class="cc-settle-head">
                  <span class="settle-dot" :style="{ background: paymentColor(contract.paymentState) }"></span>
                  <b :style="{ color: paymentColor(contract.paymentState) }">{{ contract.paymentStateName }}</b>
                  <span class="cc-line">已付 {{ contract.settledAmount }} 万 · {{ contract.paidNodeCount }}/{{ contract.totalNodeCount }}</span>
                  <span class="link cc-more" @click="openSettlement(contract)">详情/付款 ›</span>
                </div>
                <div v-if="contract.paymentState !== 'PAID' && contract.blockers && contract.blockers.length" class="cc-blockers">
                  <div v-for="(b, i) in contract.blockers" :key="i" class="cc-blocker">· {{ b.message }}</div>
                </div>
              </div>
              <div class="cc-line">签订 {{ contract.signDate || '-' }} · 交船 {{ contract.deliveryDate || '-' }}</div>
              <div class="cc-line" v-if="contract.settleRef">结算依据：{{ contract.settleRef }}</div>
              <div class="cc-actions">
                <el-button size="small" type="primary" plain :disabled="acting" @click="advanceContract(contract)">推进状态</el-button>
                <el-button
                  v-if="contract.settlementStarted" size="small" type="primary"
                  :disabled="acting || settlementLoading" @click="openSettlement(contract)"
                >
                  付款节点
                </el-button>
              </div>
            </div>
            <div class="empty-tip" v-if="!contracts.length">还没有合同</div>
          </div>
        </section>
      </div>

      <section class="panel">
        <div class="panel-title">按船舶类型统计（手写 JPQL）</div>
        <el-table :data="board.types" size="small">
          <el-table-column prop="vesselTypeName" label="船型" width="120" />
          <el-table-column prop="count" label="条数" width="80" />
          <el-table-column prop="avgLength" label="平均船长(m)" width="120" />
          <el-table-column prop="displacementSum" label="排水量合计(t)" />
        </el-table>
      </section>
    </div>

    <!-- 付款结算弹窗：节点状态 / 阻塞 / 冻结证据 / 操作记录，全部后端同源计算 -->
    <el-dialog
      v-model="settlementVisible" :title="settlementTitle" width="920px" top="6vh"
      @closed="settlement = null"
    >
      <div v-loading="settlementLoading">
        <template v-if="settlement">
          <div class="st-head">
            <div>
              <b>{{ settlement.contract.contractNo }}</b>
              <span class="tag-line" :style="{ background: contractColor(settlement.contract.status), color: '#fff', marginLeft: '8px' }">
                {{ settlement.contract.statusName }}
              </span>
              <span class="tag-line" :style="{ background: paymentColor(settlement.contract.paymentState), color: '#fff', marginLeft: '6px' }">
                {{ settlement.contract.paymentStateName }}
              </span>
            </div>
            <div class="cc-line">
              {{ settlement.contract.contractTypeName }} · {{ settlement.contract.vesselNo }} {{ settlement.contract.vesselName }}
              · 合同金额 {{ settlement.contract.amount }} 万 · 已付 {{ settlement.contract.settledAmount }} 万
            </div>
          </div>

          <div class="panel-sub">付款节点（应付 = 合同金额 × 比例；已付款节点冻结，任何刷新不倒改）</div>
          <el-table :data="settlement.nodes" size="small" border>
            <el-table-column label="#" prop="seq" width="42" />
            <el-table-column label="节点" min-width="100">
              <template #default="{ row }">
                <b>{{ row.nodeName }}</b>
                <div class="cc-line">{{ row.nodeNo }}</div>
              </template>
            </el-table-column>
            <el-table-column label="比例" width="56">
              <template #default="{ row }">{{ row.ratio }}%</template>
            </el-table-column>
            <el-table-column label="条件" min-width="210">
              <template #default="{ row }">
                <div class="cc-line">船舶≥{{ row.requiredVesselStatusName }}</div>
                <div class="cc-line">合格/让步报验≥{{ row.requiredAcceptSurveys }}
                  <template v-if="row.status === 'PENDING'">（当前 {{ row.currentAcceptCount }}）</template>
                </div>
                <div class="cc-line" v-if="row.status === 'PENDING'">
                  当前船舶：{{ row.currentVesselStatusName || '-' }}
                </div>
              </template>
            </el-table-column>
            <el-table-column label="应付/已付(万)" width="110">
              <template #default="{ row }">
                <b :class="{ paid: row.status === 'PAID' }">{{ row.amountDue }}</b>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <span class="tag-line" :style="{ background: row.status === 'PAID' ? 'var(--pass)' : 'var(--warn)', color: '#fff' }">
                  {{ row.statusName }}
                </span>
                <div class="cc-line" v-if="row.confirmedBy">{{ row.confirmedBy }}<br>{{ fmtTime(row.confirmedAt) }}</div>
                <div class="cc-line" v-else-if="row.verdict === 'READY'" style="color:var(--pass)">可付款</div>
              </template>
            </el-table-column>
            <el-table-column label="阻塞项" min-width="240">
              <template #default="{ row }">
                <div v-if="row.blockers && row.blockers.length">
                  <div v-for="(b, i) in row.blockers" :key="i" class="cc-blocker">· {{ b.message }}</div>
                </div>
                <div v-else-if="row.status === 'PENDING'" style="color:var(--pass)">条件已满足，可确认付款</div>
                <div v-else class="cc-line">依据 {{ row.settleRef }}，金额与证据已冻结</div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="row.status === 'PENDING'" size="small" type="primary"
                  :disabled="acting || row.verdict !== 'READY'"
                  @click="confirmPayment(row)"
                >确认付款</el-button>
                <span v-else class="cc-line">已结清</span>
              </template>
            </el-table-column>
          </el-table>

          <!-- 已付款节点的证据快照：解释当时使用的金额 / 报验结论 / 船舶状态 -->
          <div class="panel-sub">已付款节点的历史证据（当时金额 / 报验结论 / 船舶状态）</div>
          <div v-if="paidNodes.length" class="ev-list">
            <div v-for="row in paidNodes" :key="row.id" class="ev-item">
              <div class="ev-top">
                <b>{{ row.nodeName }}（{{ row.nodeNo }}）</b>
                <span class="cc-line">{{ row.confirmedBy }} · {{ fmtTime(row.confirmedAt) }}</span>
              </div>
              <div class="cc-line">
                付款 {{ row.amountPaid }} 万 = 冻结合同金额 {{ row.frozenContractAmount }} × {{ row.ratio }}%
                · 付款时船舶「{{ row.evidence?.vessel?.statusName }}」
                · 有效报验 {{ row.evidence?.acceptSurveyCount }} 条
                · 依据 {{ row.settleRef }}
              </div>
              <div class="cc-line" v-if="row.driftWarnings && row.driftWarnings.length" style="color:var(--warn)">
                <span v-for="(w, i) in row.driftWarnings" :key="i">{{ w }}<br></span>
              </div>
            </div>
          </div>
          <div class="empty-tip" v-else>暂无已付款节点</div>

          <div class="panel-sub">操作与联动记录（只追加，责任追溯）</div>
          <div class="log-list">
            <div v-for="log in settlement.logs" :key="log.id" class="log-item" :class="log.action.toLowerCase()">
              <div class="log-top">
                <span class="tag-line ck-type">{{ log.actionName }}</span>
                <span v-if="log.verdict" class="tag-line"
                  :style="{ background: log.verdict === 'READY' ? 'var(--pass)' : 'var(--fail)', color: '#fff' }">
                  {{ log.verdictName }}
                </span>
                <b v-if="log.nodeNo">{{ log.nodeNo }}</b>
                <span class="cc-line">{{ log.operator }}</span>
                <span class="cc-line" style="margin-left:auto">{{ fmtTime(log.createdAt) }}</span>
              </div>
              <div class="log-note">{{ log.note }}</div>
              <div v-if="log.sourceType && log.sourceType !== 'RECHECK'" class="cc-line">
                触发来源：{{ log.sourceTypeName }}<template v-if="log.sourceRef"> · {{ log.sourceRef }}</template>
              </div>
              <div v-if="log.blockers && log.blockers.length">
                <div v-for="(b, i) in log.blockers" :key="i" class="cc-blocker">· {{ b.message }}</div>
              </div>
            </div>
          </div>
        </template>
      </div>
      <template #footer>
        <el-button size="small" :disabled="acting || settlementLoading" @click="refreshSettlement">刷新</el-button>
        <el-button size="small" type="primary" plain :disabled="acting || settlementLoading" @click="recheckSettlement">
          按最新数据复核
        </el-button>
        <el-button size="small" @click="settlement = null">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dialog" title="新增船舶" width="560px">
      <el-form label-width="104px">
        <el-form-item label="本厂船号"><el-input v-model="form.vesselNo" placeholder="如 HN2609" /></el-form-item>
        <el-form-item label="船检登记号"><el-input v-model="form.regNo" placeholder="如 2026CS0288" /></el-form-item>
        <el-form-item label="船名"><el-input v-model="form.vesselName" /></el-form-item>
        <el-form-item label="船型">
          <el-select v-model="form.vesselType">
            <el-option v-for="t in board.typeMeta" :key="t.code" :label="t.name" :value="t.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="归属船东">
          <el-select v-model="form.ownerId" style="width:100%">
            <el-option v-for="o in board.owners" :key="o.id" :label="o.ownerNo + ' ' + o.ownerName" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="船长(m)"><el-input-number v-model="form.lengthM" :min="1" /></el-form-item>
        <el-form-item label="型宽(m)"><el-input-number v-model="form.widthM" :min="1" /></el-form-item>
        <el-form-item label="型深(m)"><el-input-number v-model="form.depthM" :min="1" /></el-form-item>
        <el-form-item label="总吨"><el-input-number v-model="form.grossTonnage" :min="1" /></el-form-item>
        <el-form-item label="排水量(t)"><el-input-number v-model="form.displacementT" :min="1" /></el-form-item>
        <el-form-item label="建造年份"><el-input-number v-model="form.buildYear" :min="1990" :max="2100" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="createVessel">建档</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tl-wrap { display: flex; flex-direction: column; gap: 10px; }
.vs-item {
  border: 1px solid var(--line-strong);
  border-radius: 10px;
  padding: 9px 11px;
  cursor: pointer;
  background: #fff;
  transition: transform .16s ease, box-shadow .16s ease;
}
.vs-item:hover { transform: translateY(-2px); box-shadow: 0 8px 18px rgba(38, 50, 56, 0.12); }
.vs-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.vs-no { font-weight: 800; color: var(--primary-deep); }
.vs-owner { margin-left: auto; font-size: 11px; color: var(--ink-dim); }
.vs-meta { font-size: 11px; color: var(--ink-soft); margin-top: 3px; }
.vs-nodes { display: flex; gap: 6px; margin-top: 7px; flex-wrap: wrap; }
.vs-node {
  font-size: 10.5px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--primary-wash);
  color: var(--ink-dim);
  border: 1px solid var(--line);
}
.vs-node.done { background: #e8f5e9; color: var(--pass); border-color: #c8e6c9; }
.vs-node i { font-style: normal; opacity: .8; }

.contract-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
  margin: 12px 0;
}
.contract-cards.single { margin-top: 0; }

.contract-card {
  border: 1px solid var(--line-strong);
  border-top: 4px solid var(--steel);
  border-radius: 10px;
  padding: 10px 12px;
  background: #fff;
}
.cc-head { display: flex; align-items: center; gap: 8px; }
.cc-head b { font-size: 13px; }
.cc-type { font-size: 11.5px; color: var(--ink-soft); margin-top: 4px; }
.cc-amount { font-size: 20px; font-weight: 800; color: var(--primary-deep); margin-top: 6px; }
.cc-amount span { font-size: 11px; font-weight: 400; color: var(--ink-dim); }
.cc-line { font-size: 11px; color: var(--ink-dim); margin-top: 3px; }
.cc-note { font-size: 11px; color: var(--ink-soft); margin-top: 4px; }
.cc-actions { margin-top: 8px; display: flex; gap: 6px; flex-wrap: wrap; }

/* 付款结算联动块 */
.cc-settle {
  margin-top: 6px;
  padding: 6px 8px;
  border-radius: 6px;
  background: var(--primary-wash);
  border: 1px dashed var(--line-strong);
}
.cc-settle-head { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; font-size: 11.5px; }
.cc-settle-head b { font-size: 11.5px; }
.settle-dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.cc-more { margin-left: auto; font-size: 11px; white-space: nowrap; }
.cc-blockers { margin-top: 4px; }
.cc-blocker { font-size: 11px; color: var(--fail); line-height: 1.55; }
.cc-warn { margin-top: 4px; }
.cc-warn div { font-size: 10.5px; color: var(--warn); line-height: 1.5; }

.st-head { margin-bottom: 8px; }
.panel-sub { font-size: 12.5px; font-weight: 700; color: var(--ink); margin: 12px 0 8px; }
.ev-list { display: flex; flex-direction: column; gap: 8px; }
.ev-item {
  border: 1px solid var(--line);
  border-left: 3px solid var(--pass);
  border-radius: 8px;
  padding: 7px 10px;
  background: #fafdfa;
}
.ev-top { display: flex; align-items: center; gap: 8px; }
.log-list { display: flex; flex-direction: column; gap: 7px; max-height: 280px; overflow-y: auto; }
.log-item {
  border: 1px solid var(--line);
  border-left: 3px solid var(--primary-soft);
  border-radius: 8px;
  padding: 7px 9px;
  background: #fbfcfc;
}
.log-item.confirm { border-left-color: var(--pass); }
.log-item.confirm_reject { border-left-color: var(--fail); background: #fff8f7; }
.log-item.recalc { border-left-color: var(--warn); }
.log-top { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; }
.log-note { font-size: 12px; color: var(--ink-soft); margin-top: 4px; line-height: 1.6; }
.tag-line.ck-type { background: var(--primary-wash); color: var(--primary-deep); font-weight: 700; }
b.paid { color: var(--pass); }

.vs-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 8px 14px; }
.vs-grid .k { font-size: 11px; color: var(--ink-dim); display: block; }
.vs-grid b { font-size: 13px; }

.link { color: var(--primary-deep); font-weight: 700; text-decoration: underline dotted; cursor: pointer; }
</style>
