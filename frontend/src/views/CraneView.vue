<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const props = defineProps({
  params: { type: Object, default: () => ({}) },
  tabKey: { type: String, default: '' }
})
const emit = defineEmits(['openTab'])

const board = ref({ cranes: [] })
const detail = ref(null)
const loading = ref(false)

// 停用联动弹窗
const dlg = ref(false)
const impact = ref(null)
const unavailForm = ref({ status: 'MAINTAINING', reason: '', operator: '马国栋', decision: 'AUTO', safetyHandling: '' })
const submitting = ref(false)

// 人工改派弹窗
const reassignDlg = ref(false)
const reassignTask = ref(null)
const reassignCandidates = ref([])
const reassignForm = ref({ targetCraneId: null, operator: '马国栋', note: '' })

// 恢复复核弹窗（单任务）
const reviewDlg = ref(false)
const reviewTask = ref(null)
const reviewCandidates = ref([])
const reviewForm = ref({ decision: 'RESTORE', operator: '马国栋', note: '' })

const cranes = computed(() => board.value.cranes || [])
const craneId = computed(() => Number(props.params?.craneId || 0))

function craneStatusType(s) {
  return { AVAILABLE: 'success', MAINTAINING: 'warning', RETIRED: 'info' }[s] || ''
}

// ---------------- 停用 / 检修 ----------------

async function openUnavailable(crane) {
  unavailForm.value = { status: 'MAINTAINING', reason: '', operator: '马国栋', decision: 'AUTO', safetyHandling: '' }
  impact.value = null
  dlg.value = true
  try {
    impact.value = await api.schedule.impact(crane.id)
  } catch (e) {
    ElMessage.error(e.message)
    dlg.value = false
  }
}

const taskImpacts = computed(() => impact.value?.taskImpacts || [])
const impactTasks = computed(() => impact.value?.notStartedTasks || [])
const inProgress = computed(() => impact.value?.inProgressLifts || [])
const history = computed(() => impact.value?.historyLifts || [])

/** 每个未开始任务的首选合格替代（自动改派将按它执行） */
function bestAlt(taskId) {
  const ti = taskImpacts.value.find((t) => t.task.id === taskId)
  return (ti?.alternatives || []).find((a) => a.eligible) || null
}

async function submitUnavailable() {
  if (!unavailForm.value.reason.trim()) {
    ElMessage.error('必须填写检修 / 停用原因')
    return
  }
  if (inProgress.value.length && !unavailForm.value.safetyHandling.trim()) {
    ElMessage.error(`有 ${inProgress.value.length} 个进行中吊次，必须填写安全处置记录（不得换机）`)
    return
  }
  if (unavailForm.value.decision === 'AUTO') {
    const missing = impactTasks.value.filter((t) => !bestAlt(t.id))
    if (missing.length) {
      ElMessage.error(`${missing.map((t) => t.erectionNo).join('、')} 找不到三重匹配的替代设备，请改选「保留原排程待复核」`)
      return
    }
  }
  submitting.value = true
  try {
    const res = await api.schedule.unavailable(impact.value.crane.id, unavailForm.value)
    const parts = []
    if (res.reassignedTasks.length) parts.push(`自动改派 ${res.reassignedTasks.length} 个任务`)
    if (res.heldTasks.length) parts.push(`${res.heldTasks.length} 个任务保留待复核`)
    if (res.inProgressLifts.length) parts.push(`${res.inProgressLifts.length} 个进行中吊次已挂安全处置`)
    ElMessage.success(`设备已置为${unavailForm.value.status === 'RETIRED' ? '停用' : '检修'}：${parts.join('，') || '无关联排程'}`)
    dlg.value = false
    await reload()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    submitting.value = false
  }
}

// ---------------- 恢复 ----------------

