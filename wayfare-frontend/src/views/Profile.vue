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

        <!-- 旅行偏好（P5-A） -->
        <el-tab-pane label="旅行偏好" name="preference">
          <div class="preference-form" v-loading="prefLoading">
            <p class="pref-intro">
              这些偏好会在 AI 规划行程时被参考 —— 填得越具体，生成的行程越贴合你。
              没有填写任何一项也能保存。
            </p>

            <el-form label-position="top" class="pref-form">
              <!-- 喜欢菜系 -->
              <el-form-item>
                <template #label>
                  喜欢菜系
                  <el-tooltip content="AI 检索餐饮点位时会优先考虑这些菜系" placement="top">
                    <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </template>
                <div class="tag-picker">
                  <el-check-tag
                    v-for="item in CUISINE_OPTIONS"
                    :key="item"
                    :checked="profile.cuisines.includes(item)"
                    @change="toggleTag('cuisines', item)"
                  >{{ item }}</el-check-tag>
                </div>
                <div class="tag-adder">
                  <el-input
                    v-model="customInput.cuisines"
                    size="small"
                    placeholder="自定义菜系，回车添加"
                    @keyup.enter="addCustom('cuisines')"
                  />
                  <el-button size="small" @click="addCustom('cuisines')">添加</el-button>
                </div>
              </el-form-item>

              <!-- 口味偏好 -->
              <el-form-item>
                <template #label>
                  口味偏好
                  <el-tooltip content="影响餐饮推荐的筛选，也会写进行程提示" placement="top">
                    <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </template>
                <div class="tag-picker">
                  <el-check-tag
                    v-for="item in FLAVOR_OPTIONS"
                    :key="item"
                    :checked="profile.flavors.includes(item)"
                    @change="toggleTag('flavors', item)"
                  >{{ item }}</el-check-tag>
                </div>
              </el-form-item>

              <!-- 忌口与过敏 -->
              <el-form-item>
                <template #label>
                  忌口与过敏
                  <el-tooltip content="硬约束 —— AI 生成的餐饮推荐会严格避开这些食材" placement="top">
                    <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </template>
                <div class="tag-picker">
                  <el-tag
                    v-for="(item, idx) in profile.taboos"
                    :key="item"
                    closable
                    type="danger"
                    effect="light"
                    @close="removeTaboo(idx)"
                  >{{ item }}</el-tag>
                  <span v-if="!profile.taboos.length" class="tag-empty">还没有添加忌口</span>
                </div>
                <div class="tag-adder">
                  <el-input
                    v-model="tabooInput"
                    size="small"
                    placeholder="输入后回车添加，如「香菜」"
                    @keyup.enter="addTaboo"
                  />
                  <el-button size="small" @click="addTaboo">添加</el-button>
                </div>
                <p class="field-note strong">AI 生成餐饮建议时会严格避开这些食材</p>
              </el-form-item>

              <!-- 旅行风格 -->
              <el-form-item>
                <template #label>
                  旅行风格
                  <el-tooltip content="决定候选点位从哪几类景点里检索" placement="top">
                    <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </template>
                <div class="tag-picker">
                  <el-check-tag
                    v-for="item in STYLE_OPTIONS"
                    :key="item"
                    :checked="profile.travelStyles.includes(item)"
                    @change="toggleTag('travelStyles', item)"
                  >{{ item }}</el-check-tag>
                </div>
              </el-form-item>

              <!-- 节奏 -->
              <el-form-item>
                <template #label>
                  节奏
                  <el-tooltip content="AI 会据此控制每天排几个点位" placement="top">
                    <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </template>
                <el-radio-group v-model="profile.pace">
                  <el-radio v-for="opt in PACE_OPTIONS" :key="opt.value" :value="opt.value">
                    {{ opt.label }}<span class="radio-desc">{{ opt.desc }}</span>
                  </el-radio>
                </el-radio-group>
              </el-form-item>

              <!-- 预算倾向 -->
              <el-form-item>
                <template #label>
                  预算倾向
                  <el-tooltip content="影响点位与餐饮的档位选择" placement="top">
                    <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </template>
                <el-radio-group v-model="profile.budgetLevel">
                  <el-radio :value="1">经济</el-radio>
                  <el-radio :value="2">舒适</el-radio>
                  <el-radio :value="3">品质</el-radio>
                </el-radio-group>
              </el-form-item>

              <!-- 常同行人 -->
              <el-form-item>
                <template #label>
                  常同行人
                  <el-tooltip content="影响交通方式与点位的适龄性" placement="top">
                    <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </template>
                <el-radio-group v-model="profile.companions">
                  <el-radio v-for="item in COMPANION_OPTIONS" :key="item" :value="item">{{ item }}</el-radio>
                </el-radio-group>
              </el-form-item>

              <!-- 单日步行上限 -->
              <el-form-item>
                <template #label>
                  单日步行上限
                  <el-tooltip content="AI 会据此控制每天的点位密度" placement="top">
                    <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </template>
                <div class="slider-row">
                  <el-slider v-model="profile.walkLimitKm" :min="1" :max="20" :step="1" class="walk-slider" />
                  <span class="slider-value">{{ profile.walkLimitKm }} 公里</span>
                </div>
                <p class="field-note">{{ walkHint }}</p>
              </el-form-item>

              <!-- 住宿偏好 -->
              <el-form-item>
                <template #label>
                  住宿偏好
                  <el-tooltip content="影响行程的起止点与每天的首末点位" placement="top">
                    <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </template>
                <el-input
                  v-model="profile.hotelPref"
                  maxlength="50"
                  placeholder="如「民宿」「青年旅舍」「市区商务酒店」"
                />
              </el-form-item>

              <!-- 自由备注 -->
              <el-form-item>
                <template #label>
                  自由备注
                  <el-tooltip content="会原样交给 AI 作为补充约束" placement="top">
                    <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </template>
                <el-input
                  v-model="profile.notes"
                  type="textarea"
                  :rows="3"
                  maxlength="200"
                  show-word-limit
                  placeholder="如「不喜欢人多的景区」「拍古建时希望光线好」"
                />
              </el-form-item>

              <!-- 隐私开关（铁律三的入口） -->
              <el-form-item>
                <div class="privacy-row">
                  <el-switch v-model="profile.allowAiUse" :active-value="1" :inactive-value="0" />
                  <span class="privacy-label">允许 AI 生成行程时使用我的偏好</span>
                </div>
                <p class="field-note">关闭后 AI 不会读取这些信息，生成的行程将不体现你的个人偏好</p>
              </el-form-item>

              <el-form-item>
                <el-button type="primary" :loading="prefSaving" @click="savePreference">保存偏好</el-button>
              </el-form-item>
            </el-form>
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
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import WorkCard from '@/components/WorkCard.vue'
import { useUserStore } from '@/stores/user'
import { getUserWorks } from '@/api/work'
import { getMyFavorites, getMyFollowing, getMyFollowers, unfollowUser, followUser } from '@/api/social'
import { updateUser, uploadAvatar } from '@/api/user'
import { getTravelProfile, saveTravelProfile } from '@/api/profile'

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
  // 直接落在偏好页签时 watch 不会触发（值没变），这里补一次
  if (activeTab.value === 'preference') fetchPreference()
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
  if (tab === 'preference' && !prefLoaded.value) fetchPreference()
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

