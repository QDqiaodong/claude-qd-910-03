<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const props = defineProps({
  params: { type: Object, default: () => ({}) },
  tabKey: { type: String, default: '' }
})
const emit = defineEmits(['openTab'])

const board = ref({ surveys: [], punchItems: [], blocks: [], verdicts: [], resultMeta: [], punchStatusMeta: [] })
const detail = ref(null)
const loading = ref(false)
const filterResult = ref('ALL')
const dialog = ref(false)

const form = ref({
  blockId: null, surveyItem: 'WELD', authority: 'CCS', inspector: '钱广安',
  result: 'PASS', remark: '', punchItems: []
})

const surveys = computed(() => board.value.surveys || [])
const punchItems = computed(() => board.value.punchItems || [])
const shownSurveys = computed(() =>
  filterResult.value === 'ALL' ? surveys.value : surveys.value.filter((s) => s.result === filterResult.value)
)
const surveyId = computed(() => Number(props.params?.surveyId || 0))

const authorities = [
  { code: 'CCS', name: '中国船级社' }, { code: 'ABS', name: '美国船级社' },
  { code: 'LR', name: '英国劳氏' }, { code: 'DNV', name: '挪威船级社' },
  { code: 'BV', name: '法国必维' }, { code: 'OWNER', name: '船东代表' }
]
const items = [
  { code: 'WELD', name: '焊缝探伤' }, { code: 'DIMENSION', name: '主尺度' },
  { code: 'COATING', name: '涂装膜厚' }, { code: 'OUTFITTING', name: '舾装完整性' },
  { code: 'TIGHTNESS', name: '舱室密性' }
]
const results = [
  { code: 'PASS', name: '合格' }, { code: 'CONCESSION', name: '让步' }, { code: 'FAIL', name: '不合格' }
]

function addPunch() {
  form.value.punchItems.push({ title: '', description: '', severity: 'MINOR', owner: '王立柱' })
}

function removePunch(index) {
  form.value.punchItems.splice(index, 1)
}

function stampClass(result) {
  return 'stamp-' + String(result || '').toLowerCase()
}

function openSurvey(survey) {
  emit('openTab', {
    path: '/survey',
    title: survey.surveyNo,
    sub: survey.blockCode,
    params: { surveyId: survey.id }
  })
}

function openBlock(code, id) {
  if (!id) return
  emit('openTab', { path: '/block', title: code, params: { blockId: id } })
}

function punchOf(surveyId) {
  return punchItems.value.filter((p) => p.surveyId === surveyId)
}

