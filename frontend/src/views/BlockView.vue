<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const props = defineProps({
  params: { type: Object, default: () => ({}) },
  tabKey: { type: String, default: '' }
})
const emit = defineEmits(['openTab'])

const board = ref({ blocks: [], statusMeta: [], typeMeta: [], vessels: [], statuses: [], erections: [] })
const detail = ref(null)
const loading = ref(false)
const filterStatus = ref('ALL')
const dialog = ref(false)

const form = ref({
  vesselId: null, blockName: '', blockType: 'BOTTOM', weightT: 120,
  segSeq: 1, workshop: '内场一跨', note: ''
})

const blocks = computed(() => board.value.blocks || [])
const erectionOf = (blockId) => (board.value.erections || []).find((e) => e.blockId === blockId) || null
const shown = computed(() =>
  filterStatus.value === 'ALL' ? blocks.value : blocks.value.filter((b) => b.status === filterStatus.value)
)
const blockId = computed(() => Number(props.params?.blockId || 0))

function statusColor(status) {
  return {
    PREFAB: 'var(--steel)',
    PAINT: 'var(--warn)',
    ERECTION: 'var(--slipway)',
    CLOSURE: 'var(--drydock)',
    SURVEY: 'var(--pass)'
  }[status] || 'var(--steel)'
}

function openDetail(block) {
  emit('openTab', {
    path: '/block',
    title: block.blockCode,
    sub: block.blockName,
    params: { blockId: block.id }
  })
}

function openVessel(block) {
  if (!block.vesselId) return
  emit('openTab', {
    path: '/vessel',
    title: block.vesselNo + ' ' + (block.vesselName || ''),
    params: { vesselId: block.vesselId }
  })
}

function openCrane(craneId) {
  if (!craneId) return
  emit('openTab', { path: '/crane', title: '设备详情', params: { craneId } })
}