// ==================== 旅行偏好（P5-A） ====================

// 手册指定的固定选项。菜系可自定义、忌口自由输入，各走自己的输入框。
const CUISINE_OPTIONS = ['晋菜', '川菜', '粤菜', '淮扬', '面食', '火锅', '烧烤', '日料', '西餐', '家常菜']
const FLAVOR_OPTIONS = ['偏清淡', '偏咸', '偏辣', '微辣', '重辣', '偏甜', '偏酸']
const STYLE_OPTIONS = ['古建探访', '自然风光', '博物馆', '市井烟火', '摄影旅拍', '亲子出行', '城市漫步', '美食之旅']
const PACE_OPTIONS = [
  { value: 1, label: '慢', desc: '每天 2-3 个点，留足闲逛时间' },
  { value: 2, label: '适中', desc: '每天 3-4 个点' },
  { value: 3, label: '紧凑', desc: '每天 4-5 个点' }
]
const COMPANION_OPTIONS = ['独自', '情侣', '朋友', '家庭带娃', '带长辈']

// 字段与后端 TravelProfileDTO 一一对应。
// 列表类字段在库里是逗号分隔字符串（CSV），提交前用 toCsv 转回去。
const profile = reactive({
  cuisines: [],
  flavors: [],
  taboos: [],
  travelStyles: [],
  pace: null,
  budgetLevel: null,
  companions: '',
  walkLimitKm: 5,
  hotelPref: '',
  notes: '',
  allowAiUse: 1
})

const customInput = reactive({ cuisines: '' })
const tabooInput = ref('')
const prefLoading = ref(false)
const prefSaving = ref(false)
// 只在第一次切到该页签时拉取，避免把用户还没保存的编辑覆盖掉
const prefLoaded = ref(false)

// 滑块含义的引导文案（描述这个值意味着什么，不是对点位数量的承诺）
const walkHint = computed(() => {
  const km = profile.walkLimitKm
  if (km <= 3) return `${km} 公里：活动范围集中在步行可达的街区`
  if (km <= 8) return `${km} 公里：大致覆盖一个城区，点位之间可搭配短途交通`
  if (km <= 14) return `${km} 公里：可跨区安排，建议用公共交通串联`
  return `${km} 公里：范围较大，更适合自驾或包车`
})

function toggleTag(field, value) {
  const arr = profile[field]
  const i = arr.indexOf(value)
  if (i >= 0) arr.splice(i, 1)
  else arr.push(value)
}

// 自定义菜系：回车或点「添加」都走这里，重复项直接拦掉（验收 3 的「去重」）
function addCustom(field) {
  const raw = (customInput[field] || '').trim()
  if (!raw) return
  if (profile[field].includes(raw)) {
    ElMessage.warning(`「${raw}」已经在列表里了`)
    customInput[field] = ''
    return
  }
  profile[field].push(raw)
  customInput[field] = ''
}

