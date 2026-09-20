<template>
  <div class="user-home container" v-loading="loading">
    <template v-if="user">
      <div class="user-header card">
        <div class="user-left">
          <el-avatar :size="72" :src="user.avatar">{{ user.nickname?.charAt(0) }}</el-avatar>
          <div class="user-info">
            <h2 class="user-name">{{ user.nickname }}</h2>
            <p class="user-bio">{{ user.bio || '这个人很懒，什么都没写' }}</p>
          </div>
        </div>
        <div class="user-right">
          <div class="user-stats">
            <span><b>{{ stats.works }}</b> 作品</span>
            <span><b>{{ stats.following }}</b> 关注</span>
            <span><b>{{ stats.followers }}</b> 粉丝</span>
          </div>
          <div class="user-actions" v-if="userStore.isLoggedIn && user.id !== userStore.userId">
            <el-button :type="isFollowing ? 'default' : 'primary'" @click="handleFollow">
              {{ isFollowing ? '已关注' : '关注' }}
            </el-button>
            <el-button type="primary" plain @click="goMessage">私信</el-button>
          </div>
        </div>
      </div>

      <div class="user-works">
        <h3 class="section-title">TA的作品</h3>
        <div class="work-grid" v-loading="worksLoading">
          <WorkCard v-for="work in works" :key="work.id" :work="work" />
          <el-empty v-if="!worksLoading && works.length === 0" description="暂无作品" />
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import WorkCard from '@/components/WorkCard.vue'
import { useUserStore } from '@/stores/user'
import { getUserInfo } from '@/api/user'
import { getUserWorks } from '@/api/work'
import { getUserFollowing, getUserFollowers, checkFollow, followUser, unfollowUser } from '@/api/social'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const user = ref(null)
const loading = ref(false)
const works = ref([])
const worksLoading = ref(false)
const isFollowing = ref(false)

const stats = reactive({ works: 0, following: 0, followers: 0 })

onMounted(() => {
  fetchUserInfo()
  fetchWorks()
})

async function fetchUserInfo() {
  loading.value = true
  try {
    const res = await getUserInfo(route.params.id)
    user.value = res.data
    if (userStore.isLoggedIn && user.value.id !== userStore.userId) {
      checkFollow(user.value.id).then(r => { isFollowing.value = r.data.following }).catch(() => {})
    }
    // 获取统计
    getUserWorks(user.value.id, { pageNum: 1, pageSize: 1 }).then(r => { stats.works = r.data.total || 0 }).catch(() => {})
    getUserFollowing(user.value.id, { pageNum: 1, pageSize: 1 }).then(r => { stats.following = r.data.total || 0 }).catch(() => {})
    getUserFollowers(user.value.id, { pageNum: 1, pageSize: 1 }).then(r => { stats.followers = r.data.total || 0 }).catch(() => {})
  } catch (e) {
    // 错误已处理
  } finally {
    loading.value = false
  }
}

async function fetchWorks() {
  worksLoading.value = true
  try {
    const res = await getUserWorks(route.params.id, { pageNum: 1, pageSize: 20 })
    works.value = res.data.records || []
  } catch (e) {
    // 忽略
  } finally {
    worksLoading.value = false
  }
}

async function handleFollow() {
  try {
    const res = await followUser(user.value.id)
    isFollowing.value = res.data.following
    ElMessage.success(res.data.following ? '关注成功' : '已取消关注')
  } catch (e) {
    // 错误已处理
  }
}

function goMessage() {
  router.push({ path: '/messages', query: { userId: user.value.id } })
}
</script>

<style scoped lang="scss">
.user-home {
  padding-bottom: 40px;
}

.user-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24px 28px;
  margin-bottom: 20px;
  flex-wrap: wrap;
  gap: 16px;
}

.user-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.user-info {
  .user-name {
    font-size: 20px;
    font-weight: 700;
    margin: 0 0 6px;
  }
  .user-bio {
    font-size: 14px;
    color: #909399;
    margin: 0;
  }
}

.user-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 12px;
}

.user-stats {
  display: flex;
  gap: 20px;
  font-size: 14px;
  color: #606266;

  b {
    font-size: 18px;
    color: #303133;
    margin-right: 4px;
  }
}

.section-title {
  font-size: 18px;
  font-weight: 600;
  margin: 0 0 16px;
}

.work-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

@media (max-width: 768px) {
  .user-home {
    padding: 0 12px;
  }
  .work-grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 10px;
  }
  .user-header {
    flex-direction: column;
    align-items: flex-start;
  }
  .user-right {
    align-items: flex-start;
  }
}
</style>
