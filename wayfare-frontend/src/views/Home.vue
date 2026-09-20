<template>
  <div class="home-page container">
    <!-- 顶部Banner -->
    <div class="hero-banner hide-mobile">
      <div class="banner-content">
        <h1>发现值得走的路线</h1>
        <p>真实行程 · 多图实录 · 按目的地与天数找攻略</p>
        <el-button type="primary" size="large" @click="$router.push('/publish')" v-if="userStore.isLoggedIn">
          <el-icon><Plus /></el-icon>发布攻略
        </el-button>
        <el-button type="primary" size="large" @click="$router.push('/login')" v-else>
          开始创作
        </el-button>
      </div>
    </div>

    <div class="home-content">
      <!-- 主内容区 -->
      <div class="main-area">
        <!-- 筛选栏：目的地 / 分类 / 天数（攻略特有的三个维度） -->
        <div class="filter-bar">
          <el-input
            v-model="filterDestination"
            placeholder="输入目的地，如 泉州"
            clearable
            class="filter-dest"
            @keyup.enter="applyFilters"
            @clear="applyFilters"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>

          <el-select
            v-model="filterCategoryId"
            placeholder="全部分类"
            clearable
            class="filter-cat"
            :loading="categoryLoading"
            @change="applyFilters"
          >
            <template v-for="cat in categories" :key="cat.id">
              <el-option-group v-if="cat.children && cat.children.length" :label="cat.name">
                <el-option v-for="sub in cat.children" :key="sub.id" :label="sub.name" :value="sub.id" />
              </el-option-group>
              <el-option v-else :label="cat.name" :value="cat.id" />
            </template>
          </el-select>

          <el-select v-model="filterDays" placeholder="全部天数" class="filter-days" @change="applyFilters">
            <el-option label="全部天数" :value="null" />
            <el-option label="1 天" :value="1" />
            <el-option label="2 天" :value="2" />
            <el-option label="3 天" :value="3" />
            <el-option label="4 天及以上" value="more" />
          </el-select>

          <el-button v-if="hasActiveFilter" link type="primary" @click="resetFilters">重置</el-button>
        </div>

        <!-- 搜索结果提示 -->
        <div class="search-result-bar" v-if="isSearchMode">
          <span class="search-text">搜索 "<em>{{ searchKeyword }}</em>" 的结果</span>
          <el-button type="primary" link @click="clearSearch">清除搜索</el-button>
        </div>

        <!-- Tab切换 -->
        <el-tabs v-model="activeTab" class="home-tabs" @tab-change="handleTabChange" v-if="!isSearchMode">
          <el-tab-pane label="推荐" name="recommend" />
          <el-tab-pane label="热门" name="hot" />
          <el-tab-pane label="最新" name="latest" />
        </el-tabs>

        <!-- 瀑布流 -->
        <div class="waterfall" v-loading="loading">
          <WorkCard v-for="work in works" :key="work.id" :work="work" />
        </div>

        <!-- 加载更多 -->
        <div class="load-more" ref="loadMoreRef">
          <el-button v-if="hasMore && !loading" type="primary" plain @click="loadMore">
            加载更多
          </el-button>
          <div v-else-if="!hasMore && works.length > 0" class="no-more">
            — 已经到底啦 —
          </div>
          <el-skeleton v-if="loading" :rows="3" animated />
        </div>

        <!-- 空状态 -->
        <el-empty v-if="!loading && works.length === 0" description="暂无攻略" />
      </div>

      <!-- 侧边栏 -->
      <aside class="sidebar hide-mobile">
        <!-- 热门排行 -->
        <div class="sidebar-card">
          <h3 class="card-title">
            <el-icon color="#f56c6c"><HotWater /></el-icon>
            热门排行榜
          </h3>
          <div class="rank-list" v-loading="hotLoading">
            <div
              v-for="(work, index) in hotWorks"
              :key="work.id"
              class="rank-item"
              @click="$router.push(`/work/${work.id}`)"
            >
              <span class="rank-num" :class="{ top: index < 3 }">{{ index + 1 }}</span>
              <img :src="work.coverUrl || defaultCover" :alt="work.title" class="rank-cover" />
              <div class="rank-info">
                <p class="rank-title">{{ work.title }}</p>
                <p class="rank-stats">
                  <el-icon><Star /></el-icon>{{ work.likeCount || 0 }}
                  <el-icon style="margin-left:8px"><View /></el-icon>{{ work.viewCount || 0 }}
                </p>
              </div>
            </div>
            <el-empty v-if="!hotLoading && hotWorks.length === 0" :image-size="60" description="暂无数据" />
          </div>
        </div>

        <!-- 推荐标签 -->
        <div class="sidebar-card">
          <h3 class="card-title">
            <el-icon color="#409eff"><CollectionTag /></el-icon>
            热门标签
          </h3>
          <div class="tag-cloud">
            <!--
              说明：这里只做展示。点击按标签筛选需要后端 /works/page 支持 tagId 参数，
              而 P0-C 的分页接口没有这个条件；旧代码里的 filterByTag 是个空函数（点了没反应），
              比一个会误导人的死按钮更糟，所以先改成纯展示。
              要恢复点击筛选：后端加 tagId 参数（join work_tag）后接上即可。
            -->
            <el-tag
              v-for="tag in hotTags"
              :key="tag.id"
              class="tag-item"
              effect="plain"
            >
              {{ tag.name }}
            </el-tag>
            <span v-if="hotTags.length === 0" class="tag-empty">暂无标签</span>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import WorkCard from '@/components/WorkCard.vue'
