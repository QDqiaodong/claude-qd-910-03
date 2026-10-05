<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const props = defineProps({
  params: { type: Object, default: () => ({}) },
  tabKey: { type: String, default: '' }
})
const emit = defineEmits(['openTab'])

const board = ref({ berths: [], vessels: [], freeVessels: [], typeMeta: [], statusMeta: [], moveLogs: [] })
const loading = ref(false)
const filterType = ref('ALL')
const dragging = ref(null)
const dragOverKey = ref('')
const dialog = ref(false)
/** 移泊提交中：期间禁止再次拖动 / 重复提交，避免并发请求互相覆盖 */
const moving = ref(false)

const form = ref({
  berthNo: '', berthName: '', berthType: 'SLIPWAY',
  lengthM: 200, widthM: 36, capacityT: 90000, location: '厂区东岸', note: ''
})

const berths = computed(() => board.value.berths || [])
const shown = computed(() =>
  filterType.value === 'ALL' ? berths.value : berths.value.filter((b) => b.berthType === filterType.value)
)
const freeVessels = computed(() => board.value.freeVessels || [])
const moveLogs = computed(() => board.value.moveLogs || [])
/** 详情模式：从总览或列表点进来的船台分页 */
const detailId = computed(() => Number(props.params?.berthId || 0))
const detail = computed(() => berths.value.find((b) => b.id === detailId.value) || null)

function typeColor(type) {
  if (type === 'DRYDOCK') return 'var(--drydock)'
  if (type === 'WHARF') return 'var(--wharf)'
  return 'var(--slipway)'
}

function statusColor(status) {
  if (status === 'OCCUPIED') return 'var(--fail)'
  if (status === 'MAINTENANCE') return 'var(--warn)'
  return 'var(--pass)'
}

/** 后端 ISO 时间 → 页面可读格式 */
function fmtTime(value) {
  return String(value || '').replace('T', ' ').slice(0, 19) || '-'
}

function onDragStart(vessel, berth) {
  if (moving.value) return
  dragging.value = { vesselId: vessel.id, vesselName: vessel.vesselName, fromBerthId: berth ? berth.id : null }
}

function onDragEnd() {
  dragging.value = null
  dragOverKey.value = ''
}

/**
 * 移泊：作为一次完整操作提交服务端原子切换（原船台腾空 + 目标船台占用同事务生效）。
 * 不再先腾空再占用——目标被抢占 / 转检修 / 适配校验失败时，服务端保留原船台占位，
 * 这里只需重取看板恢复真实布局并说明原因。
 */