async function advancePunch(item) {
  loading.value = true
  try {
    const updated = await api.survey.advancePunch(item.id)
    ElMessage.success(`${updated.punchNo} → ${updated.statusName}`)
    await reload()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function submitSurvey() {
  if (form.value.result === 'FAIL' && !form.value.punchItems.length) {
    ElMessage.error('判不合格必须至少开一条遗留项')
    return
  }
  loading.value = true
  try {
    const payload = { ...form.value }
    if (payload.result !== 'FAIL') payload.punchItems = []
    const created = await api.survey.create(payload)
    ElMessage.success(`报验单已登记，单号 ${created.surveyNo}`)
    dialog.value = false
    form.value.punchItems = []
    await loadBoard()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function loadBoard() {
  board.value = await api.survey.board()
}

async function loadDetail() {
  detail.value = surveyId.value ? await api.survey.detail(surveyId.value) : null
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

watch(surveyId, () => {
  if (surveyId.value) {
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
      <span class="view-title" v-if="!surveyId">报验与遗留项 · 左右双栏对照</span>
      <span class="view-title" v-else>{{ detail?.survey?.surveyNo || '报验单' }} · 判定</span>
      <span class="view-tip">左报验项、右遗留项清单；结论三选一，判不合格必须开遗留项</span>
      <el-button size="small" type="primary" style="margin-left:auto" @click="dialog = true">登记报验单</el-button>
    </div>

    <!-- 详情分页：大印章 -->
    <section class="panel" v-if="surveyId && detail">
      <div class="detail-wrap">
        <div class="stamp-big" :class="stampClass(detail.survey.result)">{{ detail.survey.stamp }}</div>
        <div class="detail-info">
          <div><span class="k">报验单号</span><b>{{ detail.survey.surveyNo }}</b></div>
          <div><span class="k">报验项目</span><b>{{ detail.survey.surveyItemName }}</b></div>
          <div><span class="k">被检分段</span>
            <b class="link" @click="openBlock(detail.survey.blockCode, detail.survey.blockId)">{{ detail.survey.blockCode }}</b>
          </div>
          <div><span class="k">检验机构</span><b>{{ detail.survey.authorityName }}</b></div>
          <div><span class="k">检验员</span><b>{{ detail.survey.inspector }}</b></div>
          <div><span class="k">报验时间</span><b>{{ detail.survey.surveyDate }}</b></div>
          <div class="full"><span class="k">结论意见</span><b>{{ detail.survey.remark }}</b></div>
        </div>
      </div>
      <div class="panel-title" style="margin-top:12px">本单遗留项（{{ detail.punchItems.length }}）</div>
      <el-table :data="detail.punchItems" size="small" v-if="detail.punchItems.length">
        <el-table-column prop="punchNo" label="编号" width="180" />
        <el-table-column prop="title" label="标题" />
        <el-table-column prop="severityName" label="严重度" width="90" />
        <el-table-column prop="owner" label="责任人" width="100" />
        <el-table-column prop="statusName" label="状态" width="90" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="advancePunch(row)">推进</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="empty-tip" v-else>本单没有遗留项</div>
    </section>

    <!-- 双栏对照 -->
    <div class="dual" v-else>
      <section class="panel">
        <div class="panel-title">
          报验项
          <span class="view-tip">共 {{ surveys.length }} 张报验单</span>
        </div>
        <div class="chip-row">
          <span class="chip" :class="{ on: filterResult === 'ALL' }" @click="filterResult = 'ALL'">全部</span>
          <span
            v-for="r in board.resultMeta" :key="r.code" class="chip"
            :class="{ on: filterResult === r.code }" @click="filterResult = r.code"
          >{{ r.name }} · {{ r.count }}</span>
        </div>
        <div class="survey-list">
          <div v-for="survey in shownSurveys" :key="survey.id" class="survey-card" @click="openSurvey(survey)">
            <div class="sc-top">
              <b>{{ survey.surveyNo }}</b>
              <span class="stamp" :class="stampClass(survey.result)">{{ survey.stamp }}</span>
            </div>
            <div class="sc-line">{{ survey.blockCode }} · {{ survey.surveyItemName }}</div>
            <div class="sc-line dim">{{ survey.authorityName }} · {{ survey.inspector }} · {{ survey.surveyDate }}</div>
            <div class="sc-line dim2" v-if="punchOf(survey.id).length">遗留项 {{ punchOf(survey.id).length }} 条，未闭环 {{ punchOf(survey.id).filter((p) => p.status !== 'CLOSED').length }} 条</div>
          </div>
          <div class="empty-tip" v-if="!shownSurveys.length">该结论下没有报验单</div>
        </div>
      </section>

      <section class="panel">
        <div class="panel-title">
          遗留项清单
          <span class="view-tip">未闭环 {{ punchItems.filter((p) => p.status !== 'CLOSED').length }} 条 / 共 {{ punchItems.length }} 条</span>
        </div>
        <div class="punch-list">
          <div v-for="item in punchItems" :key="item.id" class="punch-card" :class="{ closed: item.status === 'CLOSED' }">
            <div class="pc-top">
              <b>{{ item.punchNo }}</b>
              <span class="tag-line" :class="'sev-' + item.severity?.toLowerCase()">{{ item.severityName }}</span>
              <span class="tag-line">{{ item.statusName }}</span>
            </div>
            <div class="pc-title">{{ item.title }}</div>
            <div class="pc-line dim">{{ item.blockCode }} · {{ item.description }}</div>
            <div class="pc-line dim">责任人 {{ item.owner }} · 期限 {{ item.dueDate || '-' }}
              <span class="overdue" v-if="item.overdue">已超期</span>
            </div>
            <div class="pc-actions" v-if="item.status !== 'CLOSED'">
              <el-button size="small" type="primary" plain @click="advancePunch(item)">推进闭环</el-button>
            </div>
          </div>
          <div class="empty-tip" v-if="!punchItems.length">目前没有遗留项</div>
        </div>
      </section>
    </div>

    <el-dialog v-model="dialog" title="登记报验单（单号由后端生成器给出）" width="560px">
      <el-form label-width="100px">
        <el-form-item label="被检分段">
          <el-select v-model="form.blockId" style="width:100%">
            <el-option v-for="b in board.blocks" :key="b.id" :label="b.blockCode + ' ' + b.blockName" :value="b.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="报验项目">
          <el-select v-model="form.surveyItem">
            <el-option v-for="i in items" :key="i.code" :label="i.name" :value="i.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="检验机构">
          <el-select v-model="form.authority">
            <el-option v-for="a in authorities" :key="a.code" :label="a.name" :value="a.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="检验员"><el-input v-model="form.inspector" /></el-form-item>
        <el-form-item label="结论">
          <el-radio-group v-model="form.result">
            <el-radio v-for="r in results" :key="r.code" :value="r.code">{{ r.name }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="意见"><el-input v-model="form.remark" /></el-form-item>
      </el-form>

      <div class="punch-editor">
        <div class="pe-head">
          遗留项
          <span class="view-tip">判「不合格」时至少要开一条</span>
          <el-button size="small" type="primary" plain style="margin-left:auto" @click="addPunch">添加一条</el-button>
        </div>
        <div v-for="(item, index) in form.punchItems" :key="index" class="pe-item">
          <el-input v-model="item.title" placeholder="标题，如 涂层膜厚不足" style="flex:1 1 auto" />
          <el-select v-model="item.severity" style="width:110px">
            <el-option label="一般" value="MINOR" />
            <el-option label="重要" value="MAJOR" />
            <el-option label="严重" value="CRITICAL" />
          </el-select>
          <el-input v-model="item.owner" placeholder="责任人" style="width:110px" />
          <el-button size="small" type="danger" plain @click="removePunch(index)">删</el-button>
        </div>
        <div class="empty-tip" v-if="!form.punchItems.length">判合格 / 让步可以不带遗留项</div>
      </div>

      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submitSurvey">提交报验</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.dual { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px; align-items: start; }

.survey-list, .punch-list { display: flex; flex-direction: column; gap: 8px; max-height: 560px; overflow-y: auto; }

.survey-card {
  border: 1px solid var(--line-strong);
  border-radius: 10px;
  padding: 9px 11px;
  background: #fff;
  cursor: pointer;
  transition: box-shadow .16s ease, transform .16s ease;
}
.survey-card:hover { transform: translateY(-2px); box-shadow: 0 8px 18px rgba(38, 50, 56, 0.12); }
.sc-top { display: flex; align-items: center; gap: 8px; }
.sc-top b { font-size: 12.5px; }
.sc-line { font-size: 11.5px; color: var(--ink-soft); margin-top: 3px; }
.sc-line.dim { color: var(--ink-dim); }
.sc-line.dim2 { color: var(--warn); }

.punch-card {
  border: 1px solid var(--line-strong);
  border-left: 4px solid var(--fail);
  border-radius: 10px;
  padding: 9px 11px;
  background: #fff;
}
.punch-card.closed { border-left-color: var(--pass); background: #fafdfa; }
.pc-top { display: flex; align-items: center; gap: 6px; }
.pc-top b { font-size: 12px; }
.pc-title { font-size: 12.5px; font-weight: 600; margin-top: 4px; }
.pc-line { font-size: 11px; color: var(--ink-soft); margin-top: 2px; }
.pc-line.dim { color: var(--ink-dim); }
.pc-actions { margin-top: 6px; }
.overdue { color: var(--fail); font-weight: 700; margin-left: 6px; }

.sev-critical { background: #fdecea; color: var(--fail); }
.sev-major { background: #fff6e0; color: var(--warn); }
.sev-minor { background: var(--primary-wash); color: var(--ink-soft); }

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

.stamp-big {
  width: 92px; height: 92px;
  border: 3px double currentColor;
  border-radius: 50%;
  display: grid; place-items: center;
  font-size: 20px; font-weight: 900;
  transform: rotate(-12deg);
  flex: 0 0 auto;
}
.stamp-big.stamp-pass { color: var(--pass); }
.stamp-big.stamp-concession { color: var(--warn); }
.stamp-big.stamp-fail { color: var(--fail); }

.detail-wrap { display: flex; gap: 18px; align-items: center; }
.detail-info { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px 14px; flex: 1 1 auto; }
.detail-info .full { grid-column: 1 / -1; }
.detail-info .k { font-size: 11px; color: var(--ink-dim); display: block; }
.detail-info b { font-size: 13px; }
.link { color: var(--primary-deep); text-decoration: underline dotted; cursor: pointer; }

.punch-editor { border-top: 1px dashed var(--line-strong); margin-top: 6px; padding-top: 8px; }
.pe-head { display: flex; align-items: center; gap: 8px; font-size: 12.5px; font-weight: 700; }
.pe-item { display: flex; gap: 6px; margin-top: 8px; }
</style>
