<template>
  <div class="profile-page container">
    <!-- 用户信息卡片 -->
    <div class="profile-header card">
      <div class="avatar-section">
        <el-avatar :size="80" :src="userStore.avatar" class="profile-avatar">
          {{ userStore.nickname?.charAt(0) }}
        </el-avatar>
        <div class="user-basic">
          <h2 class="user-nickname">{{ userStore.nickname }}</h2>
          <p class="user-username">@{{ userStore.userInfo?.username }}</p>
          <p class="user-bio" v-if="userStore.userInfo?.bio">{{ userStore.userInfo.bio }}</p>
        </div>
      </div>
      <div class="stats-section">
        <div class="stat-item" @click="activeTab = 'works'">
          <span class="stat-num">{{ stats.workCount }}</span>
          <span class="stat-label">作品</span>
        </div>
        <div class="stat-item" @click="activeTab = 'following'">
          <span class="stat-num">{{ stats.followingCount }}</span>
          <span class="stat-label">关注</span>
        </div>
        <div class="stat-item" @click="activeTab = 'followers'">
          <span class="stat-num">{{ stats.followerCount }}</span>
          <span class="stat-label">粉丝</span>
        </div>
        <div class="stat-item" @click="activeTab = 'favorites'">
          <span class="stat-num">{{ stats.favoriteCount }}</span>
          <span class="stat-label">收藏</span>
        </div>
      </div>
    </div>

    <!-- Tab内容 -->
    <div class="profile-content">
      <el-tabs v-model="activeTab" class="profile-tabs">
        <!-- 我的作品 -->
        <el-tab-pane label="我的攻略" name="works">
          <div class="work-grid" v-loading="loading">
            <div v-for="work in myWorks" :key="work.id" class="work-grid-item">
              <WorkCard :work="work" />
            </div>
            <el-empty v-if="!loading && myWorks.length === 0" description="还没有发布作品">
              <el-button type="primary" @click="$router.push('/publish')">去发布</el-button>
            </el-empty>
          </div>
          <div class="load-more" v-if="hasMore && myWorks.length">
            <el-button type="primary" plain :loading="loading" @click="loadMoreWorks">加载更多</el-button>
          </div>
        </el-tab-pane>

        <!-- 我的收藏 -->
        <el-tab-pane label="我的收藏" name="favorites">
          <div class="work-grid" v-loading="favLoading">
            <div v-for="work in favorites" :key="work.id" class="work-grid-item">
              <WorkCard :work="work" />
            </div>
            <el-empty v-if="!favLoading && favorites.length === 0" description="还没有收藏作品" />
          </div>
          <div class="load-more" v-if="hasMoreFav && favorites.length">
            <el-button type="primary" plain :loading="favLoading" @click="loadMoreFavorites">加载更多</el-button>
          </div>
        </el-tab-pane>

        <!-- 关注列表 -->
        <el-tab-pane label="关注" name="following">
          <div class="user-list" v-loading="followLoading">
            <div v-for="user in followingList" :key="user.id" class="user-list-item">
              <el-avatar :size="48" :src="user.avatar" @click="goUser(user.id)">
                {{ user.nickname?.charAt(0) }}
              </el-avatar>
              <div class="user-info" @click="goUser(user.id)">
                <span class="user-name">{{ user.nickname }}</span>
                <span class="user-desc">{{ user.bio || '这个人很懒，什么都没写' }}</span>
              </div>
              <el-button type="danger" plain size="small" @click="handleUnfollow(user.id)">取消关注</el-button>
            </div>
            <el-empty v-if="!followLoading && followingList.length === 0" description="还没有关注任何人" />
          </div>
        </el-tab-pane>

        <!-- 粉丝列表 -->
        <el-tab-pane label="粉丝" name="followers">
          <div class="user-list" v-loading="followerLoading">
            <div v-for="user in followersList" :key="user.id" class="user-list-item">
              <el-avatar :size="48" :src="user.avatar" @click="goUser(user.id)">
                {{ user.nickname?.charAt(0) }}
              </el-avatar>
              <div class="user-info" @click="goUser(user.id)">
                <span class="user-name">{{ user.nickname }}</span>
                <span class="user-desc">{{ user.bio || '这个人很懒，什么都没写' }}</span>
              </div>
              <el-button
                :type="user.isFollowing ? 'default' : 'primary'"
                plain
                size="small"
                @click="toggleFollow(user)"
              >
                {{ user.isFollowing ? '已关注' : '回关' }}
              </el-button>
            </div>
            <el-empty v-if="!followerLoading && followersList.length === 0" description="还没有粉丝" />
          </div>
        </el-tab-pane>

        <!-- 修改资料 -->
        <!-- 旅行偏好（P5 占位） -->
        <el-tab-pane label="旅行偏好" name="preference">
          <div class="preference-placeholder">
            <el-icon :size="40" color="#c0c4cc"><Compass /></el-icon>
            <h3>旅行偏好还没设置</h3>
            <p>
              设置偏好后，AI 规划会结合你的节奏、预算与兴趣生成更贴合你的行程。
              该功能在 P5 阶段接入，当前仅占位。
            </p>
            <el-button type="primary" plain disabled>去设置偏好（待接入）</el-button>
          </div>
        </el-tab-pane>

        <el-tab-pane label="修改资料" name="settings">
          <div class="settings-form">
            <el-form ref="settingsFormRef" :model="settingsForm" :rules="settingsRules" label-width="100px">
              <el-form-item label="头像">
                <el-upload
                  class="avatar-uploader"
                  :show-file-list="false"
                  :before-upload="beforeAvatarUpload"
                  :http-request="customAvatarUpload"
                  accept="image/jpeg,image/png"
                >
                  <el-avatar :size="80" :src="settingsForm.avatar || userStore.avatar">
                    {{ userStore.nickname?.charAt(0) }}
                  </el-avatar>
                  <template #trigger>
                    <div class="avatar-mask">
                      <el-icon><Picture /></el-icon>
                      <span>更换</span>
                    </div>
                  </template>
                </el-upload>
              </el-form-item>
              <el-form-item label="昵称" prop="nickname">
                <el-input v-model="settingsForm.nickname" maxlength="20" />
              </el-form-item>
              <!-- 性别：P0-D 要求的基本信息四项（昵称/头像/性别/简介）之一，此前前端漏了 -->
              <el-form-item label="性别" prop="gender">
                <el-radio-group v-model="settingsForm.gender">
                  <el-radio :value="0">保密</el-radio>
                  <el-radio :value="1">男</el-radio>
                  <el-radio :value="2">女</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="个人简介" prop="bio">
                <el-input v-model="settingsForm.bio" type="textarea" :rows="3" maxlength="100" show-word-limit />
              </el-form-item>
              <el-form-item label="邮箱">
                <el-input v-model="settingsForm.email" placeholder="选填" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="saving" @click="saveSettings">保存修改</el-button>
              </el-form-item>
            </el-form>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import WorkCard from '@/components/WorkCard.vue'
