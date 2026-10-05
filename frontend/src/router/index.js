import { createRouter, createWebHashHistory } from 'vue-router'

// 注意：main.js 里没有 app.use(router)，路由实例由 App.vue 直接驱动，
// 所以这里全部用静态 import —— <component :is> 拿到的是组件对象本身。
import BerthView from '../views/BerthView.vue'
import BlockView from '../views/BlockView.vue'
import CraneView from '../views/CraneView.vue'
import ErectionView from '../views/ErectionView.vue'
import SurveyView from '../views/SurveyView.vue'
import VesselView from '../views/VesselView.vue'
import LaunchView from '../views/LaunchView.vue'

const routes = [
  { path: '/', redirect: '/overview' },
  { path: '/overview', name: 'overview', component: BerthView },
  { path: '/berth', name: 'berth', component: BerthView },
  { path: '/block', name: 'block', component: BlockView },
  { path: '/erection', name: 'erection', component: ErectionView },
  { path: '/crane', name: 'crane', component: CraneView },
  { path: '/survey', name: 'survey', component: SurveyView },
  { path: '/vessel', name: 'vessel', component: VesselView },
  { path: '/launch', name: 'launch', component: LaunchView },
  { path: '/:pathMatch(.*)*', redirect: '/overview' }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