import { useUserStore } from '@/stores/user'
import { getFeed, getHotWorks } from '@/api/recommend'
import { getWorkList } from '@/api/work'
import { getAllTags } from '@/api/tag'
import { getCategoryTree } from '@/api/category'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const activeTab = ref('recommend')
const works = ref([])
const loading = ref(false)
const pageNum = ref(1)
// 每页 20（手册要求；旧值是 12）
const pageSize = ref(20)
const hasMore = ref(true)
const loadMoreRef = ref(null)
const searchKeyword = ref('')
const isSearchMode = ref(false)

// ---- 筛选条件：目的地 / 分类 / 天数 ----
const filterDestination = ref('')
const filterCategoryId = ref(null)
// null=不限 / 1 / 2 / 3 / 'more'（4 天及以上）
const filterDays = ref(null)
const categories = ref([])
const categoryLoading = ref(false)
const hasActiveFilter = computed(() =>
  !!filterDestination.value || filterCategoryId.value != null || filterDays.value != null
)

const hotWorks = ref([])
const hotLoading = ref(false)
const hotTags = ref([])

const defaultCover = 'data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iNDAwIiBoZWlnaHQ9IjMwMCIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj48cmVjdCB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIiBmaWxsPSIjZTZmMWZiIi8+PHRleHQgeD0iNTAlIiB5PSI1MCUiIGZvbnQtZmFtaWx5PSJBcmlhbCIgZm9udC1zaXplPSIxNiIgZmlsbD0iIzg1YjdlYiIgdGV4dC1hbmNob3I9Im1pZGRsZSIgZHk9Ii4zZW0iPuihjOi1sOmbhiDCtyDmlLvnlaXlm77niYc8L3RleHQ+PC9zdmc+'

onMounted(() => {
  // 从URL参数初始化tab
  if (route.query.tab) {
    activeTab.value = route.query.tab
  }
  // 从URL参数初始化搜索关键词
  if (route.query.keyword) {
    searchKeyword.value = route.query.keyword
    isSearchMode.value = true
  }
  fetchWorks()
  fetchHotWorks()
  fetchHotTags()
  fetchCategories()
  window.addEventListener('scroll', handleScroll)
})

onUnmounted(() => {
  window.removeEventListener('scroll', handleScroll)
})

watch(() => route.query.tab, (newTab) => {
  if (newTab && newTab !== activeTab.value) {
    activeTab.value = newTab
  }
})