import { useUserStore } from '@/stores/user'
import { getUserWorks } from '@/api/work'
import { getMyFavorites, getMyFollowing, getMyFollowers, unfollowUser, followUser } from '@/api/social'
import { updateUser, uploadAvatar } from '@/api/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const activeTab = ref('works')
const settingsFormRef = ref()
const saving = ref(false)

// 统计
const stats = reactive({
  workCount: 0,
  followingCount: 0,
  followerCount: 0,
  favoriteCount: 0
})

// 我的作品
const myWorks = ref([])
const loading = ref(false)
const workPage = ref(1)
const hasMore = ref(false)

// 收藏
const favorites = ref([])
const favLoading = ref(false)
const favPage = ref(1)
const hasMoreFav = ref(false)

// 关注/粉丝
const followingList = ref([])
const followersList = ref([])
const followLoading = ref(false)
const followerLoading = ref(false)

// 修改资料
const settingsForm = reactive({
  nickname: '',
  gender: 0,
  bio: '',
  email: '',
  avatar: ''
})

const settingsRules = {
  nickname: [{ required: true, message: '请输入昵称', trigger: 'blur' }]
}

watch(() => route.query.tab, (tab) => {
  if (tab) activeTab.value = tab
})

onMounted(() => {
  if (route.query.tab) activeTab.value = route.query.tab
  initSettingsForm()
  fetchMyWorks()
})