async function advance(block) {
  loading.value = true
  try {
    const updated = await api.block.advance(block.id)
    ElMessage.success(`${updated.blockCode} 已推进到「${updated.statusName}」`)
    await reload()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function finishPaint(block) {
  loading.value = true
  try {
    const updated = await api.block.paint(block.id)
    ElMessage.success(`${updated.blockCode} 涂装已报完工`)
    await reload()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function createBlock() {
  loading.value = true
  try {
    const created = await api.block.create(form.value)
    ElMessage.success(`分段已建档，编码由生成器给出：${created.blockCode}`)
    dialog.value = false
    await loadBoard()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function loadBoard() {
  board.value = await api.block.board()
}

async function loadDetail() {
  if (!blockId.value) {
    detail.value = null
    return
  }
  detail.value = await api.block.detail(blockId.value)
}

async function reload() {
  await Promise.all([loadBoard(), loadDetail()])
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

watch(blockId, () => {
  if (blockId.value) {
    loadDetail().catch((e) => ElMessage.error(e.message))
  } else {
    detail.value = null
  }
})

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title" v-if="!blockId">船体分段 · 卡片墙</span>
      <span class="view-title" v-else>{{ detail?.block?.blockCode || '分段' }} · 详情</span>
      <span class="view-tip" v-if="!blockId">点卡片会在标签条上再开一个详情分页，同一个视图可以开多个实例</span>
      <span class="view-tip" v-else>状态只能逐级推进：预制 → 涂装 → 搭载 → 合拢 → 交验</span>
    </div>

    <!-- 详情分页 -->
    <div v-if="blockId && detail">
      <section class="panel">
        <div class="panel-title">
          {{ detail.block.blockName }}
          <span class="tag-line"><i class="dot" :style="{ background: statusColor(detail.block.status) }"></i>{{ detail.block.statusName }}</span>
          <el-button size="small" type="primary" style="margin-left:auto" @click="advance(detail.block)">推进一级状态</el-button>
          <el-button size="small" type="primary" plain @click="finishPaint(detail.block)">涂装报完工</el-button>
        </div>
        <div class="bd-grid">
          <div><span class="k">分段编码</span><b>{{ detail.block.blockCode }}</b></div>
          <div><span class="k">所属船</span><b>{{ detail.block.vesselNo }} {{ detail.block.vesselName }}</b></div>
          <div><span class="k">类型</span><b>{{ detail.block.blockTypeName }}</b></div>
          <div><span class="k">重量</span><b>{{ detail.block.weightT }} t</b></div>
          <div><span class="k">搭载顺序号</span><b>{{ detail.block.segSeq }}</b></div>
          <div><span class="k">涂装</span><b>{{ detail.block.painted ? '已完成 ' + (detail.block.paintFinishDate || '') : '未完成' }}</b></div>
          <div><span class="k">场地</span><b>{{ detail.block.workshop }}</b></div>
          <div><span class="k">未闭环遗留项</span><b :style="{ color: detail.openPunchCount ? 'var(--fail)' : 'var(--pass)' }">{{ detail.openPunchCount }} 条</b></div>
        </div>
      </section>

      <section class="panel">
        <div class="panel-title">
          该分段的搭载排程（{{ (detail.erections || []).length }}）
          <span class="view-tip">待复核任务不能开始吊装；历史吊次归属设备永不倒改</span>
        </div>
        <el-table :data="detail.erections || []" size="small" v-if="(detail.erections || []).length">
          <el-table-column prop="erectionNo" label="任务号" width="150" />
          <el-table-column prop="berthNo" label="船台" width="80" />
          <el-table-column prop="planDate" label="档期" width="105" />
          <el-table-column label="排程设备" min-width="170">
            <template #default="{ row }">
              <el-tag v-if="row.reviewPending" type="warning" size="small">待复核 · 原 {{ row.originalCraneNo }}</el-tag>
              <span v-else-if="row.craneNo" class="crane-link" @click="openCrane(row.craneId)">{{ row.craneNo }} {{ row.craneName }}</span>
              <span v-else class="view-tip">未排程</span>
            </template>
          </el-table-column>
          <el-table-column prop="statusName" label="任务状态" width="90" />
        </el-table>
        <div class="empty-tip" v-else>还没有搭载任务</div>
      </section>

      <div class="grid-2">
        <section class="panel">
          <div class="panel-title">该分段的吊次（{{ detail.lifts.length }}）</div>
          <el-table :data="detail.lifts" size="small" v-if="detail.lifts.length">
            <el-table-column prop="liftNo" label="吊次号" width="140" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="{ SCHEDULED: 'info', IN_PROGRESS: 'warning', DONE: 'success' }[row.status]">
                  {{ row.statusName }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="craneName" label="设备" min-width="120" />
            <el-table-column prop="currentStage" label="阶段" width="80" />
            <el-table-column prop="liftWeightT" label="重量(t)" width="85" />
            <el-table-column prop="ratedLoadT" label="额定(t)" width="80" />
            <el-table-column label="载荷率" width="90">
              <template #default="{ row }">
                <span :style="{ color: row.overload ? 'var(--fail)' : row.nearFull ? 'var(--warn)' : 'var(--pass)' }">
                  {{ row.liftWeightT ? row.loadRate + '%' : '-' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="liftTime" label="时间" width="160" />
          </el-table>
          <div class="empty-tip" v-else>还没有吊次记录</div>
        </section>

        <section class="panel">
          <div class="panel-title">该分段的报验单（{{ detail.surveys.length }}）</div>
          <el-table :data="detail.surveys" size="small" v-if="detail.surveys.length">
            <el-table-column prop="surveyNo" label="单号" width="150" />
            <el-table-column prop="surveyItemName" label="项目" />
            <el-table-column prop="authorityName" label="机构" width="100" />
            <el-table-column label="结论" width="90">
              <template #default="{ row }">
                <span class="stamp" :class="'stamp-' + row.result?.toLowerCase()">{{ row.stamp }}</span>
              </template>
            </el-table-column>
          </el-table>
          <div class="empty-tip" v-else>还没有报验记录</div>
        </section>
      </div>

      <section class="panel">
        <div class="panel-title">该分段的遗留项（{{ detail.punchItems.length }}）</div>
        <el-table :data="detail.punchItems" size="small" v-if="detail.punchItems.length">
          <el-table-column prop="punchNo" label="编号" width="180" />
          <el-table-column prop="title" label="标题" />
          <el-table-column prop="severityName" label="严重度" width="90" />
          <el-table-column prop="owner" label="责任人" width="100" />
          <el-table-column prop="statusName" label="状态" width="90" />
          <el-table-column prop="dueDate" label="期限" width="110" />
        </el-table>
        <div class="empty-tip" v-else>没有遗留项，可以放心交验</div>
      </section>
    </div>

    <!-- 卡片墙 -->
    <div v-else>
      <section class="panel">
        <div class="panel-title">
          分段卡片墙
          <span class="view-tip">共 {{ blocks.length }} 段 · 合计 {{ blocks.reduce((s, b) => s + Number(b.weightT || 0), 0).toFixed(1) }} t</span>
          <el-button size="small" type="primary" plain style="margin-left:auto" @click="dialog = true">新增分段</el-button>
        </div>

        <div class="chip-row">
          <span class="chip" :class="{ on: filterStatus === 'ALL' }" @click="filterStatus = 'ALL'">全部</span>
          <span
            v-for="s in board.statusMeta" :key="s.code" class="chip"
            :class="{ on: filterStatus === s.code }" @click="filterStatus = s.code"
          >{{ s.name }} · {{ s.count }}</span>
        </div>

        <div class="card-wall">
          <div
            v-for="block in shown" :key="block.id"
            class="block-card"
            :style="{ borderTopColor: statusColor(block.status) }"
            @click="openDetail(block)"
          >
            <div class="bc-top">
              <span class="bc-code">{{ block.blockCode }}</span>
              <span class="badge" :style="{ background: statusColor(block.status) }">{{ block.statusName }}</span>
            </div>
            <div class="bc-name">{{ block.blockName }}</div>
            <div class="bc-line">{{ block.blockTypeName }} · {{ block.weightT }} t · 顺序 {{ block.segSeq }}</div>
            <div class="bc-line dim" @click.stop="openVessel(block)">{{ block.vesselNo }} {{ block.vesselName }}</div>
            <div class="bc-bar"><i :style="{ width: block.progress + '%', background: statusColor(block.status) }"></i></div>
            <div class="bc-crane" v-if="erectionOf(block.id)">
              <el-tag v-if="erectionOf(block.id).reviewPending" type="warning" size="small">吊装待复核 · 原 {{ erectionOf(block.id).originalCraneNo }}</el-tag>
              <template v-else>
                <span class="bc-crane-no" @click.stop="openCrane(erectionOf(block.id).craneId)">
                  {{ erectionOf(block.id).craneNo }}
                </span>
                <span class="bc-crane-date">{{ erectionOf(block.id).planDate }}</span>
              </template>
            </div>
            <div class="bc-foot">
              <span class="tag-line">{{ block.painted ? '已涂装' : '未涂装' }}</span>
              <span class="tag-line">{{ block.workshop }}</span>
            </div>
          </div>
          <div class="empty-tip" v-if="!shown.length">该状态下没有分段</div>
        </div>
      </section>

      <section class="panel">
        <div class="panel-title">按状态统计（手写 JPQL）</div>
        <el-table :data="board.statuses" size="small">
          <el-table-column prop="statusName" label="状态" width="120" />
          <el-table-column prop="count" label="段数" width="90" />
          <el-table-column prop="weightSum" label="总重量(t)" width="120" />
          <el-table-column prop="avgWeight" label="平均重量(t)" />
        </el-table>
      </section>
    </div>

    <el-dialog v-model="dialog" title="新增分段（编码由后端生成器给出）" width="520px">
      <el-form label-width="100px">
        <el-form-item label="所属船舶">
          <el-select v-model="form.vesselId" style="width:100%">
            <el-option v-for="v in board.vessels" :key="v.id" :label="v.vesselNo + ' ' + v.vesselName" :value="v.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="分段名称"><el-input v-model="form.blockName" placeholder="如 机舱底部" /></el-form-item>
        <el-form-item label="分段类型">
          <el-select v-model="form.blockType">
            <el-option v-for="t in board.typeMeta" :key="t.code" :label="t.name" :value="t.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="重量(t)"><el-input-number v-model="form.weightT" :min="1" /></el-form-item>
        <el-form-item label="搭载顺序号"><el-input-number v-model="form.segSeq" :min="1" /></el-form-item>
        <el-form-item label="建造场地"><el-input v-model="form.workshop" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="createBlock">建档</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.card-wall {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
  gap: 12px;
}

.block-card {
  border: 1px solid var(--line-strong);
  border-top: 4px solid var(--steel);
  border-radius: 10px;
  padding: 10px 11px;
  background: #fff;
  cursor: pointer;
  transition: transform .16s ease, box-shadow .16s ease;
}
.block-card:hover { transform: translateY(-3px); box-shadow: 0 10px 20px rgba(38, 50, 56, 0.14); }

.bc-top { display: flex; align-items: center; gap: 6px; }
.bc-code { font-weight: 800; font-size: 13px; color: var(--primary-deep); flex: 1 1 auto; }
.badge { font-size: 10.5px; color: #fff; padding: 1px 8px; border-radius: 999px; }
.bc-name { font-size: 12.5px; font-weight: 600; margin-top: 6px; }
.bc-line { font-size: 11px; color: var(--ink-soft); margin-top: 3px; }
.bc-line.dim { color: var(--ink-dim); text-decoration: underline dotted; cursor: pointer; }
.bc-bar { height: 5px; background: var(--line); border-radius: 3px; margin-top: 8px; overflow: hidden; }
.bc-bar i { display: block; height: 100%; border-radius: 3px; }
.bc-crane { display: flex; align-items: center; justify-content: space-between; margin-top: 7px; font-size: 11px; }
.bc-crane-no { font-weight: 800; color: var(--primary-deep); text-decoration: underline dotted; cursor: pointer; }
.bc-crane-date { color: var(--ink-dim); }
.crane-link { color: var(--primary-deep); cursor: pointer; text-decoration: underline dotted; }
.bc-foot { display: flex; gap: 6px; margin-top: 8px; flex-wrap: wrap; }

.bd-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 8px 14px;
}
.bd-grid .k { font-size: 11px; color: var(--ink-dim); display: block; }
.bd-grid b { font-size: 13px; }

.stamp {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 4px;
  border: 1.5px solid currentColor;
  font-weight: 800;
  font-size: 11px;
  transform: rotate(-4deg);
}
.stamp-pass { color: var(--pass); }
.stamp-concession { color: var(--warn); }
.stamp-fail { color: var(--fail); }
</style>