watch(() => route.query.keyword, (newKeyword) => {
  if (newKeyword) {
    searchKeyword.value = newKeyword
    isSearchMode.value = true
  } else {
    searchKeyword.value = ''
    isSearchMode.value = false
  }
  works.value = []
  pageNum.value = 1
  hasMore.value = true
  fetchWorks()
})

function handleTabChange() {
  works.value = []
  pageNum.value = 1
  hasMore.value = true
  fetchWorks()
}

async function fetchWorks() {
  loading.value = true
  try {
    // 组装筛选参数（只有非空才带上，避免把空串当成筛选条件传给后端）
    const params = {
      pageNum: pageNum.value,
      pageSize: pageSize.value
    }
    if (filterDestination.value && filterDestination.value.trim()) {
      params.destination = filterDestination.value.trim()
    }
    if (filterCategoryId.value != null) {
      params.categoryId = filterCategoryId.value
    }
    if (filterDays.value === 'more') {
      // 「4 天及以上」用 minTripDays（后端 ≥N 天），不能塞给 tripDays（那是精确匹配）
      params.minTripDays = 4
    } else if (filterDays.value != null) {
      params.tripDays = filterDays.value
    }

    let res
    if (isSearchMode.value && searchKeyword.value) {
      // 搜索模式：关键词 + 筛选条件叠加
      res = await getWorkList({ ...params, keyword: searchKeyword.value })
    } else if (activeTab.value === 'hot') {
      res = await getHotWorks({ pageNum: pageNum.value, pageSize: pageSize.value, sortBy: 'hot' })
    } else if (activeTab.value === 'recommend' && userStore.isLoggedIn) {
      // 「推荐」是个性化结果，需要登录态；未登录时下面会回落到公开列表，
      // 否则匿名访客会拿到 401 并被 request.js 弹去登录页。
      res = await getFeed({ pageNum: pageNum.value, pageSize: pageSize.value })
    } else {
      res = await getWorkList(params)
    }
    const records = res.data.records || []
    works.value = [...works.value, ...records]
    hasMore.value = works.value.length < (res.data.total || 0)
  } catch (e) {
    // 错误已处理
  } finally {
    loading.value = false
  }
}

/** 筛选条件变化：重置分页后重新拉取（走公开的作品分页接口） */
function applyFilters() {
  isSearchMode.value = false
  works.value = []
  pageNum.value = 1
  hasMore.value = true
  fetchWorks()
}

function resetFilters() {
  filterDestination.value = ''
  filterCategoryId.value = null
  filterDays.value = null
  applyFilters()
}

async function fetchCategories() {
  categoryLoading.value = true
  try {
    const res = await getCategoryTree()
    categories.value = res.data || []
  } catch (e) {
    // 错误已处理
  } finally {
    categoryLoading.value = false
  }
}

async function fetchHotWorks() {
  hotLoading.value = true
  try {
    const res = await getHotWorks({ pageNum: 1, pageSize: 10, sortBy: 'hot' })
    hotWorks.value = res.data.records || []
  } catch (e) {
    // 忽略
  } finally {
    hotLoading.value = false
  }
}

async function fetchHotTags() {
  try {
    // 用公开接口 /tags/hot（getAllTags），不用 /tags/page ——
    // 后者从 P0-C 起需要登录，而侧边栏在未登录的首页也要看得到
    const res = await getAllTags()
    hotTags.value = res.data || []
  } catch (e) {
    // 忽略
  }
}

function loadMore() {
  if (!hasMore.value || loading.value) return
  pageNum.value++
  fetchWorks()
}

function handleScroll() {
  if (!loadMoreRef.value) return
  const rect = loadMoreRef.value.getBoundingClientRect()
  if (rect.top < window.innerHeight + 200 && hasMore.value && !loading.value) {
    loadMore()
  }
}

function clearSearch() {
  searchKeyword.value = ''
  isSearchMode.value = false
  works.value = []
  pageNum.value = 1
  hasMore.value = true
  // 清除URL中的keyword参数
  router.push({ path: '/', query: { ...route.query, keyword: undefined } })
  fetchWorks()
}
</script>

