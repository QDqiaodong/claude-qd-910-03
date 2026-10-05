<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const props = defineProps({
  params: { type: Object, default: () => ({}) },
  tabKey: { type: String, default: '' }
})
const emit = defineEmits(['openTab'])

const board = ref({ erections: [], lifts: [], cranes: [], blocks: [], berths: [], craneStats: [], statusMeta: [], reviewPending: [], maxWindLevel: 6 })
const loading = ref(false)
const dialog = ref(false)

const liftForm = ref({
  erectionId: null,
  craneId: null,
  liftNo: '',
  liftWeightT: null,
  operator: '刘长顺',
  windLevel: 3,
  remark: '',
  status: 'SCHEDULED'
})

const startForm = ref({ liftId: null, liftNo: '', operator: '刘长顺', stage: '起钩', windLevel: 3, liftWeightT: null })
const completeForm = ref({ liftId: null, liftNo: '', liftWeightT: 120, windLevel: 3, remark: '' })
const startDlg = ref(false)
const completeDlg = ref(false)

const erectionForm = ref({ blockId: null, berthId: null, planDate: '', craneId: null, note: '' })

const erections = computed(() => board.value.erections || [])
const lifts = computed(() => board.value.lifts || [])
const cranes = computed(() => board.value.cranes || [])
const erectionId = computed(() => Number(props.params?.erectionId || 0))
const detailErection = computed(() => erections.value.find((e) => e.id === erectionId.value) || null)
const detailLifts = computed(() =>
  erectionId.value ? lifts.value.filter((l) => l.erectionId === erectionId.value) : []
)

const pickedErection = computed(() => erections.value.find((e) => e.id === liftForm.value.erectionId) || null)
// 登记未开始吊次时设备必须取自任务排程，不允许另选
const pickedCrane = computed(() => {
  if (liftForm.value.status === 'DONE') {
    return cranes.value.find((c) => c.id === liftForm.value.craneId) || null
  }
  return cranes.value.find((c) => c.id === pickedErection.value?.craneId) || null
})

/** 超额定起重量：前端先算出来，后端也会拦 */
const loadRate = computed(() => {
  const rated = Number(pickedCrane.value?.ratedLoadT || 0)
  const weight = Number(liftForm.value.liftWeightT || 0)
  if (!rated || !weight) return 0
  return Math.round((weight * 1000.0) / rated) / 10
})
const overload = computed(() => pickedCrane.value && loadRate.value > 100)
const nearFull = computed(() => loadRate.value >= 90 && loadRate.value <= 100)

function rateColor(rate) {
  if (rate > 100) return 'var(--fail)'
  if (rate >= 90) return 'var(--warn)'
  return 'var(--pass)'
}

function liftStatusType(s) {
  return { SCHEDULED: 'info', IN_PROGRESS: 'warning', DONE: 'success' }[s] || ''
}

function openErection(erection) {
  emit('openTab', {
    path: '/erection',
    title: erection.erectionNo,
    sub: erection.blockCode,
    params: { erectionId: erection.id }
  })
}

function openBlock(code, id) {
  if (!id) return
  emit('openTab', { path: '/block', title: code, params: { blockId: id } })
}

function openCrane(craneId) {
  if (!craneId) return
  emit('openTab', { path: '/crane', title: '设备详情', params: { craneId } })
}

