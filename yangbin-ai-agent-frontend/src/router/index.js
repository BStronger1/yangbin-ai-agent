import { createRouter, createWebHistory } from 'vue-router'
import {auth,loadUser,safeNext} from '../auth'

const routes = [
  {path:'/login',component:()=>import('../views/Auth.vue'),meta:{public:true,title:'登录 · yangbin AI'}},
  {path:'/register',component:()=>import('../views/Auth.vue'),meta:{public:true,title:'注册 · yangbin AI'}},
  {path:'/account',component:()=>import('../views/Account.vue'),meta:{title:'账号管理 · yangbin AI'}},
  {path:'/knowledge',component:()=>import('../views/Knowledge.vue'),meta:{title:'知识库 RAG · yangbin AI'}},
  {path:'/mcp',component:()=>import('../views/Mcp.vue'),meta:{title:'MCP 工具 · yangbin AI'}},
  {path:'/planner',component:()=>import('../views/Planner.vue'),meta:{title:'约会规划 · yangbin AI'}},
  {
    path: '/',
    name: 'Home',
    component: () => import('../views/Home.vue'),
    meta: {
      title: '首页 - yangbinAI超级智能体应用平台',
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
    component: () => import('../views/SuperAgent.vue'),
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
  if(to.meta.title)document.title=to.meta.title
  try{await loadUser()}catch{if(!to.meta.public)return {path:'/login',query:{next:to.fullPath}}}
  if(!to.meta.public&&!auth.user)return {path:'/login',query:{next:to.fullPath}}
  if(to.meta.public&&auth.user)return safeNext(to.query.next)
})

export default router
