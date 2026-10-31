import { createRouter, createWebHistory } from 'vue-router'
import {auth,loadUser,safeNext} from '../auth'

const legacyWork = to => ({ path: typeof to.query.session === 'string' || typeof to.query.project === 'string' || to.query.mode !== 'chat' ? '/coding' : '/super-agent', query: to.query, hash: to.hash })

const routes = [
  {path:'/download',component:()=>import('../views/Download.vue'),meta:{public:true,title:'下载客户端 · yangbin AI'}},
  {path:'/settings',component:()=>import('../views/Settings.vue'),meta:{title:'设置 · yangbin AI'}},
  {path:'/assistant',component:()=>import('../views/AssistantWorkspace.vue'),meta:{title:'通用助手 · yangbin AI'}},
  {path:'/coding',component:()=>import('../views/CodingWorkspace.vue'),meta:{title:'Coding Agent · yangbin AI'}},
  {path:'/governance',component:()=>import('../views/Governance.vue'),meta:{title:'用量与运行状态 · yangbin AI'}},
  {path:'/partners/:partnerId',component:()=>import('../views/PartnerHome.vue'),meta:{title:'工作伙伴 · yangbin AI'}},
  {path:'/partners/:partnerId/details',component:()=>import('../views/PartnerDetails.vue'),meta:{title:'伙伴详情 · yangbin AI'}},
  {path:'/presentation',component:()=>import('../views/PresentationStudio.vue'),meta:{title:'演示工作台 · yangbin AI'}},
  {path:'/skills',component:()=>import('../views/SkillHub.vue'),meta:{title:'技能与伙伴 · yangbin AI'}},
  {path:'/skills/manage',component:()=>import('../views/Skills.vue'),meta:{title:'出行技能管理 · yangbin AI'}},
  {path:'/work',redirect:legacyWork},
  {path:'/work/:skillId',component:()=>import('../views/WorkDesk.vue'),meta:{title:'技能任务 · yangbin AI'}},
  {path:'/tasks',component:()=>import('../views/Tasks.vue'),meta:{title:'任务工作台 · yangbin AI'}},
  {path:'/tasks/:id',component:()=>import('../views/Tasks.vue'),meta:{title:'任务详情 · yangbin AI'}},
  {path:'/login',component:()=>import('../views/Auth.vue'),meta:{public:true,title:'登录 · yangbin AI'}},
  {path:'/register',component:()=>import('../views/Auth.vue'),meta:{public:true,title:'注册 · yangbin AI'}},
  {path:'/account',component:()=>import('../views/Account.vue'),meta:{title:'账号管理 · yangbin AI'}},
  {path:'/knowledge',component:()=>import('../views/Knowledge.vue'),meta:{title:'知识库 RAG · yangbin AI'}},
  {path:'/mcp',component:()=>import('../views/Mcp.vue'),meta:{title:'MCP 工具 · yangbin AI'}},
  {path:'/planner',component:()=>import('../views/Planner.vue'),meta:{title:'约会规划 · yangbin AI'}},
  {
    path: '/',
    name: 'Home',
    redirect: to => (to.query.session || to.query.project || to.query.mode ? legacyWork(to) : { path: '/assistant', query: { mode: 'chat' } }),
    meta: {
      title: '通用助手 · yangbin AI',
      description: 'yangbinAI超级智能体应用平台提供AI恋爱大师和AI超级智能体服务，满足您的各种AI对话需求'
    }
  },
  {
    path: '/love-master',
    name: 'LoveMaster',
    component: () => import('../views/LoveMaster.vue'),
    meta: {
      title: 'AI恋爱大师 - yangbinAI超级智能体应用平台',
      description: 'AI恋爱大师是yangbinAI超级智能体应用平台的专业情感顾问，帮你解答各种恋爱问题，提供情感建议'
    }
  },
  {
    path: '/super-agent',
    name: 'SuperAgent',
    component: () => import('../views/CodingWorkspace.vue'),
    meta: {
      title: 'AI超级智能体 - yangbinAI超级智能体应用平台',
      description: 'AI超级智能体是yangbinAI超级智能体应用平台的全能助手，能解答各类专业问题，提供精准建议和解决方案'
    }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局导航守卫，设置文档标题
router.beforeEach(async to => {
  // Fetch public page code while session validation is in flight. Rendering and
  // all data access still wait for the existing authentication guard below.
  if (!auth.loaded) {
    const component = to.matched.at(-1)?.components?.default
    if (typeof component === 'function') void component().catch(() => {})
  }
  try{await loadUser()}catch{if(!to.meta.public)return {path:'/login',query:{next:to.fullPath}}}
  if(!to.meta.public&&!auth.user)return {path:'/login',query:{next:to.fullPath}}
  if(['/login','/register'].includes(to.path)&&auth.user)return safeNext(to.query.next)
})

// Static route metadata does not require a head-management runtime.
router.afterEach(to => {
  document.title = to.meta.title || 'yangbin AI Agent'
  let description = document.head.querySelector('meta[name="description"]')
  if (!description) {
    description = document.createElement('meta')
    description.name = 'description'
    document.head.appendChild(description)
  }
  description.content = to.meta.description || 'yangbin AI Agent：智能对话、工作伙伴与 Coding Agent 工作台。'
})

export default router
