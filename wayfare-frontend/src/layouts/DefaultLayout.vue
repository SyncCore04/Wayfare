<template>
  <div class="default-layout">
    <!-- 顶部导航栏 -->
    <header class="nav-header">
      <div class="nav-container">
        <div class="nav-left">
          <router-link to="/" class="logo">
            <el-icon :size="24" color="#409eff"><Compass /></el-icon>
            <span class="logo-text">行走集</span>
          </router-link>
        </div>

        <!-- 桌面端导航 -->
        <nav class="nav-center hide-mobile">
          <router-link to="/" class="nav-link">首页</router-link>
          <router-link to="/?tab=hot" class="nav-link">热门</router-link>
          <router-link to="/?tab=recommend" class="nav-link">推荐</router-link>
        </nav>

        <div class="nav-right">
          <!-- 搜索框（桌面端） -->
          <el-input
            v-model="searchKey"
            placeholder="搜索作品..."
            class="search-input hide-mobile"
            clearable
            @keyup.enter="handleSearch"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>

          <!-- 已登录 -->
          <template v-if="userStore.isLoggedIn">
            <router-link to="/publish" class="publish-btn hide-mobile">
              <el-icon><Plus /></el-icon>
              <span>发布</span>
            </router-link>
            <router-link to="/messages" class="icon-btn">
              <el-badge :value="unreadCount" :hidden="unreadCount === 0" class="hide-mobile">
                <el-icon :size="20"><Message /></el-icon>
              </el-badge>
            </router-link>
            <el-dropdown @command="handleCommand">
              <div class="user-info">
                <el-avatar :size="32" :src="userStore.avatar">
                  {{ userStore.nickname?.charAt(0) }}
                </el-avatar>
                <span class="hide-mobile nickname">{{ userStore.nickname }}</span>
              </div>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="profile">
                    <el-icon><User /></el-icon>个人中心
                  </el-dropdown-item>
                  <el-dropdown-item command="publish" v-if="true">
                    <el-icon><Plus /></el-icon>发布作品
                  </el-dropdown-item>
                  <el-dropdown-item command="messages" class="hide-desktop">
                    <el-icon><Message /></el-icon>私信
                  </el-dropdown-item>
                  <el-dropdown-item command="admin" v-if="userStore.isAdmin">
                    <el-icon><Setting /></el-icon>管理后台
                  </el-dropdown-item>
                  <el-dropdown-item divided command="logout">
                    <el-icon><SwitchButton /></el-icon>退出登录
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>

          <!-- 未登录 -->
          <template v-else>
            <router-link to="/login" class="login-btn">登录</router-link>
            <router-link to="/register" class="register-btn hide-mobile">注册</router-link>
          </template>
        </div>
      </div>
    </header>

    <!-- 主内容区 -->
    <main class="main-content">
      <router-view v-slot="{ Component }">
        <transition name="fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>

    <!-- 底部 -->
    <footer class="site-footer hide-mobile">
      <div class="container">
        <p>行走集 - AI 旅游攻略分享平台</p>
        <p class="copyright">© 2024 Wayfare. All rights reserved.</p>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { getUnreadCount } from '@/api/message'

const router = useRouter()
const userStore = useUserStore()
const searchKey = ref('')
const unreadCount = ref(0)

onMounted(() => {
  if (userStore.isLoggedIn) {
    fetchUnreadCount()
  }
})

async function fetchUnreadCount() {
  try {
    const res = await getUnreadCount()
    unreadCount.value = res.data || 0
  } catch (e) {
    // 忽略
  }
}

function handleSearch() {
  if (searchKey.value.trim()) {
    router.push({ path: '/', query: { keyword: searchKey.value } })
  }
}

function handleCommand(command) {
  switch (command) {
    case 'profile':
      router.push('/profile')
      break
    case 'publish':
      router.push('/publish')
      break
    case 'messages':
      router.push('/messages')
      break
    case 'admin':
      router.push('/admin')
      break
    case 'logout':
      ElMessageBox.confirm('确定要退出登录吗？', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(() => {
        userStore.logout()
        ElMessage.success('已退出登录')
        router.push('/')
      }).catch(() => {})
      break
  }
}
</script>

<style scoped lang="scss">
.default-layout {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.nav-header {
  position: sticky;
  top: 0;
  z-index: 100;
  background: #fff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.nav-container {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 16px;
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.nav-left .logo {
  display: flex;
  align-items: center;
  gap: 8px;
  text-decoration: none;
}

.logo-text {
  font-size: 20px;
  font-weight: 700;
  color: #303133;
}

.nav-center {
  display: flex;
  gap: 24px;
  flex: 1;
  justify-content: center;
}

.nav-link {
  color: #606266;
  font-size: 15px;
  text-decoration: none;
  padding: 6px 0;
  border-bottom: 2px solid transparent;
  transition: all 0.2s;

  &:hover, &.router-link-active {
    color: #409eff;
    border-bottom-color: #409eff;
  }
}

.nav-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.search-input {
  width: 200px;
}

.publish-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 16px;
  background: #409eff;
  color: #fff;
  border-radius: 18px;
  font-size: 14px;
  text-decoration: none;
  transition: background 0.2s;

  &:hover {
    background: #66b1ff;
  }
}

.icon-btn {
  color: #606266;
  display: flex;
  align-items: center;
  padding: 6px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.nickname {
  font-size: 14px;
  color: #303133;
  max-width: 100px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.login-btn {
  padding: 6px 18px;
  border: 1px solid #409eff;
  color: #409eff;
  border-radius: 18px;
  font-size: 14px;
  text-decoration: none;
}

.register-btn {
  padding: 6px 18px;
  background: #409eff;
  color: #fff;
  border-radius: 18px;
  font-size: 14px;
  text-decoration: none;
}

.main-content {
  flex: 1;
  padding: 20px 0;
}

.site-footer {
  background: #fff;
  border-top: 1px solid #ebeef5;
  padding: 24px 0;
  text-align: center;
  color: #909399;
  font-size: 13px;

  .copyright {
    margin-top: 4px;
    font-size: 12px;
  }
}

.fade-enter-active, .fade-leave-active {
  transition: opacity 0.2s;
}
.fade-enter-from, .fade-leave-to {
  opacity: 0;
}

@media (max-width: 768px) {
  .nav-container {
    padding: 0 12px;
  }
  .logo-text {
    font-size: 18px;
  }
  .main-content {
    padding: 12px 0;
  }
}
</style>
