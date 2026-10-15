import { reactive } from 'vue'
export const auth=reactive({user:null,loaded:false})
let pending=null
export async function loadUser(force=false){
  if(auth.loaded&&!force)return auth.user
  if(pending)return pending
  pending=fetch('/api/auth/me',{credentials:'same-origin',cache:'no-store'}).then(async response=>{
    if(!response.ok)throw new Error('无法连接账号服务，请稍后重试')
    auth.user=(await response.json()).user;auth.loaded=true;return auth.user
  }).finally(()=>{pending=null})
  return pending
}
export function setUser(user){auth.user=user;auth.loaded=true}
export function safeNext(value){return typeof value==='string'&&value.startsWith('/')&&!value.startsWith('//')&&!value.includes('\\')&&!['/login','/register'].includes(value.split('?')[0])?value:'/'}
export function configureAuth(client){
  client.interceptors.request.use(async config=>{
    if(!['get','head','options'].includes((config.method||'get').toLowerCase())){
      await loadUser();if(auth.user?.csrf)config.headers['X-CSRF-Token']=auth.user.csrf
    }
    return config
  })
  client.interceptors.response.use(response=>response,error=>{
    if(error.response?.status===401){setUser(null);if(!['/login','/register'].includes(location.pathname))location.assign('/login?next='+encodeURIComponent(location.pathname+location.search))}
    return Promise.reject(error)
  })
}
export async function checkSession(){try{await loadUser(true);if(!auth.user)location.assign('/login?next='+encodeURIComponent(location.pathname))}catch{ /* Network errors remain visible in the current page. */ }}
