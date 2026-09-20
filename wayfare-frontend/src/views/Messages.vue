<template>
  <div class="messages-page container">
    <div class="messages-layout card">
      <!-- 会话列表 -->
      <div class="conversation-list" :class="{ collapsed: selectedUserId }">
        <div class="list-header">
          <h3>私信</h3>
        </div>
        <div class="conversations" v-loading="loading">
          <div
            v-for="conv in conversations"
            :key="conv.otherUserId"
            class="conversation-item"
            :class="{ active: selectedUserId === conv.otherUserId }"
            @click="selectConversation(conv)"
          >
            <el-avatar :size="44" :src="conv.otherUserAvatar">
              {{ conv.otherUserNickname?.charAt(0) }}
            </el-avatar>
            <div class="conv-info">
              <div class="conv-top">
                <span class="conv-name">{{ conv.otherUserNickname }}</span>
                <span class="conv-time">{{ formatTime(conv.lastMessageTime) }}</span>
              </div>
              <div class="conv-bottom">
                <span class="conv-last">{{ conv.lastMessage }}</span>
                <el-badge :value="conv.unreadCount" :hidden="conv.unreadCount === 0" class="conv-badge" />
              </div>
            </div>
          </div>
          <el-empty v-if="!loading && conversations.length === 0" description="暂无会话" :image-size="80" />
        </div>
      </div>

      <!-- 聊天区域 -->
      <div class="chat-area" v-if="selectedUserId">
        <div class="chat-header">
          <el-icon class="back-btn" @click="selectedUserId = null"><ArrowLeft /></el-icon>
          <el-avatar :size="36" :src="currentUser?.avatar">{{ currentUser?.nickname?.charAt(0) }}</el-avatar>
          <span class="chat-name">{{ currentUser?.nickname }}</span>
        </div>

        <div class="chat-messages" ref="messagesRef" v-loading="msgLoading">
          <div v-for="msg in messages" :key="msg.id" class="msg-item" :class="{ mine: msg.senderId === userStore.userId }">
            <el-avatar v-if="msg.senderId !== userStore.userId" :size="32" :src="currentUser?.avatar">
              {{ currentUser?.nickname?.charAt(0) }}
            </el-avatar>
            <div class="msg-bubble">
              <p>{{ msg.content }}</p>
              <span class="msg-time">{{ formatTime(msg.createdAt) }}</span>
            </div>
            <el-avatar v-if="msg.senderId === userStore.userId" :size="32" :src="userStore.avatar">
              {{ userStore.nickname?.charAt(0) }}
            </el-avatar>
          </div>
          <el-empty v-if="!msgLoading && messages.length === 0" description="暂无消息，开始聊天吧" :image-size="60" />
        </div>

        <div class="chat-input">
          <el-input
            v-model="inputText"
            type="textarea"
            :rows="2"
            placeholder="输入消息..."
            maxlength="500"
            @keyup.enter.exact="sendMessage"
          />
          <el-button type="primary" :disabled="!inputText.trim()" :loading="sending" @click="sendMessage">
            发送
          </el-button>
        </div>
      </div>

      <!-- 未选择会话 -->
      <div class="chat-empty" v-else>
        <el-icon :size="64" color="#dcdfe6"><ChatDotRound /></el-icon>
        <p>选择一个会话开始聊天</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { useUserStore } from '@/stores/user'
import { getConversationList, getMessageList, sendMessage as sendMsg, markAsRead } from '@/api/message'

const route = useRoute()
const userStore = useUserStore()

const conversations = ref([])
const loading = ref(false)
const selectedUserId = ref(null)
const currentUser = ref(null)
const messages = ref([])
const msgLoading = ref(false)
const inputText = ref('')
const sending = ref(false)
const messagesRef = ref(null)

onMounted(() => {
  fetchConversations()
  // 从URL参数打开指定用户的会话
  if (route.query.userId) {
    selectedUserId.value = Number(route.query.userId)
    fetchMessages()
  }
})

watch(selectedUserId, () => {
  if (selectedUserId.value) {
    fetchMessages()
  }
})

async function fetchConversations() {
  loading.value = true
  try {
    const res = await getConversationList()
    // 后端返回的是分页对象 IPage<Map>，会话列表在 records 里；
    // 原写法直接取 res.data 会把一个对象赋给数组，左栏永远是空的
    conversations.value = res.data?.records || []
  } catch (e) {
    // 忽略
  } finally {
    loading.value = false
  }
}

