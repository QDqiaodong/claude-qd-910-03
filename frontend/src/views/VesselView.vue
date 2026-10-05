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
const payment = ref(null)
const loading = ref(false)
const acting = ref(false)
const dialog = ref(false)
const operator = ref(localStorage.getItem('payment-operator') || '')

const form = ref({
  vesselNo: '', regNo: '', vesselName: '', vesselType: 'BULK', ownerId: null,
  lengthM: 180, widthM: 30, depthM: 16, grossTonnage: 30000, displacementT: 70000,
  buildYear: 2026, note: ''
})

const vessels = computed(() => board.value.vessels || [])
const contracts = computed(() => board.value.contracts || [])
const vesselId = computed(() => Number(props.params?.vesselId || 0))
const contractId = computed(() => Number(props.params?.contractId || 0))

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

function nodeColor(status) {
  if (status === 'SETTLED') return 'var(--pass)'
  if (status === 'DUE') return 'var(--slipway)'
  return 'var(--warn)'
}

function contractsOf(vesselId) {
  return contracts.value.filter((c) => c.vesselId === vesselId)
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

/** 付款记录里的报验结论快照 → 一行可读统计 */
function fmtSurvey(summary) {
  if (!summary) return '-'
  return `合格 ${summary.pass} · 让步 ${summary.concession} · 不合格 ${summary.fail}（共 ${summary.total} 单）`
}

function fmtBlockers(blockers) {
  if (!blockers || !blockers.length) return '-'
  return blockers.map((b) => b.message).join('；')
}

function ensureOperator() {
  operator.value = operator.value.trim()
  if (!operator.value) {
    ElMessage.warning('请先在右上角填写操作人')
    return false
  }
  localStorage.setItem('payment-operator', operator.value)
  return true
}

function openVessel(vessel) {
  emit('openTab', {
    path: '/vessel',
    title: vessel.vesselNo + ' ' + vessel.vesselName,
    sub: vessel.ownerName,
    params: { vesselId: vessel.id }
  })
}

function openContract(contract) {
  emit('openTab', {
    path: '/vessel',
    title: '合同 ' + contract.contractNo,
    sub: contract.vesselName,
    params: { contractId: contract.id }
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
  loading.value = true
  try {
    const updated = await api.vessel.advanceContract(contract.id)
    ElMessage.success(`${updated.contractNo} → ${updated.statusName}`)
    await reload()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

/** 确认结算一个付款节点：进行中拦截重复点击，后端行级锁兜底 */
async function confirmNode(node) {
  if (!ensureOperator()) return
  try {
    await ElMessageBox.confirm(
      `确认结算「${node.nodeName}」${node.amount} 万元？确认后节点置为已结算、金额记入合同已结算金额，操作者与时间留痕，且不可撤销。`,
      `确认结算 ${payment.value.contract.contractNo}`,
      { confirmButtonText: '确认结算', cancelButtonText: '再想想', type: 'warning' }
    )
  } catch {
    return
  }
  if (acting.value) return
  acting.value = true
  try {
    const data = await api.vessel.confirmPayment(node.id, operator.value)
    payment.value = data
    ElMessage.success(`「${node.nodeName}」已结算 ${node.amount} 万元`)
    await loadBoard()
  } catch (e) {
    ElMessage.error(e.message)
    // 并发确认 / 网络重试失败时刷新回真实状态，页面不留假成功
    await loadPayment().catch(() => {})
  } finally {
    acting.value = false
  }
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

async function loadPayment() {
  payment.value = contractId.value ? await api.vessel.contractPayment(contractId.value) : null
}

async function reload() {
  await Promise.all([loadBoard(), loadDetail(), loadClearances(), loadPayment()])
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

watch(contractId, () => {
  if (contractId.value) {
    loadPayment().catch((e) => ElMessage.error(e.message))
  } else {
    payment.value = null
  }
})

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title" v-if="contractId && payment">合同 {{ payment.contract.contractNo }} · 付款节点</span>
      <span class="view-title" v-else-if="!vesselId">船舶与修造合同 · 时间轴</span>
      <span class="view-title" v-else>{{ detail?.vessel?.vesselNo }} {{ detail?.vessel?.vesselName }}</span>
      <span class="view-tip" v-if="contractId">结算按合同金额、报验结论与船舶状态计算；确认留痕，重算不改历史</span>
      <span class="view-tip" v-else>一条船一条时间轴，右侧挂合同信息卡；进入履行中后生成分期付款节点，全部节点结算后合同才能整体结算</span>
      <div class="op-box">
        <el-input v-model="operator" size="small" placeholder="操作人（结算必填）" style="width: 140px" />
        <el-button size="small" @click="load">刷新</el-button>
        <el-button size="small" type="primary" @click="dialog = true">新增船舶</el-button>
      </div>
    </div>

    <!-- ============ 合同付款节点分页 ============ -->
    <div v-if="contractId && payment">
      <section class="panel">
        <div class="panel-title">
          {{ payment.contract.contractNo }}
          <span class="tag-line" :style="{ background: contractColor(payment.contract.status), color: '#fff' }">{{ payment.contract.statusName }}</span>
          <span class="view-tip">{{ payment.contract.vesselNo }} {{ payment.contract.vesselName }} · {{ payment.contract.contractTypeName }} · {{ payment.contract.ownerName }}</span>
        </div>
        <div class="vs-grid">
          <div><span class="k">合同金额</span><b>{{ payment.contract.amount }} 万元</b></div>
          <div><span class="k">已结算金额</span><b>{{ payment.summary.paidAmount }} 万元</b></div>
          <div><span class="k">节点进度</span><b>{{ payment.summary.settledNodes }}/{{ payment.summary.totalNodes }} 已结算</b></div>
          <div><span class="k">可结算 / 待结算</span><b>{{ payment.summary.dueNodes }} / {{ payment.summary.pendingNodes }}</b></div>
          <div><span class="k">签订 / 交船</span><b>{{ payment.contract.signDate || '-' }} / {{ payment.contract.deliveryDate || '-' }}</b></div>
          <div v-if="payment.contract.settleRef"><span class="k">结算依据</span><b>{{ payment.contract.settleRef }}</b></div>
        </div>
        <div class="pay-blockers" v-if="payment.summary.blockers.length">
          <div class="pb-title">当前阻塞项（{{ payment.summary.blockers.length }}）</div>
          <div v-for="(b, i) in payment.summary.blockers" :key="i" class="pb-item">· 【{{ b.nodeName }}】{{ b.message }}</div>
        </div>
      </section>

      <section class="panel">
        <div class="panel-title">
          付款节点
          <span class="view-tip">应付金额 = 合同金额 × 比例；条件未满足停留在待结算并逐条列明阻塞；已结算为终态，联动重算不覆盖</span>
        </div>
        <el-table :data="payment.nodes" size="small">
          <el-table-column label="节点" width="110">
            <template #default="{ row }"><b>{{ row.nodeName }}</b></template>
          </el-table-column>
          <el-table-column label="比例" width="70">
            <template #default="{ row }">{{ Math.round(row.ratio * 100) }}%</template>
          </el-table-column>
          <el-table-column label="应付金额(万元)" width="110">
            <template #default="{ row }">{{ row.amount }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <span class="tag-line" :style="{ background: nodeColor(row.status), color: '#fff' }">{{ row.statusName }}</span>
            </template>
          </el-table-column>
          <el-table-column label="阻塞项 / 结算留痕">
            <template #default="{ row }">
              <div v-if="row.status === 'SETTLED'" class="nd-settled">
                {{ row.settledBy }} · {{ fmtTime(row.settledAt) }} · 实结 {{ row.settledAmount }} 万元
              </div>
              <div v-else-if="row.blockers.length">
                <div v-for="(b, i) in row.blockers" :key="i" class="nd-blocker">· {{ b.message }}</div>
              </div>
              <span v-else class="nd-ok">条件已满足，待确认</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button v-if="row.status === 'DUE'" size="small" type="primary" :disabled="acting" @click="confirmNode(row)">确认结算</el-button>
              <span v-else-if="row.status === 'SETTLED'" class="nd-done">已结算</span>
              <span v-else class="nd-wait">待条件满足</span>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="panel">
        <div class="panel-title">
          生成 / 重算 / 确认记录
          <span class="view-tip">只追加不改写：每条记录留存当时的合同金额、报验结论与船舶状态，可解释每一次状态变化</span>
        </div>
        <el-table :data="payment.logs" size="small" v-if="payment.logs.length">
          <el-table-column label="时间" width="150">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column prop="nodeName" label="节点" width="90" />
          <el-table-column prop="actionName" label="动作" width="80" />
          <el-table-column prop="operator" label="操作者 / 触发" width="160" show-overflow-tooltip />
          <el-table-column label="合同金额" width="90">
            <template #default="{ row }">{{ row.contractAmount ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="节点金额" width="90">
            <template #default="{ row }">{{ row.nodeAmount ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="船舶状态" width="80">
            <template #default="{ row }">{{ row.vesselStatusName || '-' }}</template>
          </el-table-column>
          <el-table-column label="当时报验结论" width="170">
            <template #default="{ row }">{{ fmtSurvey(row.surveySummary) }}</template>
          </el-table-column>
          <el-table-column label="当时阻塞" width="180">
            <template #default="{ row }">{{ fmtBlockers(row.blockers) }}</template>
          </el-table-column>
          <el-table-column label="结果" width="80">
            <template #default="{ row }">{{ row.resultName || '-' }}</template>
          </el-table-column>
          <el-table-column prop="note" label="说明" show-overflow-tooltip />
        </el-table>
        <div class="empty-tip" v-else>还没有记录</div>
      </section>
    </div>

    <!-- ============ 单船分页 ============ -->
    <div v-else-if="vesselId && detail">
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
        <div v-for="contract in detail.contracts" :key="contract.id" class="contract-card" :style="{ borderTopColor: contractColor(contract.status) }">
          <div class="cc-head">
            <b>{{ contract.contractNo }}</b>
            <span class="tag-line" :style="{ background: contractColor(contract.status), color: '#fff' }">{{ contract.statusName }}</span>
          </div>
          <div class="cc-type">{{ contract.contractTypeName }} · {{ contract.ownerName }}</div>
          <div class="cc-amount">{{ contract.amount }} <span>万元</span></div>
          <div class="cc-line">签订 {{ contract.signDate || '-' }} · 交船 {{ contract.deliveryDate || '-' }}</div>
          <div class="cc-line" v-if="contract.settleRef">结算依据：{{ contract.settleRef }}</div>
          <div class="cc-line" v-if="contract.paymentSummary && contract.paymentSummary.generated">
            付款 {{ contract.paymentSummary.settledNodes }}/{{ contract.paymentSummary.totalNodes }} 节点 · 已结 {{ contract.paymentSummary.paidAmount }} 万元
            <template v-if="contract.paymentSummary.pendingNodes">· 待结算 {{ contract.paymentSummary.pendingNodes }} 节点</template>
          </div>
          <div class="cc-line cc-blocked" v-if="contract.paymentSummary && contract.paymentSummary.generated && contract.paymentSummary.blockers.length">
            阻塞：{{ contract.paymentSummary.blockers[0].message }}<template v-if="contract.paymentSummary.blockers.length > 1"> 等 {{ contract.paymentSummary.blockers.length }} 项</template>
          </div>
          <div class="cc-note">{{ contract.note }}</div>
          <div class="cc-actions">
            <el-button size="small" type="primary" plain @click="advanceContract(contract)">推进状态</el-button>
            <el-button v-if="contract.paymentSummary && contract.paymentSummary.generated" size="small" @click="openContract(contract)">付款节点</el-button>
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

    <!-- ============ 时间轴总览 ============ -->
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
            <span class="view-tip">草拟 → 已签 → 履行中 → 已结算 · 履行中起按付款节点结算</span>
          </div>
          <div class="contract-cards single">
            <div v-for="contract in contracts" :key="contract.id" class="contract-card" :style="{ borderTopColor: contractColor(contract.status) }">
              <div class="cc-head">
                <b>{{ contract.contractNo }}</b>
                <span class="tag-line" :style="{ background: contractColor(contract.status), color: '#fff' }">{{ contract.statusName }}</span>
              </div>
              <div class="cc-type">{{ contract.contractTypeName }} · {{ contract.vesselNo }} {{ contract.vesselName }}</div>
              <div class="cc-amount">{{ contract.amount }} <span>万元</span></div>
              <div class="cc-line">签订 {{ contract.signDate || '-' }} · 交船 {{ contract.deliveryDate || '-' }}</div>
              <div class="cc-line" v-if="contract.settleRef">结算依据：{{ contract.settleRef }}</div>
              <div class="cc-line" v-if="contract.paymentSummary && contract.paymentSummary.generated">
                付款 {{ contract.paymentSummary.settledNodes }}/{{ contract.paymentSummary.totalNodes }} 节点 · 已结 {{ contract.paymentSummary.paidAmount }} 万元
                <template v-if="contract.paymentSummary.pendingNodes">· 待结算 {{ contract.paymentSummary.pendingNodes }} 节点</template>
              </div>
              <div class="cc-line cc-blocked" v-if="contract.paymentSummary && contract.paymentSummary.generated && contract.paymentSummary.blockers.length">
                阻塞：{{ contract.paymentSummary.blockers[0].message }}<template v-if="contract.paymentSummary.blockers.length > 1"> 等 {{ contract.paymentSummary.blockers.length }} 项</template>
              </div>
              <div class="cc-actions">
                <el-button size="small" type="primary" plain @click="advanceContract(contract)">推进状态</el-button>
                <el-button v-if="contract.paymentSummary && contract.paymentSummary.generated" size="small" @click="openContract(contract)">付款节点</el-button>
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
.cc-line.cc-blocked { color: var(--fail); }
.cc-note { font-size: 11px; color: var(--ink-soft); margin-top: 4px; }
.cc-actions { margin-top: 8px; display: flex; gap: 6px; }

.vs-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 8px 14px; }
.vs-grid .k { font-size: 11px; color: var(--ink-dim); display: block; }
.vs-grid b { font-size: 13px; }

.link { color: var(--primary-deep); font-weight: 700; text-decoration: underline dotted; cursor: pointer; }

.op-box { margin-left: auto; display: flex; gap: 6px; align-items: center; }

.pay-blockers {
  margin-top: 10px;
  border: 1px solid #f3c2c2;
  background: #fdf3f3;
  border-radius: 8px;
  padding: 8px 10px;
}
.pb-title { font-size: 12px; font-weight: 800; color: var(--fail); }
.pb-item { font-size: 11.5px; color: var(--ink-soft); margin-top: 3px; }

.nd-blocker { font-size: 11.5px; color: var(--fail); }
.nd-settled { font-size: 11.5px; color: var(--pass); }
.nd-ok { font-size: 11.5px; color: var(--slipway); }
.nd-done { font-size: 11.5px; color: var(--pass); font-weight: 700; }
.nd-wait { font-size: 11.5px; color: var(--ink-dim); }
</style>