async function submitLift() {
  if (!liftForm.value.erectionId) {
    ElMessage.error('请选择搭载任务')
    return
  }
  if (liftForm.value.status === 'SCHEDULED') {
    const e = pickedErection.value
    if (!e?.craneId) {
      ElMessage.error('该任务尚未排程起重设备，请先在新增搭载任务时指派设备')
      return
    }
    if (e.reviewPending) {
      ElMessage.error('该任务排程正待复核（原设备检修），请先完成恢复 / 改派')
      return
    }
  }
  if (overload.value) {
    ElMessage.error('超过额定起重量，禁止起吊')
    return
  }
  loading.value = true
  try {
    const payload = { ...liftForm.value }
    if (payload.status === 'SCHEDULED') {
      delete payload.craneId // 未开始吊次强制走任务排程设备
      if (!payload.liftWeightT) delete payload.liftWeightT
    }
    const lift = await api.erection.registerLift(liftForm.value.erectionId, payload)
    ElMessage.success(`吊次 ${lift.liftNo} 已登记为「${lift.statusName}」`)
    liftForm.value.liftNo = ''
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function openStart(lift) {
  startForm.value = {
    liftId: lift.id, liftNo: lift.liftNo,
    operator: lift.operator || '刘长顺', stage: lift.currentStage || '起钩',
    windLevel: lift.windLevel ?? 3, liftWeightT: lift.liftWeightT
  }
  startDlg.value = true
}

async function submitStart() {
  try {
    const lift = await api.erection.startLift(startForm.value.liftId, {
      operator: startForm.value.operator,
      stage: startForm.value.stage,
      windLevel: startForm.value.windLevel,
      liftWeightT: startForm.value.liftWeightT
    })
    ElMessage.success(`吊次 ${lift.liftNo} 已开始（${lift.currentStage}），设备 ${lift.craneNo} 归属锁定`)
    startDlg.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openComplete(lift) {
  completeForm.value = {
    liftId: lift.id, liftNo: lift.liftNo,
    liftWeightT: lift.liftWeightT, windLevel: lift.windLevel ?? 3, remark: ''
  }
  completeDlg.value = true
}

async function submitComplete() {
  try {
    const lift = await api.erection.completeLift(completeForm.value.liftId, {
      liftWeightT: completeForm.value.liftWeightT,
      windLevel: completeForm.value.windLevel,
      remark: completeForm.value.remark
    })
    ElMessage.success(`吊次 ${lift.liftNo} 已完成，归属 ${lift.craneNo} 落定不可倒改`)
    completeDlg.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function advance(erection) {
  loading.value = true
  try {
    const updated = await api.erection.advance(erection.id)
    ElMessage.success(`${updated.erectionNo} 状态已更新为「${updated.statusName}」`)
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function openReview(row) {
  emit('openTab', { path: '/crane', title: '起重设备', params: { craneId: row.originalCraneId || row.craneId } })
}

async function createErection() {
  // 船舶由所选分段带出（后端会以分段的归属船为准），顺序号也取自分段
  const block = (board.value.blocks || []).find((b) => b.id === erectionForm.value.blockId)
  const payload = { ...erectionForm.value, vesselId: block ? block.vesselId : null }
  loading.value = true
  try {
    const created = await api.erection.create(payload)
    ElMessage.success(`搭载任务 ${created.erectionNo} 已排程${created.craneNo ? '，设备 ' + created.craneNo : ''}`)
    dialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function load() {
  loading.value = true
  try {
    board.value = await api.erection.board()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title" v-if="!erectionId">搭载与吊装 · 吊装时间戳序列</span>
      <span class="view-title" v-else>{{ detailErection?.erectionNo || '搭载任务' }}</span>
      <span class="view-tip">未开始吊次随排程改派 · 进行中归属锁定 · 已完成历史不倒改；设备检修请去「起重设备」页联动处置</span>
    </div>

    <!-- 待复核横幅 -->
    <section class="panel review-banner" v-if="(board.reviewPending || []).length && !erectionId">
      <b>有 {{ board.reviewPending.length }} 个任务因设备检修处于待复核：</b>
      <span v-for="t in board.reviewPending" :key="t.id" class="rp-chip">
        {{ t.erectionNo }}（原 {{ t.originalCraneNo }}）
        <el-button size="small" type="warning" plain @click="openCrane(t.originalCraneId)">去恢复确认</el-button>
      </span>
    </section>

    <div class="grid-2">
      <!-- 时间戳序列 -->
      <section class="panel">
        <div class="panel-title">
          吊装时间戳序列
          <span class="view-tip">进行中 {{ lifts.filter((l) => l.status === 'IN_PROGRESS').length }} ·
            未开始 {{ lifts.filter((l) => l.status === 'SCHEDULED').length }} ·
            历史 {{ lifts.filter((l) => l.status === 'DONE').length }}</span>
        </div>
        <div class="timeline">
          <div v-for="lift in lifts" :key="lift.id" class="tl-item">
            <div class="tl-dot" :style="{ background: rateColor(lift.loadRate) }"></div>
            <div class="tl-body">
              <div class="tl-head">
                <b>{{ lift.liftNo }}</b>
                <el-tag :type="liftStatusType(lift.status)" size="small">{{ lift.statusName }}</el-tag>
                <span class="tl-time">{{ lift.liftTime }}</span>
              </div>
              <div class="tl-line">
                <span class="tl-code" @click="openBlock(lift.blockCode, lift.blockId)">{{ lift.blockCode }}</span>
                · <span class="crane-link" @click="openCrane(lift.craneId)">{{ lift.craneName }}</span> · 顺序 {{ lift.segSeq }}
                <span v-if="lift.status === 'IN_PROGRESS'" class="stage-tag">阶段：{{ lift.currentStage || '-' }}</span>
              </div>
              <div class="tl-bar" v-if="lift.liftWeightT">
                <i :style="{ width: Math.min(100, lift.loadRate) + '%', background: rateColor(lift.loadRate) }"></i>
              </div>
              <div class="tl-line dim">
                <template v-if="lift.liftWeightT">
                  {{ lift.liftWeightT }} t / 额定 {{ lift.ratedLoadT }} t · 载荷率
                  <b :style="{ color: rateColor(lift.loadRate) }">{{ lift.loadRate }}%</b> ·
                </template>
                {{ lift.operator || '待派司机' }} · {{ lift.windLevel ?? '-' }} 级风
              </div>
              <div class="tl-line safety" v-if="lift.safetyHandling">⚠ 安全处置：{{ lift.safetyHandling }}</div>
              <div class="tl-actions">
                <el-button size="small" type="warning" plain v-if="lift.status === 'SCHEDULED'" @click="openStart(lift)">吊次开始</el-button>
                <el-button size="small" type="success" plain v-if="lift.status === 'IN_PROGRESS'" @click="openComplete(lift)">完成吊次</el-button>
              </div>
            </div>
          </div>
          <div class="empty-tip" v-if="!lifts.length">还没有吊次记录</div>
        </div>
      </section>

      <!-- 吊次借记卡 -->
      <div>
        <section class="panel">
          <div class="panel-title">吊次借记卡</div>
          <el-form label-width="104px">
            <el-form-item label="登记类型">
              <el-radio-group v-model="liftForm.status">
                <el-radio value="SCHEDULED">未开始（随排程）</el-radio>
                <el-radio value="DONE">补登历史吊次</el-radio>
            </el-radio-group>
            </el-form-item>
            <el-form-item label="搭载任务">
              <el-select v-model="liftForm.erectionId" style="width:100%" placeholder="选择搭载任务">
                <el-option
                  v-for="e in erections" :key="e.id"
                  :label="e.erectionNo + ' · ' + e.blockCode + ' · ' + e.statusName + (e.reviewPending ? ' · 待复核' : '')"
                  :value="e.id"
                  :disabled="liftForm.status === 'SCHEDULED' && (!e.craneId || e.reviewPending)"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="起重设备">
              <el-select v-if="liftForm.status === 'DONE'" v-model="liftForm.craneId" style="width:100%" placeholder="选择设备">
                <el-option
                  v-for="c in cranes" :key="c.id"
                  :label="c.craneNo + ' ' + c.craneName + '（额定 ' + c.ratedLoadT + ' t）'"
                  :value="c.id"
                  :disabled="c.status !== 'AVAILABLE'"
                />
              </el-select>
              <el-input v-else :model-value="pickedCrane ? pickedCrane.craneNo + ' ' + pickedCrane.craneName : '该任务尚未排程设备'" readonly>
                <template #append><el-button @click="openCrane(pickedCrane?.id)">设备</el-button></template>
              </el-input>
            </el-form-item>
            <el-form-item label="吊次号"><el-input v-model="liftForm.liftNo" :placeholder="liftForm.status === 'SCHEDULED' ? '留空自动生成 LF-日期-序号' : '如 LF-20260501-001'" /></el-form-item>
            <el-form-item label="吊装重量">
              <el-input-number v-model="liftForm.liftWeightT" :min="1" />
              <span class="unit">t{{ liftForm.status === 'SCHEDULED' ? '（未开始可留空，默认分段重量）' : '' }}</span>
            </el-form-item>
            <el-form-item label="司机"><el-input v-model="liftForm.operator" /></el-form-item>
            <el-form-item label="风力">
              <el-input-number v-model="liftForm.windLevel" :min="0" :max="12" />
              <span class="unit">级（>{{ board.maxWindLevel }} 级禁止吊装）</span>
            </el-form-item>
            <el-form-item label="备注"><el-input v-model="liftForm.remark" /></el-form-item>
          </el-form>

          <div class="gauge" v-if="pickedCrane && liftForm.liftWeightT">
            <div class="gauge-top">
              <span>载荷率</span>
              <b :style="{ color: rateColor(loadRate) }">{{ loadRate }}%</b>
            </div>
            <div class="gauge-bar"><i :style="{ width: Math.min(100, loadRate) + '%', background: rateColor(loadRate) }"></i></div>
            <div class="gauge-foot">
              {{ liftForm.liftWeightT }} t / {{ pickedCrane.ratedLoadT }} t（{{ pickedCrane.craneName }}）
            </div>
            <div class="gauge-alert" v-if="overload">超过额定起重量，禁止起吊（后端同样拦截）</div>
            <div class="gauge-warn" v-else-if="nearFull">接近满载，注意试吊</div>
          </div>

          <div class="card-actions">
            <el-button type="primary" :loading="loading" :disabled="overload" @click="submitLift">登记吊次</el-button>
            <span class="view-tip" v-if="pickedErection">{{ pickedErection.blockCode }} · 顺序 {{ pickedErection.segSeq }}</span>
          </div>
        </section>

        <section class="panel">
          <div class="panel-title">按设备类型统计（仅历史吊次 / 手写 JPQL）</div>
          <el-table :data="board.craneStats" size="small">
            <el-table-column prop="craneTypeName" label="设备类型" width="100" />
            <el-table-column prop="liftCount" label="吊次数" width="80" />
            <el-table-column prop="weightSum" label="总吨位(t)" width="100" />
            <el-table-column prop="avgLoadRate" label="平均载荷率%" />
          </el-table>
        </section>
      </div>
    </div>

    <!-- 搭载任务 -->
    <section class="panel">
      <div class="panel-title">
        搭载任务
        <span class="view-tip">排程设备随检修联动；待复核任务不能开始吊装</span>
        <el-button size="small" type="primary" plain style="margin-left:auto" @click="dialog = true">新增搭载任务</el-button>
      </div>
      <el-table :data="erections" size="small">
        <el-table-column prop="erectionNo" label="任务号" width="150" />
        <el-table-column prop="vesselNo" label="船号" width="90" />
        <el-table-column prop="blockCode" label="分段" width="150">
          <template #default="{ row }">
            <span class="tl-code" @click="openBlock(row.blockCode, row.blockId)">{{ row.blockCode }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="segSeq" label="顺序" width="60" />
        <el-table-column prop="berthNo" label="船台" width="80" />
        <el-table-column prop="planDate" label="计划日期" width="105" />
        <el-table-column label="排程设备" width="150">
          <template #default="{ row }">
            <el-tag v-if="row.reviewPending" type="warning" size="small" class="rp-tag">
              待复核 · 原 {{ row.originalCraneNo }}
            </el-tag>
            <span v-else-if="row.craneNo" class="crane-link" @click="openCrane(row.craneId)">{{ row.craneNo }} {{ row.craneName }}</span>
            <span v-else class="view-tip">未排程</span>
          </template>
        </el-table-column>
        <el-table-column prop="statusName" label="状态" width="80" />
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="openErection(row)">详情</el-button>
            <el-button size="small" type="warning" plain v-if="row.reviewPending" @click="openReview(row)">复核</el-button>
            <el-button size="small" type="primary" plain v-else @click="advance(row)">推进</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <!-- 详情分页 -->
    <section class="panel" v-if="erectionId && detailErection">
      <div class="panel-title">
        {{ detailErection.erectionNo }} · 吊次明细（{{ detailLifts.length }}）
        <span class="view-tip">
          {{ detailErection.blockCode }} · 船台 {{ detailErection.berthNo || '-' }} · {{ detailErection.statusName }}
          · 排程
          <template v-if="detailErection.reviewPending">待复核（原 {{ detailErection.originalCraneNo }} → 现 {{ detailErection.craneNo }}）</template>
          <template v-else>{{ detailErection.craneNo || '未排程' }}</template>
        </span>
      </div>
      <el-table :data="detailLifts" size="small" v-if="detailLifts.length">
        <el-table-column prop="liftNo" label="吊次号" width="160" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag :type="liftStatusType(row.status)" size="small">{{ row.statusName }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="craneName" label="设备" />
        <el-table-column prop="currentStage" label="当前阶段" width="100" />
        <el-table-column prop="liftWeightT" label="重量(t)" width="90" />
        <el-table-column prop="ratedLoadT" label="额定(t)" width="90" />
        <el-table-column prop="liftTime" label="时间" width="160" />
        <el-table-column prop="safetyHandling" label="安全处置" min-width="180" />
      </el-table>
      <div class="empty-tip" v-else>这个任务还没有吊次记录</div>
    </section>

    <!-- 吊次开始 -->
    <el-dialog v-model="startDlg" :title="'吊次开始 · ' + startForm.liftNo" width="520px">
      <el-alert type="warning" :closable="false" show-icon class="gap-top"
        title="吊次一旦开始，设备归属立即锁定：即使设备进入检修也不得换机，只允许记录安全处置。" />
      <el-form label-width="90px" class="gap-top">
        <el-form-item label="司机"><el-input v-model="startForm.operator" /></el-form-item>
        <el-form-item label="当前阶段">
          <el-select v-model="startForm.stage" style="width:100%">
            <el-option v-for="s in ['起钩', '翻身', '转运', '落位', '摘钩']" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="实际重量">
          <el-input-number v-model="startForm.liftWeightT" :min="1" /><span class="unit">t</span>
        </el-form-item>
        <el-form-item label="风力"><el-input-number v-model="startForm.windLevel" :min="0" :max="12" /><span class="unit">级</span></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="startDlg = false">取消</el-button>
        <el-button type="warning" @click="submitStart">确认开始，锁定设备</el-button>
      </template>
    </el-dialog>

    <!-- 吊次完成 -->
    <el-dialog v-model="completeDlg" :title="'完成吊次 · ' + completeForm.liftNo" width="520px">
      <el-alert type="info" :closable="false" show-icon class="gap-top"
        title="完成后成为历史吊次，永远归属实际执行设备，任何检修恢复都不会倒改。" />
      <el-form label-width="90px" class="gap-top">
        <el-form-item label="实际重量"><el-input-number v-model="completeForm.liftWeightT" :min="1" /><span class="unit">t</span></el-form-item>
        <el-form-item label="风力"><el-input-number v-model="completeForm.windLevel" :min="0" :max="12" /><span class="unit">级</span></el-form-item>
        <el-form-item label="备注"><el-input v-model="completeForm.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="completeDlg = false">取消</el-button>
        <el-button type="success" @click="submitComplete">确认完成</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dialog" title="新增搭载任务（指派排程设备）" width="560px">
      <el-form label-width="100px">
        <el-form-item label="分段">
          <el-select v-model="erectionForm.blockId" style="width:100%">
            <el-option
              v-for="b in board.blocks || []" :key="b.id"
              :label="b.blockCode + ' ' + b.blockName + '（' + b.vesselNo + ' 顺序 ' + b.segSeq + '）'"
              :value="b.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="船台">
          <el-select v-model="erectionForm.berthId" style="width:100%">
            <el-option v-for="b in board.berths || []" :key="b.id" :label="b.berthNo + ' ' + b.berthName + '（' + (b.location || '') + '）'" :value="b.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="计划日期"><el-input v-model="erectionForm.planDate" placeholder="2026-10-08" /></el-form-item>
        <el-form-item label="排程设备">
          <el-select v-model="erectionForm.craneId" style="width:100%" clearable placeholder="选择起重设备（能力/区域/档期校验）">
            <el-option v-for="c in (board.cranes || [])" :key="c.id"
              :label="c.craneNo + ' ' + c.craneName + '（额定 ' + c.ratedLoadT + 't · ' + (c.location || '') + '）'"
              :value="c.id" :disabled="c.status !== 'AVAILABLE'" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="erectionForm.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="createErection">排程</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.timeline { position: relative; padding-left: 6px; }
.tl-item { display: flex; gap: 10px; padding-bottom: 12px; }
.tl-dot { width: 10px; height: 10px; border-radius: 50%; margin-top: 4px; flex: 0 0 auto; }
.tl-body { flex: 1 1 auto; border-bottom: 1px dashed var(--line); padding-bottom: 8px; }
.tl-head { display: flex; align-items: baseline; gap: 8px; }
.tl-time { font-size: 11px; color: var(--ink-dim); margin-left: auto; }
.tl-line { font-size: 11.5px; color: var(--ink-soft); margin-top: 2px; }
.tl-line.dim { color: var(--ink-dim); }
.tl-line.safety { color: var(--fail); }
.tl-code { color: var(--primary-deep); font-weight: 700; text-decoration: underline dotted; cursor: pointer; }
.crane-link { color: var(--primary-deep); cursor: pointer; text-decoration: underline dotted; }
.stage-tag { margin-left: 6px; color: var(--warn, #b88230); font-weight: 700; }
.tl-bar { height: 5px; background: var(--line); border-radius: 3px; margin: 6px 0 4px; overflow: hidden; }
.tl-bar i { display: block; height: 100%; }
.tl-actions { margin-top: 6px; }

.review-banner { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; border-left: 4px solid var(--warn, #e6a23c); }
.rp-chip { font-size: 12px; color: var(--ink-soft); display: inline-flex; align-items: center; gap: 6px; }
.rp-tag { margin-right: 4px; }

.unit { font-size: 11px; color: var(--ink-dim); margin-left: 6px; }

.gauge { background: var(--primary-wash); border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; }
.gauge-top { display: flex; justify-content: space-between; font-size: 12px; }
.gauge-top b { font-size: 17px; }
.gauge-bar { height: 8px; background: #fff; border-radius: 4px; margin-top: 6px; overflow: hidden; }
.gauge-bar i { display: block; height: 100%; }
.gauge-foot { font-size: 11px; color: var(--ink-dim); margin-top: 6px; }
.gauge-alert { margin-top: 6px; font-size: 12px; font-weight: 700; color: var(--fail); }
.gauge-warn { margin-top: 6px; font-size: 12px; font-weight: 700; color: var(--warn); }
.card-actions { display: flex; align-items: center; gap: 10px; margin-top: 12px; }
.gap-top { margin-top: 10px; }
</style>