<style scoped lang="scss">
.home-page {
  padding-bottom: 40px;
}

.search-result-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: #ecf5ff;
  border-radius: 8px;
  margin-bottom: 16px;

  .search-text {
    font-size: 14px;
    color: #606266;

    em {
      color: #409eff;
      font-style: normal;
      font-weight: 600;
    }
  }
}

.hero-banner {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 12px;
  padding: 48px 40px;
  margin-bottom: 24px;
  color: #fff;
  position: relative;
  overflow: hidden;

  &::after {
    content: '';
    position: absolute;
    right: -50px;
    top: -50px;
    width: 200px;
    height: 200px;
    background: rgba(255,255,255,0.1);
    border-radius: 50%;
  }

  h1 {
    font-size: 32px;
    margin: 0 0 12px;
  }

  p {
    font-size: 16px;
    opacity: 0.9;
    margin: 0 0 24px;
  }
}

.home-content {
  display: flex;
  gap: 24px;
  align-items: flex-start;
}

.main-area {
  flex: 1;
  min-width: 0;
}

.home-tabs {
  margin-bottom: 16px;

  :deep(.el-tabs__nav-wrap::after) {
    display: none;
  }
}

// 筛选栏：目的地 / 分类 / 天数
.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  background: #fff;
  border-radius: 10px;
  padding: 12px 14px;
  margin-bottom: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.04);

  .filter-dest {
    flex: 1;
    min-width: 180px;
  }

  .filter-cat {
    width: 160px;
  }

  .filter-days {
    width: 150px;
  }
}

.waterfall {
  column-count: 3;
  column-gap: 16px;
}

.load-more {
  text-align: center;
  padding: 24px 0;
}

.no-more {
  color: #c0c4cc;
  font-size: 14px;
}

.sidebar {
  width: 300px;
  flex-shrink: 0;
  position: sticky;
  top: 80px;
}

.sidebar-card {
  background: #fff;
  border-radius: 10px;
  padding: 18px;
  margin-bottom: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.04);
}

.card-title {
  font-size: 16px;
  font-weight: 600;
  margin: 0 0 16px;
  display: flex;
  align-items: center;
  gap: 6px;
  color: #303133;
}

.rank-list {
  .rank-item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px 0;
    cursor: pointer;
    border-bottom: 1px solid #f5f5f5;

    &:last-child {
      border-bottom: none;
    }

    &:hover .rank-title {
      color: #409eff;
    }
  }
}

.rank-num {
  width: 20px;
  text-align: center;
  font-size: 14px;
  font-weight: 700;
  color: #c0c4cc;

  &.top {
    color: #f56c6c;
  }
}

.rank-cover {
  width: 50px;
  height: 50px;
  object-fit: cover;
  border-radius: 6px;
  flex-shrink: 0;
}

.rank-info {
  flex: 1;
  min-width: 0;
}

.rank-title {
  font-size: 13px;
  color: #303133;
  margin: 0 0 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color 0.2s;
}

.rank-stats {
  font-size: 12px;
  color: #909399;
  margin: 0;
  display: flex;
  align-items: center;
}

.tag-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;

  .tag-item {
    cursor: pointer;
    transition: all 0.2s;

    &:hover {
      background: #409eff;
      color: #fff;
      border-color: #409eff;
    }
  }
}

// 响应式
@media (max-width: 1024px) {
  .waterfall {
    column-count: 2;
  }
}

@media (max-width: 768px) {
  .home-page {
    padding: 0 12px;
  }
  .waterfall {
    column-count: 2;
    column-gap: 10px;
  }
  .home-tabs {
    :deep(.el-tabs__item) {
      padding: 0 16px;
    }
  }
  // 窄屏：筛选栏换行，三个控件各占满一行宽度，避免被挤成一条缝
  .filter-bar {
    padding: 10px;
    gap: 8px;

    .filter-dest,
    .filter-cat,
    .filter-days {
      width: 100%;
      flex: none;
    }
  }
}

.tag-empty {
  font-size: 13px;
  color: #c0c4cc;
}
</style>