async function recover(crane) {
  try {
    await ElMessageBox.confirm(
      `确认设备 ${crane.craneNo} 检修完成、恢复可用？恢复后不会自动回改排程，待复核任务需逐任务选择「恢复原排程 / 保留替代」。`,
      '设备恢复', { confirmButtonText: '恢复可用', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await api.schedule.recover(crane.id, '马国栋')
    ElMessage.success('设备已恢复可用，请对待复核任务逐任务确认')
    await reload()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

// ---------------- 待复核：逐任务恢复 / 保留替代 ----------------

async function openReview(task) {
  reviewTask.value = task
  reviewForm.value = {
    decision: task.originalCraneId ? 'RESTORE' : 'KEEP_ALTERNATIVE',
    operator: '马国栋', note: ''
  }
  reviewCandidates.value = []
  reviewDlg.value = true
  try {
    reviewCandidates.value = await api.schedule.alternatives(task.id)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function submitReview() {
  try {
    await api.schedule.review(reviewTask.value.id, reviewForm.value)
    ElMessage.success(reviewForm.value.decision === 'RESTORE' ? '已恢复原排程' : '已保留替代设备')
    reviewDlg.value = false
    await reload()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

// ---------------- 人工改派 ----------------

async function openReassign(task) {
  reassignTask.value = task
  reassignForm.value = { targetCraneId: null, operator: '马国栋', note: '' }
  reassignCandidates.value = []
  reassignDlg.value = true
  try {
    reassignCandidates.value = await api.schedule.alternatives(task.id)
    reassignForm.value.targetCraneId = reassignCandidates.value.find((c) => c.eligible)?.craneId || null
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function submitReassign() {
  if (!reassignForm.value.targetCraneId) {
    ElMessage.error('请选择替代设备')
    return
  }
  try {
    await api.schedule.reassign(reassignTask.value.id, reassignForm.value)
    ElMessage.success('任务已改派')
    reassignDlg.value = false
    await reload()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openDetail(crane) {
  emit('openTab', { path: '/crane', title: crane.craneNo, sub: crane.craneName, params: { craneId: crane.id } })
}

async function loadBoard() {
  board.value = await api.erection.board()
}

async function loadDetail() {
  if (!craneId.value) {
    detail.value = null
    return
  }
  detail.value = await api.schedule.craneDetail(craneId.value)
}

async function reload() {
  loading.value = true
  try {
    await Promise.all([loadBoard(), loadDetail()])
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

watch(craneId, () => {
  if (craneId.value) loadDetail().catch((e) => ElMessage.error(e.message))
  else detail.value = null
})

onMounted(reload)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title" v-if="!craneId">起重设备 · 检修状态与吊装排程联动</span>
      <span class="view-title" v-else>{{ detail?.crane?.craneNo || '设备' }} · 详情</span>
      <span class="view-tip" v-if="!craneId">停用即汇总未开始任务 / 进行中吊次 / 历史吊次；进行中不换机，历史不倒改</span>
    </div>

    <!-- ==================== 设备详情 ==================== -->
    <div v-if="craneId && detail">
      <section class="panel">
        <div class="panel-title">
          {{ detail.crane.craneName }}
          <el-tag :type="craneStatusType(detail.crane.status)" size="small">{{ detail.crane.statusName }}</el-tag>
          <span class="view-tip">{{ detail.crane.craneTypeName }} · 额定 {{ detail.crane.ratedLoadT }} t · 驻位 {{ detail.crane.location || '-' }}</span>
          <el-button style="margin-left:auto" size="small" type="primary" plain
            v-if="detail.crane.status === 'AVAILABLE'" @click="openUnavailable(detail.crane)">设为检修 / 停用</el-button>
          <el-button style="margin-left:auto" size="small" type="success" plain
            v-else-if="detail.crane.status === 'MAINTAINING'" @click="recover(detail.crane)">检修恢复</el-button>
        </div>
        <div class="kv-grid" v-if="detail.crane.status !== 'AVAILABLE'">
          <div><span class="k">停用原因</span><b>{{ detail.crane.unavailableReason || '-' }}</b></div>
          <div><span class="k">进入时间</span><b>{{ detail.crane.maintenanceFrom || '-' }}</b></div>
          <div><span class="k">操作人</span><b>{{ detail.crane.maintenanceOperator || '-' }}</b></div>
        </div>
        <div class="impact-banner" v-if="detail.crane.status !== 'AVAILABLE'">
          该设备已禁止承接新吊次；进行中吊次保留原机与阶段，历史吊次永远归属本设备，恢复不会倒改。
        </div>
      </section>

      <div class="grid-3">
        <section class="panel">
          <div class="panel-title">未开始任务（{{ detail.notStartedTasks.length }}）<span class="view-tip">可改派 / 待复核</span></div>
          <el-table :data="detail.notStartedTasks" size="small">
            <el-table-column prop="erectionNo" label="任务号" width="150" />
            <el-table-column prop="blockCode" label="分段" width="140" />
            <el-table-column prop="planDate" label="档期" width="100" />
            <el-table-column label="排程" min-width="110">
              <template #default="{ row }">
                <el-tag v-if="row.reviewPending" type="warning" size="small">待复核</el-tag>
                <span v-else>{{ row.craneNo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="170">
              <template #default="{ row }">
                <el-button size="small" type="warning" plain v-if="row.reviewPending" @click="openReview(row)">恢复确认</el-button>
                <el-button size="small" type="primary" plain v-else @click="openReassign(row)">改派</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="empty-tip" v-if="!detail.notStartedTasks.length">没有未开始任务</div>
        </section>

        <section class="panel">
          <div class="panel-title">进行中吊次（{{ detail.inProgressLifts.length }}）<span class="view-tip">归属冻结</span></div>
          <div v-for="l in detail.inProgressLifts" :key="l.id" class="frozen-card">
            <div class="fz-head"><b>{{ l.liftNo }}</b><el-tag type="danger" size="small">进行中 · {{ l.currentStage || '-' }}</el-tag></div>
            <div class="fz-line">{{ l.blockCode }} · {{ l.liftWeightT }} t · 司机 {{ l.operator || '-' }}</div>
            <div class="fz-line safety">安全处置：{{ l.safetyHandling || '（停用本设备时必须登记）' }}</div>
          </div>
          <div class="empty-tip" v-if="!detail.inProgressLifts.length">没有进行中吊次</div>
        </section>

        <section class="panel">
          <div class="panel-title">历史吊次（{{ detail.historyLifts.length }}）<span class="view-tip">永不倒改</span></div>
          <el-table :data="detail.historyLifts" size="small" max-height="320">
            <el-table-column prop="liftNo" label="吊次号" width="150" />
            <el-table-column prop="blockCode" label="分段" width="140" />
            <el-table-column prop="liftWeightT" label="重量(t)" width="80" />
            <el-table-column prop="liftTime" label="完成时间" min-width="150" />
          </el-table>
          <div class="empty-tip" v-if="!detail.historyLifts.length">没有历史吊次</div>
        </section>
      </div>

      <section class="panel">
        <div class="panel-title">排程操作日志（责任追溯，只追加）</div>
        <el-timeline v-if="detail.logs.length">
          <el-timeline-item v-for="log in detail.logs" :key="log.id" :timestamp="log.createdAt" placement="top">
            <b>{{ log.actionTypeName }}</b>
            <span class="view-tip" v-if="log.otherCraneNo"> · 关联 {{ log.otherCraneNo }}</span>
            <div class="log-detail">{{ log.detail }}</div>
            <div class="view-tip" v-if="log.operator">操作人：{{ log.operator }}</div>
          </el-timeline-item>
        </el-timeline>
        <div class="empty-tip" v-else>暂无操作日志</div>
      </section>
    </div>

    <!-- ==================== 设备列表（管理员主页） ==================== -->
    <div v-else>
      <section class="panel">
        <div class="panel-title">
          起重设备台账
          <span class="view-tip">检修 / 停用必须走联动处置，不能只改设备状态或直接替换排程里的设备编号</span>
        </div>
        <el-table :data="cranes" size="small" v-loading="loading">
          <el-table-column prop="craneNo" label="编号" width="80" />
          <el-table-column prop="craneName" label="名称" min-width="130" />
          <el-table-column prop="craneTypeName" label="类型" width="90" />
          <el-table-column prop="ratedLoadT" label="额定(t)" width="90" />
          <el-table-column prop="location" label="作业区域" width="100" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="craneStatusType(row.status)" size="small">{{ row.statusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="检修信息" min-width="200">
            <template #default="{ row }">
              <span v-if="row.status !== 'AVAILABLE'" class="maint-info">
                {{ row.unavailableReason || '—' }}（{{ row.maintenanceOperator || '—' }}）
              </span>
              <span v-else class="view-tip">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="230">
            <template #default="{ row }">
              <el-button size="small" type="primary" plain @click="openDetail(row)">详情</el-button>
              <el-button size="small" type="warning" plain v-if="row.status === 'AVAILABLE'" @click="openUnavailable(row)">检修 / 停用</el-button>
              <el-button size="small" type="success" plain v-else-if="row.status === 'MAINTAINING'" @click="recover(row)">检修恢复</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="panel">
        <div class="panel-title">
          待复核排程
          <span class="view-tip">设备恢复后，只能对这些「未开始且待复核」的任务逐任务选择恢复原机或保留替代，并重查状态 / 能力 / 档期</span>
        </div>
        <el-table :data="(board.erections || []).filter((e) => e.reviewPending)" size="small">
          <el-table-column prop="erectionNo" label="任务号" width="150" />
          <el-table-column prop="blockCode" label="分段" width="140" />
          <el-table-column label="原设备" width="110">
            <template #default="{ row }">{{ row.originalCraneNo || '-' }}</template>
          </el-table-column>
          <el-table-column label="当前替代" width="110">
            <template #default="{ row }">{{ row.craneNo || '-' }}</template>
          </el-table-column>
          <el-table-column prop="planDate" label="档期" width="110" />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button size="small" type="warning" plain @click="openReview(row)">恢复确认</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="empty-tip" v-if="!(board.erections || []).some((e) => e.reviewPending)">没有待复核排程</div>
      </section>
    </div>

    <!-- ==================== 停用影响 + 决策弹窗 ==================== -->
    <el-dialog v-model="dlg" :title="'设备检修 / 停用联动处置 · ' + (impact?.crane?.craneNo || '')" width="920px" top="6vh">
      <div v-if="impact">
        <el-form label-width="96px" class="unavail-form">
          <el-form-item label="处置状态">
            <el-radio-group v-model="unavailForm.status">
              <el-radio value="MAINTAINING">检修（可恢复）</el-radio>
              <el-radio value="RETIRED">停用报废</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="原因" required>
            <el-input v-model="unavailForm.reason" type="textarea" :rows="2" placeholder="如：行走机构异响，返厂更换减速箱" />
          </el-form-item>
          <el-form-item label="操作人"><el-input v-model="unavailForm.operator" style="width:200px" /></el-form-item>
        </el-form>

        <div class="impact-summary">
          <div class="sum-item"><b>{{ impactTasks.length }}</b><span>未开始任务</span></div>
          <div class="sum-item warn"><b>{{ inProgress.length }}</b><span>进行中吊次</span></div>
          <div class="sum-item dim"><b>{{ history.length }}</b><span>历史吊次</span></div>
        </div>

        <!-- 进行中吊次：只能安全处置 -->
        <section class="sub-panel" v-if="inProgress.length">
          <div class="sub-title danger">进行中吊次不得静默换机：保留原设备与当前阶段，仅登记安全处置</div>
          <el-table :data="inProgress" size="small">
            <el-table-column prop="liftNo" label="吊次号" width="150" />
            <el-table-column prop="blockCode" label="分段" width="140" />
            <el-table-column prop="currentStage" label="当前阶段" width="100" />
            <el-table-column prop="craneNo" label="原设备(锁定)" width="110" />
          </el-table>
          <el-input v-model="unavailForm.safetyHandling" type="textarea" :rows="2" class="safety-input"
            placeholder="必填：统一安全处置记录，如「立即停止起升，将载荷稳妥落位于胎架，摘钩断电，设置监护人员，恢复前禁止再启动」" />
        </section>

        <!-- 历史吊次：只提示，不动 -->
        <section class="sub-panel" v-if="history.length">
          <div class="sub-title">历史吊次（{{ history.length }}）永远归属实际执行设备 {{ impact.crane.craneNo }}，检修恢复不得倒改</div>
        </section>

        <!-- 未开始任务：二选一 -->
        <section class="sub-panel" v-if="impactTasks.length">
          <div class="sub-title">未开始任务（{{ impactTasks.length }}）— 管理员必须二选一：</div>
          <el-radio-group v-model="unavailForm.decision" class="decision-group">
            <el-radio value="AUTO">自动筛选能力、作业区域、档期都匹配的替代设备并改派</el-radio>
            <el-radio value="HOLD">保留原排程，全部置为「待复核」，恢复后逐任务确认</el-radio>
          </el-radio-group>

          <el-table :data="impactTasks" size="small" class="task-table">
            <el-table-column prop="erectionNo" label="任务号" width="150" />
            <el-table-column prop="blockCode" label="分段" width="140" />
            <el-table-column prop="planDate" label="档期" width="100" />
            <el-table-column label="自动改派目标 / 保留态" min-width="260">
              <template #default="{ row }">
                <template v-if="unavailForm.decision === 'AUTO'">
                  <el-tag v-if="bestAlt(row.id)" type="success" size="small">
                    {{ bestAlt(row.id).craneNo }} {{ bestAlt(row.id).craneName }}
                  </el-tag>
                  <el-tag v-else type="danger" size="small">无三重匹配设备</el-tag>
                  <span class="view-tip" v-if="bestAlt(row.id)">
                    额定 {{ bestAlt(row.id).ratedLoadT }}t · {{ bestAlt(row.id).location }}
                  </span>
                </template>
                <el-tag v-else type="warning" size="small">保留 {{ impact.crane.craneNo }} · 待复核</el-tag>
              </template>
            </el-table-column>
          </el-table>

          <!-- 候选明细（折叠） -->
          <el-collapse class="alt-collapse">
            <el-collapse-item v-for="ti in taskImpacts" :key="ti.task.id"
              :title="ti.task.erectionNo + ' · ' + ti.task.blockCode + ' · 替代候选明细（能力 / 区域 / 档期）'"
              :name="ti.task.id">
              <el-table :data="ti.alternatives" size="small">
                <el-table-column prop="craneNo" label="设备" width="90" />
                <el-table-column prop="craneName" label="名称" min-width="120" />
                <el-table-column label="能力" width="150">
                  <template #default="{ row }">
                    <span :class="row.capacityMatch ? 'ok' : 'no'">{{ row.capacityText }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="作业区域" min-width="190">
                  <template #default="{ row }">
                    <span :class="row.areaMatch ? 'ok' : 'no'">{{ row.areaText }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="档期" min-width="160">
                  <template #default="{ row }">
                    <span :class="row.scheduleMatch ? 'ok' : 'no'">{{ row.scheduleText }}</span>
                  </template>
                </el-table-column>
              </el-table>
            </el-collapse-item>
          </el-collapse>
        </section>

        <!-- 影响说明 -->
        <div class="impact-notes">
          <div><b>对分段搭载进度的影响：</b>
            自动改派后任务按新设备档期继续，计划日期如冲突需重新协调；保留待复核期间任务不允许开始吊装，
            涉及船号 {{ (impact.affectedVessels || []).join('、') || '—' }} 的搭载节点将顺延，顺序约束不变。
          </div>
          <div><b>对责任追溯的影响：</b>
            停用原因、决策方式、改派前后设备、安全处置记录全部写入只追加的排程操作日志；
            进行中吊次与历史吊次的执行设备不可更改，恢复操作不会倒改任何既有记录。
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitUnavailable">确认联动处置</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 人工改派弹窗 ==================== -->
    <el-dialog v-model="reassignDlg" title="人工改派（能力 / 作业区域 / 档期三重校验）" width="720px">
      <div v-if="reassignTask" class="review-head">
        <b>{{ reassignTask.erectionNo }} · {{ reassignTask.blockCode }}</b>
        <span class="view-tip">档期 {{ reassignTask.planDate }} · 当前 {{ reassignTask.craneNo || '未排程' }}</span>
      </div>
      <el-table :data="reassignCandidates" size="small">
        <el-table-column width="55">
          <template #default="{ row }">
            <el-radio v-model="reassignForm.targetCraneId" :value="row.craneId" :disabled="!row.eligible">&nbsp;</el-radio>
          </template>
        </el-table-column>
        <el-table-column prop="craneNo" label="设备" width="90" />
        <el-table-column prop="craneName" label="名称" min-width="110" />
        <el-table-column label="能力" width="150">
          <template #default="{ row }"><span :class="row.capacityMatch ? 'ok' : 'no'">{{ row.capacityText }}</span></template>
        </el-table-column>
        <el-table-column label="区域" min-width="180">
          <template #default="{ row }"><span :class="row.areaMatch ? 'ok' : 'no'">{{ row.areaText }}</span></template>
        </el-table-column>
        <el-table-column label="档期" min-width="150">
          <template #default="{ row }"><span :class="row.scheduleMatch ? 'ok' : 'no'">{{ row.scheduleText }}</span></template>
        </el-table-column>
      </el-table>
      <el-input v-model="reassignForm.note" class="gap-top" placeholder="改派说明（写入操作日志）" />
      <template #footer>
        <el-button @click="reassignDlg = false">取消</el-button>
        <el-button type="primary" @click="submitReassign">确认改派</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 恢复复核弹窗 ==================== -->
    <el-dialog v-model="reviewDlg" title="检修恢复 · 排程复核" width="720px">
      <div v-if="reviewTask" class="review-head">
        <b>{{ reviewTask.erectionNo }} · {{ reviewTask.blockCode }}</b>
        <span class="view-tip">档期 {{ reviewTask.planDate }} · 当前 {{ reviewTask.craneNo }} · 原设备 {{ reviewTask.originalCraneNo || '-' }}</span>
      </div>
      <el-radio-group v-model="reviewForm.decision" class="decision-group">
        <el-radio value="RESTORE" :disabled="!reviewTask.originalCraneId">恢复原排程（{{ reviewTask.originalCraneNo }}，重新校验状态 / 能力 / 档期）</el-radio>
        <el-radio value="KEEP_ALTERNATIVE">保留替代设备（{{ reviewTask.craneNo }}，同样重新校验）</el-radio>
      </el-radio-group>
      <el-table :data="reviewCandidates" size="small" class="gap-top">
        <el-table-column prop="craneNo" label="设备" width="90" />
        <el-table-column prop="craneName" label="名称" min-width="110" />
        <el-table-column label="能力" width="150">
          <template #default="{ row }"><span :class="row.capacityMatch ? 'ok' : 'no'">{{ row.capacityText }}</span></template>
        </el-table-column>
        <el-table-column label="区域" min-width="180">
          <template #default="{ row }"><span :class="row.areaMatch ? 'ok' : 'no'">{{ row.areaText }}</span></template>
        </el-table-column>
        <el-table-column label="档期" min-width="150">
          <template #default="{ row }"><span :class="row.scheduleMatch ? 'ok' : 'no'">{{ row.scheduleText }}</span></template>
        </el-table-column>
      </el-table>
      <el-input v-model="reviewForm.note" class="gap-top" placeholder="复核说明（写入操作日志）" />
      <template #footer>
        <el-button @click="reviewDlg = false">取消</el-button>
        <el-button type="primary" @click="submitReview">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.kv-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 8px 14px; margin: 8px 2px; }
.kv-grid .k { font-size: 11px; color: var(--ink-dim); display: block; }
.kv-grid b { font-size: 13px; }

.impact-banner {
  margin-top: 8px; padding: 8px 12px; border-radius: 8px;
  background: var(--warn-wash, #fdf6ec); border: 1px solid var(--warn);
  font-size: 12.5px; color: var(--warn, #b88230);
}

.grid-3 { display: grid; grid-template-columns: 1.2fr 0.9fr 1fr; gap: 12px; align-items: start; }
@media (max-width: 1280px) { .grid-3 { grid-template-columns: 1fr; } }

.frozen-card { border: 1px solid var(--line-strong); border-left: 4px solid var(--fail); border-radius: 8px; padding: 8px 10px; margin-bottom: 8px; background: #fff; }
.fz-head { display: flex; align-items: center; justify-content: space-between; }
.fz-line { font-size: 12px; color: var(--ink-soft); margin-top: 4px; }
.fz-line.safety { color: var(--fail); }

.log-detail { font-size: 12.5px; margin: 2px 0; }

.maint-info { font-size: 11.5px; color: var(--warn, #b88230); }

.impact-summary { display: flex; gap: 12px; margin: 10px 0; }
.sum-item { flex: 1 1 0; text-align: center; border: 1px solid var(--line-strong); border-radius: 10px; padding: 10px; background: #fff; }
.sum-item b { font-size: 24px; display: block; color: var(--primary-deep); }
.sum-item span { font-size: 12px; color: var(--ink-dim); }
.sum-item.warn b { color: var(--fail); }
.sum-item.dim b { color: var(--ink-dim); }

.sub-panel { border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; margin-top: 10px; background: #fafbfc; }
.sub-title { font-size: 13px; font-weight: 700; margin-bottom: 8px; }
.sub-title.danger { color: var(--fail); }

.decision-group { display: flex; flex-direction: column; gap: 6px; margin: 4px 0 10px; }
.task-table { margin-top: 6px; }
.alt-collapse { margin-top: 8px; }
.safety-input { margin-top: 8px; }

.ok { color: var(--pass); font-size: 11.5px; }
.no { color: var(--fail); font-size: 11.5px; }

.impact-notes { margin-top: 12px; border-top: 1px dashed var(--line); padding-top: 10px; }
.impact-notes div { font-size: 12.5px; color: var(--ink-soft); line-height: 1.7; }

.gap-top { margin-top: 10px; }
.review-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 10px; }
</style>
