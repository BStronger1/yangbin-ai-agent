<template>
  <div class="love-master-container">
    <div class="header">
      <div class="back-button" @click="goBack">返回</div>
      <h1 class="title">AI恋爱大师</h1>
      <div class="chat-id">会话ID: {{ chatId }}</div>
    </div>

    <label class="mode-picker">回答方式
      <select v-model="mode" @change="newConversation" :disabled="connectionStatus === 'connecting'">
        <option value="chat">普通聊天</option>
        <option value="rag" :disabled="!capabilities.rag">知识库问答（RAG）</option>
        <option value="mcp" :disabled="!capabilities.mcpTools.length">MCP 工具</option>
      </select>
    </label>
    <div class="mode-picker history-picker"><label>历史会话 <select aria-label="历史会话" :value="chatId" :disabled="connectionStatus === 'connecting'" @change="openConversation($event.target.value)"><option :value="chatId" v-if="!conversations.some(c=>c.id===chatId)">新会话</option><option v-for="c in conversations" :key="c.id+':'+c.mode" :value="c.id">{{c.title}} · {{c.mode}}</option></select></label><button @click="newConversation" :disabled="connectionStatus === 'connecting'">新建会话</button><button @click="deleteConversation" :disabled="connectionStatus === 'connecting'">删除当前会话</button><small>按账号保存最近 20 条上下文，支持刷新和重启后恢复。</small><span role="alert" v-if="historyError">{{historyError}}</span></div>
    <ExecutionTrace :events="traceEvents" />
    <div class="content-wrapper">
      <div class="chat-area">
        <ChatRoom
          :messages="messages"
          :connection-status="connectionStatus"
          ai-type="love"
          @send-message="sendMessage"
        />
      </div>
    </div>

    <div class="footer-container">
      <AppFooter />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import ChatRoom from '../components/ChatRoom.vue'
import {checkSession} from '../auth'
import {api,errorMessage} from '../workspace-api'
import ExecutionTrace from '../components/ExecutionTrace.vue'
import AppFooter from '../components/AppFooter.vue'
import { chatWithLoveApp, getCapabilities } from '../api'

// 设置页面标题和元数据
useHead({
  title: 'AI恋爱大师 - yangbinAI超级智能体应用平台',
  meta: [
    {
      name: 'description',
      content: 'AI恋爱大师是yangbinAI超级智能体应用平台的专业情感顾问，帮你解答各种恋爱问题，提供情感建议'
    },
    {
      name: 'keywords',
      content: 'AI恋爱大师,情感顾问,恋爱咨询,AI聊天,情感问题,yangbin,AI智能体'
    }
  ]
})

const router = useRouter()
const messages = ref([])
const traceEvents=ref([])
const mode = ref('chat')
const capabilities = ref({ rag: false, mcpTools: [] })
onMounted(async () => {
  try {
    capabilities.value = await getCapabilities()
    if (capabilities.value.rag) mode.value = 'rag'
    await refreshConversations();if(conversations.value.length)await openConversation(conversations.value[0].id);else newConversation()
  } catch (error) { console.error('Capabilities unavailable', error) }
})
const conversations=ref([]),historyError=ref('')
async function refreshConversations(){conversations.value=(await api.get('/ai/conversations')).data}
function newConversation(){chatId.value=generateChatId();messages.value=[];traceEvents.value=[];historyError.value=''}
async function openConversation(id){try{const c=conversations.value.find(c=>c.id===id);if(!c)return;const response=await api.get('/ai/conversations/'+id,{params:{mode:c.mode}});chatId.value=id;mode.value=c.mode;messages.value=response.data.map(m=>({content:m.content,isUser:m.role==='USER',time:Date.now()}));traceEvents.value=[]}catch(e){historyError.value=errorMessage(e)}}
async function deleteConversation(){if(!confirm('删除当前会话及保存的上下文？'))return;try{await api.delete('/ai/conversations/'+chatId.value,{params:{mode:mode.value}});newConversation();await refreshConversations()}catch(e){historyError.value=errorMessage(e)}}
const chatId = ref('')
const connectionStatus = ref('disconnected')
let eventSource = null

// 生成随机会话ID
const generateChatId = () => {
  return 'love_' + Math.random().toString(36).substring(2, 10)
}

// 添加消息到列表
const addMessage = (content, isUser) => {
  messages.value.push({
    content,
    isUser,
    time: new Date().getTime()
  })
}