function addTaboo() {
  const raw = tabooInput.value.trim()
  if (!raw) return
  if (profile.taboos.includes(raw)) {
    ElMessage.warning(`「${raw}」已经在列表里了`)
    tabooInput.value = ''
    return
  }
  profile.taboos.push(raw)
  tabooInput.value = ''
}

function removeTaboo(index) {
  profile.taboos.splice(index, 1)
}

// 库里的逗号分隔字符串 ↔ 前端数组。中英文逗号都认，与后端 ProfileRenderer 的拆分口径一致。
function splitCsv(raw) {
  if (!raw) return []
  return String(raw).split(/[,，]/).map(s => s.trim()).filter(Boolean)
}

function toCsv(arr) {
  return arr && arr.length ? arr.join(',') : null
}

// 用后端返回值回填表单 —— 保存后直接用它，省一次 GET，也保证界面与库里完全一致
function applyProfile(d) {
  const data = d || {}
  profile.cuisines = splitCsv(data.cuisines)
  profile.flavors = splitCsv(data.flavors)
  profile.taboos = splitCsv(data.taboos)
  profile.travelStyles = splitCsv(data.travelStyles)
  profile.pace = data.pace != null ? data.pace : null
  profile.budgetLevel = data.budgetLevel != null ? data.budgetLevel : null
  profile.companions = data.companions || ''
  profile.walkLimitKm = data.walkLimitKm != null ? data.walkLimitKm : 5
  profile.hotelPref = data.hotelPref || ''
  profile.notes = data.notes || ''
  // 后端「不传按 1 处理」；读回来是 null 时同样落成 1（默认开启）
  profile.allowAiUse = data.allowAiUse != null ? data.allowAiUse : 1
}

async function fetchPreference() {
  prefLoading.value = true
  try {
    const res = await getTravelProfile()
    applyProfile(res.data)
    prefLoaded.value = true
  } catch (e) {
    // 错误已由 request 拦截器统一提示
  } finally {
    prefLoading.value = false
  }
}

async function savePreference() {
  prefSaving.value = true
  try {
    // 后端是整体替换语义：必须提交完整表单，否则未提交的字段会被清空。
    // 空字符串一律转 null —— 本表可空列用 DEFAULT NULL，null 与 '' 两种表示会让 WHERE IS NULL 静默漏行
    const res = await saveTravelProfile({
      cuisines: toCsv(profile.cuisines),
      flavors: toCsv(profile.flavors),
      taboos: toCsv(profile.taboos),
      travelStyles: toCsv(profile.travelStyles),
      pace: profile.pace,
      budgetLevel: profile.budgetLevel,
      companions: profile.companions || null,
      walkLimitKm: profile.walkLimitKm,
      hotelPref: profile.hotelPref.trim() || null,
      notes: profile.notes.trim() || null,
      allowAiUse: profile.allowAiUse
    })
    applyProfile(res.data)
    ElMessage.success('偏好已保存')
  } catch (e) {
    // 错误已由 request 拦截器统一提示
  } finally {
    prefSaving.value = false
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

// 旅行偏好（P5-A）
.preference-form {
  max-width: 720px;
}

.pref-intro {
  font-size: 13px;
  color: #909399;
  line-height: 1.7;
  margin: 0 0 20px;
  padding: 10px 14px;
  background: #f5f7fa;
  border-radius: 6px;
}

.pref-form {
  :deep(.el-form-item__label) {
    display: flex;
    align-items: center;
    gap: 4px;
    font-weight: 500;
    color: #303133;
    padding-bottom: 6px;
  }
}

.tip-icon {
  font-size: 14px;
  color: #c0c4cc;
  cursor: help;

  &:hover {
    color: #409eff;
  }
}

.tag-picker {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  min-height: 24px;
  margin-bottom: 10px;
}

.tag-empty {
  font-size: 13px;
  color: #c0c4cc;
}

.tag-adder {
  display: flex;
  gap: 8px;
  max-width: 320px;
}

.field-note {
  width: 100%;
  margin: 6px 0 0;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;

  &.strong {
    font-weight: 600;
    color: #606266;
  }
}

.radio-desc {
  margin-left: 6px;
  font-size: 12px;
  color: #909399;
}

.slider-row {
  display: flex;
  align-items: center;
  gap: 16px;
  width: 100%;
}

.walk-slider {
  flex: 1;
  max-width: 360px;
}

.slider-value {
  min-width: 64px;
  font-size: 13px;
  font-weight: 500;
  color: #303133;
}

.privacy-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.privacy-label {
  font-size: 14px;
  color: #303133;
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
  // 旅行偏好：手机上表单不溢出（验收 5）
  .tag-adder {
    max-width: 100%;
  }
  .slider-row {
    flex-wrap: wrap;
    gap: 8px;
  }
  .walk-slider {
    max-width: 100%;
  }
  .radio-desc {
    display: block;
    margin-left: 0;
  }
  .pref-form :deep(.el-radio) {
    display: flex;
    align-items: center;
    height: auto;
    margin-bottom: 6px;
  }
}
</style>
