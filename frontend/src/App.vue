<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from './router'
import api from './api'

import BerthView from './views/BerthView.vue'
import BlockView from './views/BlockView.vue'
import CraneView from './views/CraneView.vue'
import ErectionView from './views/ErectionView.vue'
import SurveyView from './views/SurveyView.vue'
import VesselView from './views/VesselView.vue'
import LaunchView from './views/LaunchView.vue'

// ------------------------------------------------------------------
// 多标签工作区（MDI）：没有左侧栏、没有顶部菜单导航树。
// 顶部只有一条可开可关的标签条，最左边「总览」固定不可关；
// 点列表里的分段 / 船舶会在标签条上再开一个分页，同一个视图可以开多个实例。
// 每个分页的 DOM 都留在文档里（v-show 切换），所以各自的滚动位置与填写内容都不丢。
// ------------------------------------------------------------------

const viewMap = {
  '/berth': BerthView,
  '/block': BlockView,
  '/erection': ErectionView,
  '/crane': CraneView,
  '/survey': SurveyView,
  '/vessel': VesselView,
  '/launch': LaunchView
}

const titleMap = {
  '/berth': '船台船坞',
  '/block': '分段',
  '/erection': '搭载吊装',
  '/crane': '起重设备',
  '/survey': '报验遗留',
  '/vessel': '船舶合同',
  '/launch': '下水放行'
}

const seq = ref(0)
const tabs = ref([
  { key: 'overview', title: '总览', sub: '工作区', path: '/overview', params: {}, fixed: true }
])
const activeKey = ref('overview')
const stripOpen = ref(true)
const overview = ref(null)
const route = computed(() => router.currentRoute.value)

const activeTab = computed(() => tabs.value.find((t) => t.key === activeKey.value) || tabs.value[0])

/** 总览里点模块卡：开一个该模块的分页 */
const modules = [
  { path: '/berth', title: '船台船坞', desc: '船台平面占位图 · 拖拽改船位', tip: '空闲 / 占用 / 检修' },
  { path: '/block', title: '分段', desc: '卡片墙 + 状态徽章 · 点卡开分页', tip: '预制→涂装→搭载→合拢→交验' },
  { path: '/erection', title: '搭载吊装', desc: '吊装时间戳序列 + 吊次借记卡', tip: '未开始/进行中/历史 · 检修联动' },
  { path: '/crane', title: '起重设备', desc: '检修停用联动排程 · 替代设备自动筛选', tip: '进行中不换机 · 历史不倒改' },
  { path: '/survey', title: '报验遗留', desc: '左右双栏对照 · 判定印章', tip: '不合格必开遗留项' },
  { path: '/vessel', title: '船舶合同', desc: '时间轴 + 合同信息卡 · 付款节点结算', tip: '履行中起按节点结算' },
  { path: '/launch', title: '下水放行', desc: '放行台 + 放行单快照 · 阻塞逐条列明', tip: '签认后确认 · 船台同步释放' }
]

function viewOf(tab) {
  return viewMap[tab.path] || BerthView
}

function syncRoute() {
  const tab = activeTab.value
  if (!tab) return
  const query = { tab: tab.key }
  Object.keys(tab.params || {}).forEach((k) => {
    const value = tab.params[k]
    if (value !== null && value !== undefined && value !== '') query[k] = value
  })
  const current = router.currentRoute.value
  if (current?.path === tab.path && String(current?.query?.tab || '') === tab.key) return
  router.replace({ path: tab.path, query })
}

function openTab(payload) {
  const path = payload.path
  if (!viewMap[path]) return
  // 同一个视图 + 同一份参数只开一次，重复点就切过去
  const exists = tabs.value.find(
    (t) => t.path === path && JSON.stringify(t.params || {}) === JSON.stringify(payload.params || {})
  )
  if (exists) {
    activeKey.value = exists.key
    syncRoute()
    return exists.key
  }
  seq.value += 1
  const key = 't' + seq.value
  tabs.value.push({
    key,
    title: payload.title || titleMap[path] || '分页',
    sub: payload.sub || '',
    path,
    params: payload.params || {},
    fixed: false
  })
  activeKey.value = key
  stripOpen.value = true
  syncRoute()
  return key
}

function closeTab(key) {
  const index = tabs.value.findIndex((t) => t.key === key)
  if (index < 0) return
  if (tabs.value[index].fixed) return
  tabs.value.splice(index, 1)
  if (activeKey.value !== key) return
  const next = tabs.value[Math.min(index, tabs.value.length - 1)]
  activeKey.value = next ? next.key : 'overview'
  syncRoute()
}

function activate(key) {
  activeKey.value = key
  syncRoute()
}

function onOpenTab(payload) {
  openTab(payload)
}

// 地址栏 → 标签：有 tab key 就切过去，没有就按路径新开一个
function onRouteChange() {
  const current = router.currentRoute.value
  if (!current?.path) return
  const key = current.query?.tab
  if (key && tabs.value.some((t) => t.key === key)) {
    activeKey.value = key
    return
  }
  if (current.path === '/overview') {
    activeKey.value = 'overview'
    return
  }
  if (viewMap[current.path]) {
    openTab({ path: current.path, params: { ...current.query } })
  }
}

async function loadOverview() {
  try {
    overview.value = await api.stats.overview()
  } catch (e) {
    overview.value = null
  }
}