// 发送消息
const sendMessage = (message) => {
  traceEvents.value=[]
  addMessage(message, true)

  // 连接SSE
  if (eventSource) {
    eventSource.close()
  }

  // 创建一个空的AI回复消息
  const aiMessageIndex = messages.value.length
  addMessage('', false)

  connectionStatus.value = 'connecting'
  eventSource = chatWithLoveApp(message, chatId.value, mode.value)

  eventSource.addEventListener('trace', event => {
    const item=JSON.parse(event.data),index=traceEvents.value.findIndex(e=>e.id===item.id)
    if(index>=0) traceEvents.value[index]=item
    else {traceEvents.value.push(item);if(traceEvents.value.length>80)traceEvents.value.shift()}
  })
  eventSource.addEventListener('failure', event => {
    traceEvents.value=traceEvents.value.map(e=>e.status==='running'?{...e,status:'failed'}:e);addMessage(event.data,false);connectionStatus.value='error';eventSource.close()
  })

  // 监听SSE消息
  eventSource.onmessage = (event) => {
    const data = event.data
    if (data && data !== '[DONE]') {
      // 更新最新的AI消息内容，而不是创建新消息
      if (aiMessageIndex < messages.value.length) {
        messages.value[aiMessageIndex].content += data
      }
    }

    if (data === '[DONE]') {
      connectionStatus.value = 'disconnected'
      eventSource.close()
      refreshConversations().catch(e=>historyError.value=errorMessage(e))
    }
  }

  // 监听SSE错误
  eventSource.onerror = (error) => {
    traceEvents.value=traceEvents.value.map(e=>e.status==='running'?{...e,status:'failed'}:e)
    checkSession()
    console.error('SSE Error:', error)
    connectionStatus.value = 'error'
    eventSource.close()
  }
}

// 返回主页
const goBack = () => {
  router.push('/')
}

// 页面加载时添加欢迎消息
onMounted(() => {
  // 生成聊天ID
  chatId.value = generateChatId()

  // 添加欢迎消息
  addMessage('欢迎来到AI恋爱大师，请告诉我你的恋爱问题，我会尽力给予帮助和建议。', false)
})

// 组件销毁前关闭SSE连接
onBeforeUnmount(() => {
  if (eventSource) {
    eventSource.close()
  }
})
</script>

<style scoped>
.history-picker {flex-wrap:wrap}.history-picker select{max-width:300px}.history-picker button{padding:8px;border:1px solid #ddd;background:white;border-radius:6px}.history-picker small{width:100%;text-align:center;color:#666}
.mode-picker { display: flex; align-items: center; justify-content: center; gap: 12px; padding: 12px; color: #333; background: #fff; }
.mode-picker select { padding: 8px; border-radius: 6px; border: 1px solid #ccc; }
.love-master-container {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background-color: #fff9f9;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 24px;
  background-color: #ff6b8b;
  color: white;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  position: sticky;
  top: 0;
  z-index: 10;
}

.back-button {
  font-size: 16px;
  cursor: pointer;
  display: flex;
  align-items: center;
  transition: opacity 0.2s;
}

.back-button:hover {
  opacity: 0.8;
}

.back-button:before {
  content: '←';
  margin-right: 8px;
}

.title {
  font-size: 20px;
  font-weight: bold;
  margin: 0;
}

.chat-id {
  font-size: 14px;
  opacity: 0.8;
}

.content-wrapper {
  display: flex;
  flex-direction: column;
  flex: 1;
}

.chat-area {
  flex: 1;
  padding: 16px;
  overflow: hidden;
  position: relative;
  /* 设置最小高度确保内容显示正常 */
  min-height: calc(100vh - 56px - 180px); /* 100vh减去头部高度和页脚高度 */
  margin-bottom: 16px; /* 为页脚留出空间 */
}

.footer-container {
  margin-top: auto;
}

/* 响应式样式 */
@media (max-width: 768px) {
  .header {
    padding: 12px 16px;
  }

  .title {
    font-size: 18px;
  }

  .chat-id {
    font-size: 12px;
  }

  .chat-area {
    padding: 12px;
    min-height: calc(100vh - 48px - 160px); /* 调整计算值 */
    margin-bottom: 12px;
  }
}

@media (max-width: 480px) {
  .header {
    padding: 10px 12px;
  }

  .back-button {
    font-size: 14px;
  }

  .title {
    font-size: 16px;
  }

  .chat-id {
    display: none;
  }

  .chat-area {
    padding: 8px;
    min-height: calc(100vh - 42px - 150px); /* 再次调整计算值 */
    margin-bottom: 8px;
  }
}
</style>