function initSettingsForm() {
  const info = userStore.userInfo || {}
  settingsForm.nickname = info.nickname || ''
  // gender 为 0（保密）时也要落回 0，不能用 || 兜底，否则 0 会被当成 falsy
  settingsForm.gender = info.gender != null ? info.gender : 0
  settingsForm.bio = info.bio || ''
  settingsForm.email = info.email || ''
  settingsForm.avatar = info.avatar || ''
}

async function fetchMyWorks() {
  loading.value = true
  try {
    const res = await getUserWorks(userStore.userId, { pageNum: workPage.value, pageSize: 12 })
    const records = res.data.records || []
    myWorks.value = workPage.value === 1 ? records : [...myWorks.value, ...records]
    hasMore.value = myWorks.value.length < (res.data.total || 0)
    stats.workCount = res.data.total || 0
  } catch (e) {
    // 忽略
  } finally {
    loading.value = false
  }
}

function loadMoreWorks() {
  workPage.value++
  fetchMyWorks()
}

async function fetchFavorites() {
  favLoading.value = true
  try {
    const res = await getMyFavorites({ pageNum: favPage.value, pageSize: 12 })
    const records = res.data.records || []
    favorites.value = favPage.value === 1 ? records : [...favorites.value, ...records]
    hasMoreFav.value = favorites.value.length < (res.data.total || 0)
    stats.favoriteCount = res.data.total || 0
  } catch (e) {
    // 忽略
  } finally {
    favLoading.value = false
  }
}

function loadMoreFavorites() {
  favPage.value++
  fetchFavorites()
}

async function fetchFollowing() {
  followLoading.value = true
  try {
    const res = await getMyFollowing({ pageNum: 1, pageSize: 50 })
    followingList.value = res.data.records || []
    stats.followingCount = res.data.total || 0
  } catch (e) {
    // 忽略
  } finally {
    followLoading.value = false
  }
}

async function fetchFollowers() {
  followerLoading.value = true
  try {
    const res = await getMyFollowers({ pageNum: 1, pageSize: 50 })
    followersList.value = res.data.records || []
    stats.followerCount = res.data.total || 0
  } catch (e) {
    // 忽略
  } finally {
    followerLoading.value = false
  }
}

// Tab切换时加载对应数据
watch(activeTab, (tab) => {
  if (tab === 'favorites' && favorites.value.length === 0) fetchFavorites()
  if (tab === 'following' && followingList.value.length === 0) fetchFollowing()
  if (tab === 'followers' && followersList.value.length === 0) fetchFollowers()
})

async function handleUnfollow(userId) {
  try {
    const res = await unfollowUser(userId)
    ElMessage.success('已取消关注')
    followingList.value = followingList.value.filter(u => u.id !== userId)
    stats.followingCount = res.data.followingCount || stats.followingCount - 1
  } catch (e) {
    // 错误已处理
  }
}

async function toggleFollow(user) {
  try {
    const res = await followUser(user.id)
    user.isFollowing = res.data.following
    ElMessage.success(res.data.following ? '关注成功' : '已取消关注')
  } catch (e) {
    // 错误已处理
  }
}

function beforeAvatarUpload(file) {
  const isImage = ['image/jpeg', 'image/png'].includes(file.type)
  const isLt5M = file.size / 1024 / 1024 < 5
  if (!isImage) ElMessage.error('只能上传 JPG/PNG 格式图片')
  if (!isLt5M) ElMessage.error('图片大小不能超过 5MB')
  return isImage && isLt5M
}