watch(route, onRouteChange)

onMounted(() => {
  loadOverview()
  // 路由没有 install，初始 currentRoute 是 START_LOCATION，这里按地址栏补一次导航
  const hash = window.location.hash.replace(/^#/, '')
  router.replace(hash && hash.startsWith('/') ? hash : '/overview')
})
</script>

<template>
  <div class="app-shell">
    <header class="app-brand">
      <div class="brand-mark">船</div>
      <div>
        <div class="brand-title">船舶修造船厂 · 船台船坞与分段管理</div>
        <div class="brand-sub">船台船坞 · 船舶合同 · 分段 · 搭载吊装 · 报验与遗留项 · 下水放行</div>
      </div>
      <div class="top-stats" v-if="overview">
        <div class="top-stat"><b>{{ overview.berthOccupied }}/{{ overview.berthCount }}</b><span>船台占用</span></div>
        <div class="top-stat"><b>{{ overview.vesselCount }}</b><span>在厂船舶</span></div>
        <div class="top-stat"><b>{{ overview.blockCount }}</b><span>分段</span></div>
        <div class="top-stat"><b>{{ overview.liftCount }}</b><span>吊次</span></div>
        <div class="top-stat"><b>{{ overview.passCount }}</b><span>报验通过</span></div>
        <div class="top-stat"><b>{{ overview.openPunchCount }}</b><span>未闭环遗留项</span></div>
      </div>
    </header>

    <!-- 顶部标签条：可开可关 -->
    <div class="mdi-bar" v-if="stripOpen">
      <div class="mdi-tabs">
        <div
          v-for="tab in tabs"
          :key="tab.key"
          class="mdi-tab"
          :class="{ on: tab.key === activeKey, fixed: tab.fixed }"
          @click="activate(tab.key)"
        >
          <span class="tab-dot"></span>
          <span class="tab-title">{{ tab.title }}</span>
          <span class="tab-close" v-if="!tab.fixed" @click.stop="closeTab(tab.key)">×</span>
        </div>
      </div>
      <div class="mdi-toggle" @click="stripOpen = false">收起标签条 ▲</div>
    </div>
    <div class="mdi-collapsed" v-else>
      <span>当前：{{ activeTab?.title || '总览' }}</span>
      <span class="mdi-toggle" @click="stripOpen = true">展开标签条 ▼</span>
    </div>

    <!-- 工作区：所有分页 DOM 都留着，切换只切 v-show -->
    <div class="mdi-body">
      <div v-for="tab in tabs" :key="tab.key" class="tab-pane" v-show="tab.key === activeKey">
        <section v-if="tab.key === 'overview'" class="overview">
          <div class="view-head">
            <span class="view-title">总览</span>
            <span class="view-tip">顶部标签条可以收起；下面每张模块卡都会新开一个标签页，同一视图可以开多个实例</span>
          </div>

          <div class="ov-stats" v-if="overview">
            <div class="mini-card"><div class="k">船台 / 船坞</div><div class="v">{{ overview.berthCount }}</div></div>
            <div class="mini-card"><div class="k">占用中</div><div class="v">{{ overview.berthOccupied }}</div></div>
            <div class="mini-card"><div class="k">在厂船舶</div><div class="v">{{ overview.vesselCount }}</div></div>
            <div class="mini-card"><div class="k">船东</div><div class="v">{{ overview.ownerCount }}</div></div>
            <div class="mini-card"><div class="k">分段</div><div class="v">{{ overview.blockCount }}</div></div>
            <div class="mini-card"><div class="k">分段总重 (t)</div><div class="v">{{ overview.blockWeight }}</div></div>
            <div class="mini-card"><div class="k">搭载任务</div><div class="v">{{ overview.erectionCount }}</div></div>
            <div class="mini-card"><div class="k">吊次</div><div class="v">{{ overview.liftCount }}</div></div>
            <div class="mini-card"><div class="k">报验单</div><div class="v">{{ overview.surveyCount }}</div></div>
            <div class="mini-card"><div class="k">合同金额 (万元)</div><div class="v">{{ overview.contractAmount }}</div></div>
          </div>

          <div class="ov-modules">
            <div v-for="m in modules" :key="m.path" class="ov-card" @click="openTab({ path: m.path })">
              <div class="ov-card-title">{{ m.title }}</div>
              <div class="ov-card-desc">{{ m.desc }}</div>
              <div class="ov-card-tip">{{ m.tip }}</div>
            </div>
          </div>
        </section>

        <component
          v-else
          :is="viewOf(tab)"
          :params="tab.params"
          :tab-key="tab.key"
          @open-tab="onOpenTab"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.overview { padding-bottom: 8px; }

.ov-stats {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.ov-modules {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
}

.ov-card {
  padding: 14px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid var(--line-strong);
  border-left: 4px solid var(--primary);
  cursor: pointer;
  transition: transform .18s ease, box-shadow .18s ease;
}
.ov-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 22px rgba(38, 50, 56, 0.16);
}
.ov-card-title { font-size: 14px; font-weight: 800; color: var(--primary-deep); }
.ov-card-desc { font-size: 12px; color: var(--ink-soft); margin-top: 4px; }
.ov-card-tip { font-size: 11px; color: var(--ink-dim); margin-top: 8px; }
</style>