async function dropToBerth(berth) {
  dragOverKey.value = ''
  const drag = dragging.value
  dragging.value = null
  if (!drag || moving.value) return
  if (drag.fromBerthId === berth.id) return
  if (berth.status === 'MAINTENANCE') {
    ElMessage.error(`${berth.berthNo} 检修中，不允许排入新船`)
    return
  }
  if (berth.status === 'OCCUPIED') {
    ElMessage.error(`${berth.berthNo} 已占用，不允许再排入新船`)
    return
  }
  moving.value = true
  try {
    const result = await api.berth.move({
      vesselId: drag.vesselId,
      fromBerthId: drag.fromBerthId,
      toBerthId: berth.id
    })
    ElMessage.success(result?.message || `${drag.vesselName} 已排入 ${berth.berthNo}`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    // 成功：刷新总览 / 详情 / 待排区保持一致；失败：回退到服务端真实布局
    await load()
    moving.value = false
  }
}

async function dropToYard() {
  dragOverKey.value = ''
  const drag = dragging.value
  dragging.value = null
  if (!drag || moving.value) return
  if (!drag.fromBerthId) {
    ElMessage.info('这艘船本来就在待排区')
    return
  }
  moving.value = true
  try {
    await api.berth.release(drag.fromBerthId)
    ElMessage.success(`${drag.vesselName} 已出坞 / 让位`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    await load()
    moving.value = false
  }
}

async function transfer(berth, status) {
  loading.value = true
  try {
    await api.berth.status(berth.id, status)
    ElMessage.success(`${berth.berthNo} 状态已更新`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    // 并发下船台状态可能已被他人改变，无论成败都回退到服务端真实布局
    await load()
  }
}

async function releaseBerth(berth) {
  loading.value = true
  try {
    await api.berth.release(berth.id)
    ElMessage.success(`${berth.berthNo} 已腾空`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    await load()
  }
}

function openDetail(berth) {
  emit('openTab', { path: '/berth', title: berth.berthNo + ' ' + berth.berthName, sub: '船台', params: { berthId: berth.id } })
}

async function createBerth() {
  loading.value = true
  try {
    await api.berth.create(form.value)
    ElMessage.success('船台已建档')
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
    board.value = await api.berth.board()
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
      <span class="view-title" v-if="!detail">船台船坞 · 平面占位图</span>
      <span class="view-title" v-else>{{ detail.berthNo }} · {{ detail.berthName }}</span>
      <span class="view-tip" v-if="!detail">格子＝船台 / 船坞 / 码头；把船占位块按住拖到别的格子就是改船位（后端会校验尺度与承载能力）</span>
      <span class="view-tip" v-else>船台详情：尺度 / 承载 / 占用船舶 / 状态流转</span>
    </div>

    <!-- 详情模式 -->
    <section class="panel" v-if="detail">
      <div class="panel-title">船台信息</div>
      <div class="berth-detail">
        <div class="bd-cell">
          <div class="bd-k">编号 / 名称</div><div class="bd-v">{{ detail.berthNo }} · {{ detail.berthName }}</div>
          <div class="bd-k">类型</div><div class="bd-v">{{ detail.berthTypeName }}</div>
          <div class="bd-k">位置</div><div class="bd-v">{{ detail.location || '-' }}</div>
        </div>
        <div class="bd-cell">
          <div class="bd-k">有效尺度</div><div class="bd-v">{{ detail.lengthM }} m × {{ detail.widthM }} m</div>
          <div class="bd-k">承载能力</div><div class="bd-v">{{ detail.capacityT }} t</div>
          <div class="bd-k">状态</div>
          <div class="bd-v"><span class="tag-line"><i class="dot" :style="{ background: statusColor(detail.status) }"></i>{{ detail.statusName }}</span></div>
        </div>
        <div class="bd-cell">
          <div class="bd-k">占用船舶</div><div class="bd-v">{{ detail.vesselName || '（空）' }}</div>
          <div class="bd-k">备注</div><div class="bd-v">{{ detail.note || '-' }}</div>
          <div class="bd-actions">
            <el-button size="small" type="primary" plain @click="transfer(detail, 'MAINTENANCE')">转检修</el-button>
            <el-button size="small" type="primary" plain @click="transfer(detail, 'IDLE')">转空闲</el-button>
            <el-button size="small" type="primary" plain v-if="detail.status === 'OCCUPIED'" @click="releaseBerth(detail)">出坞</el-button>
          </div>
        </div>
      </div>
    </section>

    <!-- 占位图 -->
    <section class="panel" v-if="!detail">
      <div class="panel-title">
        船台平面占位图
        <span class="view-tip">共 {{ berths.length }} 个泊位 · 占用 {{ berths.filter((b) => b.status === 'OCCUPIED').length }} 个</span>
        <el-button size="small" type="primary" plain style="margin-left:auto" @click="dialog = true">新增船台</el-button>
      </div>

      <div class="chip-row">
        <span class="chip" :class="{ on: filterType === 'ALL' }" @click="filterType = 'ALL'">全部</span>
        <span
          v-for="t in board.typeMeta" :key="t.code" class="chip"
          :class="{ on: filterType === t.code }" @click="filterType = t.code"
        >{{ t.name }} · {{ t.count }}</span>
      </div>

      <div class="berth-grid">
        <div
          v-for="berth in shown" :key="berth.id"
          class="berth-cell"
          :class="{ over: dragOverKey === 'b' + berth.id, busy: berth.status !== 'IDLE' }"
          :style="{ borderTopColor: typeColor(berth.berthType) }"
          @dragover.prevent="dragOverKey = 'b' + berth.id"
          @dragleave="dragOverKey = ''"
          @drop="dropToBerth(berth)"
        >
          <div class="bc-head">
            <span class="bc-no" @click="openDetail(berth)">{{ berth.berthNo }}</span>
            <span class="bc-name">{{ berth.berthName }}</span>
            <span class="bc-status" :style="{ background: statusColor(berth.status) }">{{ berth.statusName }}</span>
          </div>
          <div class="bc-scale">{{ berth.lengthM }}×{{ berth.widthM }} m · 承载 {{ berth.capacityT }} t</div>
          <div class="bc-slot">
            <div
              v-if="berth.vesselId"
              class="ship-block"
              :style="{ borderLeftColor: typeColor(berth.berthType) }"
              :draggable="!moving"
              @dragstart="onDragStart({ id: berth.vesselId, vesselName: berth.vesselName }, berth)"
              @dragend="onDragEnd"
            >
              <div class="sb-name">{{ berth.vesselName }}</div>
              <div class="sb-meta">{{ berth.location || '在台' }} · 按住可拖走</div>
            </div>
            <div v-else class="slot-empty">空格子 · 可拖船进来</div>
          </div>
        </div>
      </div>
    </section>

    <!-- 移泊记录：原船台 → 目标船台 · 操作时间 · 结果，只追加可追溯 -->
    <section class="panel" v-if="!detail">
      <div class="panel-title">
        移泊记录
        <span class="view-tip">每一次移泊尝试都留痕：原船台 → 目标船台 · 操作时间 · 结果与原因</span>
      </div>
      <el-table :data="moveLogs" size="small" v-if="moveLogs.length">
        <el-table-column prop="vesselName" label="船舶" width="110" />
        <el-table-column label="原船台" width="100">
          <template #default="{ row }">{{ row.fromBerthNo || '待排区' }}</template>
        </el-table-column>
        <el-table-column prop="toBerthNo" label="目标船台" width="100" />
        <el-table-column label="操作时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="结果" width="80">
          <template #default="{ row }">
            <span class="tag-line"><i class="dot" :style="{ background: row.result === 'SUCCESS' ? 'var(--pass)' : 'var(--fail)' }"></i>{{ row.resultName }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="说明" show-overflow-tooltip />
      </el-table>
      <div class="empty-tip" v-else>还没有移泊记录</div>
    </section>

    <!-- 待排船舶 -->
    <section class="panel">
      <div class="panel-title">
        待排船舶（未上台）
        <span class="view-tip">拖到上面格子＝排船；把格子里的船拖回这里＝出坞让位</span>
      </div>
      <div
        class="yard"
        :class="{ over: dragOverKey === 'yard' }"
        @dragover.prevent="dragOverKey = 'yard'"
        @dragleave="dragOverKey = ''"
        @drop="dropToYard"
      >
        <div
          v-for="vessel in freeVessels" :key="vessel.id"
          class="ship-block yard-block"
          :draggable="!moving"
          @dragstart="onDragStart(vessel, null)"
          @dragend="onDragEnd"
          @click="emit('openTab', { path: '/vessel', title: vessel.vesselNo + ' ' + vessel.vesselName, params: { vesselId: vessel.id } })"
        >
          <div class="sb-name">{{ vessel.vesselName }}</div>
          <div class="sb-meta">{{ vessel.vesselNo }} · {{ vessel.lengthM }}×{{ vessel.widthM }} m · {{ vessel.displacementT }} t</div>
        </div>
        <div class="empty-tip" v-if="!freeVessels.length">全部船舶都已上台</div>
      </div>
    </section>

    <el-dialog v-model="dialog" title="新增船台 / 船坞" width="520px">
      <el-form label-width="96px">
        <el-form-item label="船台编号"><el-input v-model="form.berthNo" placeholder="如 BT-04" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.berthName" placeholder="如 四号船台" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.berthType">
            <el-option v-for="t in board.typeMeta" :key="t.code" :label="t.name" :value="t.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="有效长度"><el-input-number v-model="form.lengthM" :min="1" /></el-form-item>
        <el-form-item label="有效宽度"><el-input-number v-model="form.widthM" :min="1" /></el-form-item>
        <el-form-item label="承载能力"><el-input-number v-model="form.capacityT" :min="1" /></el-form-item>
        <el-form-item label="位置"><el-input v-model="form.location" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="createBerth">建档</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.berth-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: 12px;
}

.berth-cell {
  border: 1px solid var(--line-strong);
  border-top: 4px solid var(--slipway);
  border-radius: 10px;
  padding: 10px;
  background: #fbfcfc;
  transition: box-shadow .18s ease, background .18s ease;
}
.berth-cell.over { box-shadow: 0 0 0 2px var(--primary) inset; background: var(--primary-wash); }
.berth-cell.busy { background: #f6f8f8; }

.bc-head { display: flex; align-items: center; gap: 6px; }
.bc-no { font-weight: 800; color: var(--primary-deep); cursor: pointer; text-decoration: underline dotted; }
.bc-name { font-size: 12px; color: var(--ink-soft); flex: 1 1 auto; }
.bc-status {
  font-size: 10.5px; color: #fff; padding: 1px 7px; border-radius: 999px;
}
.bc-scale { font-size: 11px; color: var(--ink-dim); margin-top: 4px; }

.bc-slot { margin-top: 8px; min-height: 54px; }
.slot-empty {
  border: 1px dashed var(--line-strong);
  border-radius: 8px;
  padding: 14px 8px;
  text-align: center;
  color: var(--ink-dim);
  font-size: 11.5px;
}

.ship-block {
  border: 1px solid var(--line-strong);
  border-left: 4px solid var(--slipway);
  border-radius: 8px;
  padding: 7px 9px;
  background: #fff;
  cursor: grab;
  box-shadow: 0 3px 8px rgba(38, 50, 56, 0.08);
}
.ship-block:active { cursor: grabbing; }
.sb-name { font-weight: 700; font-size: 12.5px; }
.sb-meta { font-size: 10.5px; color: var(--ink-dim); margin-top: 2px; }

.yard {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 10px;
  min-height: 70px;
  border: 1px dashed var(--line-strong);
  border-radius: 10px;
  padding: 10px;
  background: #fafbfb;
}
.yard.over { background: var(--primary-wash); box-shadow: 0 0 0 2px var(--primary) inset; }
.yard-block { cursor: grab; }

.berth-detail { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.bd-k { font-size: 11px; color: var(--ink-dim); margin-top: 6px; }
.bd-v { font-size: 13px; font-weight: 600; color: var(--ink); }
.bd-actions { display: flex; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
</style>