async function customAvatarUpload(options) {
  try {
    const formData = new FormData()
    formData.append('file', options.file)
    const res = await uploadAvatar(formData)
    settingsForm.avatar = res.data.fileUrl
    ElMessage.success('头像上传成功')
  } catch (e) {
    // 错误已处理
  }
}

async function saveSettings() {
  await settingsFormRef.value.validate()
  saving.value = true
  try {
    await updateUser({
      nickname: settingsForm.nickname,
      gender: settingsForm.gender,
      bio: settingsForm.bio,
      email: settingsForm.email,
      avatar: settingsForm.avatar
    })
    userStore.updateUserInfo({
      nickname: settingsForm.nickname,
      gender: settingsForm.gender,
      bio: settingsForm.bio,
      email: settingsForm.email,
      avatar: settingsForm.avatar
    })
    ElMessage.success('保存成功')
  } catch (e) {
    // 错误已处理
  } finally {
    saving.value = false
  }
}

function goUser(id) {
  if (id) router.push(`/user/${id}`)
}
</script>

<style scoped lang="scss">
.profile-page {
  padding-bottom: 40px;
}

.profile-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 28px 32px;
  margin-bottom: 20px;
  flex-wrap: wrap;
  gap: 20px;
}

.avatar-section {
  display: flex;
  align-items: center;
  gap: 20px;
}

.profile-avatar {
  border: 3px solid #f0f0f0;
}

.user-basic {
  .user-nickname {
    font-size: 22px;
    font-weight: 700;
    margin: 0 0 4px;
    color: #303133;
  }
  .user-username {
    font-size: 13px;
    color: #909399;
    margin: 0 0 6px;
  }
  .user-bio {
    font-size: 14px;
    color: #606266;
    margin: 0;
    max-width: 300px;
  }
}

.stats-section {
  display: flex;
  gap: 32px;
}

.stat-item {
  text-align: center;
  cursor: pointer;
  padding: 8px 12px;
  border-radius: 8px;
  transition: background 0.2s;

  &:hover {
    background: #f5f7fa;
  }

  .stat-num {
    display: block;
    font-size: 22px;
    font-weight: 700;
    color: #303133;
  }
  .stat-label {
    font-size: 13px;
    color: #909399;
  }
}

.profile-content {
  background: #fff;
  border-radius: 10px;
  padding: 20px 24px;
}

// 旅行偏好占位（P5 填内容）
.preference-placeholder {
  text-align: center;
  padding: 48px 20px;
  color: #909399;

  h3 {
    font-size: 16px;
    color: #606266;
    margin: 12px 0 8px;
    font-weight: 500;
  }

  p {
    font-size: 13px;
    line-height: 1.7;
    max-width: 460px;
    margin: 0 auto 16px;
  }
}


.work-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.load-more {
  text-align: center;
  padding: 20px 0;
}

.user-list {
  .user-list-item {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 14px 0;
    border-bottom: 1px solid #f5f5f5;

    &:last-child {
      border-bottom: none;
    }
  }
}

.user-info {
  flex: 1;
  cursor: pointer;

  .user-name {
    display: block;
    font-size: 15px;
    font-weight: 600;
    color: #303133;
    margin-bottom: 4px;
  }
  .user-desc {
    font-size: 13px;
    color: #909399;
  }
}

.settings-form {
  max-width: 500px;
}

.avatar-uploader {
  position: relative;
  cursor: pointer;

  .avatar-mask {
    position: absolute;
    top: 0;
    left: 0;
    width: 80px;
    height: 80px;
    border-radius: 50%;
    background: rgba(0,0,0,0.5);
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    color: #fff;
    font-size: 12px;
    opacity: 0;
    transition: opacity 0.2s;

    &:hover {
      opacity: 1;
    }
  }
}

@media (max-width: 768px) {
  .profile-page {
    padding: 0 12px;
  }
  .profile-header {
    padding: 20px 16px;
    flex-direction: column;
    text-align: center;
  }
  .avatar-section {
    flex-direction: column;
  }
  .stats-section {
    gap: 20px;
  }
  .work-grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 10px;
  }
  .profile-content {
    padding: 16px 12px;
  }
}
</style>