function selectConversation(conv) {
  selectedUserId.value = conv.otherUserId
  currentUser.value = {
    id: conv.otherUserId,
    nickname: conv.otherUserNickname,
    avatar: conv.otherUserAvatar
  }
}

async function fetchMessages() {
  if (!selectedUserId.value) return
  msgLoading.value = true
  try {
    const res = await getMessageList(selectedUserId.value, { pageNum: 1, pageSize: 50 })
    messages.value = (res.data.records || []).reverse()
    // 标记已读
    markAsRead(selectedUserId.value).catch(() => {})
    // 更新会话未读数
    const conv = conversations.value.find(c => c.otherUserId === selectedUserId.value)
    if (conv) conv.unreadCount = 0
    nextTick(() => {
      scrollToBottom()
    })
  } catch (e) {
    // 忽略
  } finally {
    msgLoading.value = false
  }
}

async function sendMessage() {
  if (!inputText.value.trim() || !selectedUserId.value) return
  sending.value = true
  try {
    await sendMsg({
      receiverId: selectedUserId.value,
      content: inputText.value.trim()
    })
    // 添加到消息列表
    messages.value.push({
      id: Date.now(),
      senderId: userStore.userId,
      receiverId: selectedUserId.value,
      content: inputText.value.trim(),
      createdAt: new Date().toISOString()
    })
    inputText.value = ''
    nextTick(() => scrollToBottom())
    // 刷新会话列表
    fetchConversations()
  } catch (e) {
    // 错误已处理
  } finally {
    sending.value = false
  }
}

function scrollToBottom() {
  if (messagesRef.value) {
    messagesRef.value.scrollTop = messagesRef.value.scrollHeight
  }
}

function formatTime(time) {
  if (!time) return ''
  const d = dayjs(time)
  const now = dayjs()
  if (d.isSame(now, 'day')) return d.format('HH:mm')
  if (d.isSame(now.subtract(1, 'day'), 'day')) return '昨天'
  return d.format('MM-DD')
}
</script>

<style scoped lang="scss">
.messages-page {
  height: calc(100vh - 100px);
  padding-bottom: 20px;
}

.messages-layout {
  display: flex;
  height: 100%;
  overflow: hidden;
  padding: 0;
}

.conversation-list {
  width: 300px;
  border-right: 1px solid #ebeef5;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;

  &.collapsed {
    @media (max-width: 768px) {
      display: none;
    }
  }
}

.list-header {
  padding: 16px 20px;
  border-bottom: 1px solid #ebeef5;

  h3 {
    margin: 0;
    font-size: 18px;
  }
}

.conversations {
  flex: 1;
  overflow-y: auto;
}

.conversation-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  cursor: pointer;
  border-bottom: 1px solid #f5f5f5;
  transition: background 0.2s;

  &:hover {
    background: #f5f7fa;
  }

  &.active {
    background: #ecf5ff;
  }
}

.conv-info {
  flex: 1;
  min-width: 0;
}

.conv-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.conv-name {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.conv-time {
  font-size: 11px;
  color: #c0c4cc;
}

.conv-bottom {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.conv-last {
  font-size: 12px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.chat-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.chat-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 20px;
  border-bottom: 1px solid #ebeef5;
}

.back-btn {
  font-size: 20px;
  cursor: pointer;
  color: #606266;
  display: none;

  @media (max-width: 768px) {
    display: block;
  }
}

.chat-name {
  font-size: 16px;
  font-weight: 600;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  background: #f5f7fa;
}

.msg-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 16px;

  &.mine {
    flex-direction: row-reverse;

    .msg-bubble {
      background: #409eff;
      color: #fff;

      .msg-time {
        color: rgba(255,255,255,0.7);
      }
    }
  }
}

.msg-bubble {
  max-width: 60%;
  background: #fff;
  border-radius: 10px;
  padding: 10px 14px;
  position: relative;

  p {
    margin: 0 0 4px;
    font-size: 14px;
    line-height: 1.5;
    word-break: break-word;
  }

  .msg-time {
    font-size: 11px;
    color: #c0c4cc;
  }
}

.chat-input {
  display: flex;
  gap: 10px;
  padding: 14px 20px;
  border-top: 1px solid #ebeef5;
  background: #fff;
  align-items: flex-end;

  .el-textarea {
    flex: 1;
  }
}

.chat-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #c0c4cc;
  gap: 12px;

  p {
    margin: 0;
    font-size: 14px;
  }
}

@media (max-width: 768px) {
  .messages-page {
    padding: 0 12px;
    height: calc(100vh - 80px);
  }
  .conversation-list {
    width: 100%;
  }
}
</style>
