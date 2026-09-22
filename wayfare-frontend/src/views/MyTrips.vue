<template>
  <div class="my-trips container">
    <div class="page-head">
      <h2 class="page-title">我的行程</h2>
      <el-button type="primary" @click="$router.push('/plan')">
        <el-icon><Plus /></el-icon>规划新行程
      </el-button>
    </div>

    <div v-loading="loading" class="trip-list">
      <div
        v-for="trip in trips"
        :key="trip.id"
        class="trip-item"
        @click="openTrip(trip)"
      >
        <div class="trip-main">
          <div class="trip-title-row">
            <span class="trip-title">{{ trip.title || '未命名行程' }}</span>
            <!-- 地图可信度角标：与结果页同一套三色口径 -->
            <el-tag size="small" :type="modeTagType(trip.mapMode)" effect="light">
              {{ modeText(trip.mapMode) }}
            </el-tag>
            <el-tag v-if="trip.workId" size="small" type="primary" effect="plain">已发布</el-tag>
          </div>
          <div class="trip-meta">
            <span v-if="trip.destination"><el-icon><LocationInformation /></el-icon>{{ trip.destination }}</span>
            <span v-if="trip.days">{{ trip.days }} 天</span>
            <span v-if="trip.startDate">{{ trip.startDate }} 出发</span>
            <span class="trip-time">{{ formatTime(trip.createdAt) }}</span>
          </div>
        </div>
        <el-icon class="trip-arrow"><ArrowRight /></el-icon>
      </div>

      <!-- 空状态：给引导，不给一句「暂无数据」就完事（P5-D 验收 3） -->
      <el-empty v-if="!loading && trips.length === 0" description="还没有行程">
        <p class="empty-hint">
          用一句话描述你的旅行想法，AI 会帮你检索真实景点、排好每天的路线，
          并标出哪些距离是实测的、哪些是估算的。
        </p>
        <el-button type="primary" @click="$router.push('/plan')">试试 AI 规划你的行程</el-button>
      </el-empty>
    </div>

    <div class="load-more" v-if="hasMore && trips.length">
      <el-button plain :loading="loading" @click="loadMore">加载更多</el-button>
    </div>

    <!-- 行程详情弹窗：复用 TripTimeline 的只读模式，与攻略详情页同一份渲染 -->
    <el-dialog v-model="detailVisible" :title="detail?.title || '行程详情'" width="760px" top="6vh">
      <div v-loading="detailLoading">
        <div v-if="detail" class="detail-head">
          <span>{{ detail.destination || '' }}</span>
          <span v-if="detail.days"> · {{ detail.days }} 天</span>
          <span v-if="detail.modelName" class="detail-model"> · 由 {{ detail.modelName }} 生成</span>
          <span v-if="detail.generationRounds > 0"> · 重排 {{ detail.generationRounds }} 轮</span>
        </div>
        <div v-if="detail && detail.guideText" class="detail-guide">{{ detail.guideText }}</div>
        <TripTimeline v-if="detail" :days="detail.dayPlans || []" />
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import TripTimeline from '@/components/TripTimeline.vue'
import { myTrips, getTrip } from '@/api/trip'

const trips = ref([])
const loading = ref(false)
const pageNum = ref(1)
const hasMore = ref(false)

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

onMounted(fetchTrips)

async function fetchTrips() {
  loading.value = true
  try {
    const res = await myTrips({ pageNum: pageNum.value, pageSize: 10 })
    const records = res.data.records || []
    trips.value = pageNum.value === 1 ? records : [...trips.value, ...records]
    hasMore.value = trips.value.length < (res.data.total || 0)
  } catch (e) {
    // 错误已由 request 拦截器统一提示
  } finally {
    loading.value = false
  }
}

function loadMore() {
  pageNum.value++
  fetchTrips()
}

async function openTrip(trip) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    const res = await getTrip(trip.id)
    detail.value = res.data
  } catch (e) {
    ElMessage.error('行程详情加载失败')
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

function modeTagType(mode) {
  if (mode === 'VERIFIED') return 'success'
  if (mode === 'CACHED') return 'info'
  if (mode === 'ESTIMATED') return 'warning'
  return 'info'
}

function modeText(mode) {
  if (mode === 'VERIFIED') return '实测'
  if (mode === 'CACHED') return '缓存'
  if (mode === 'ESTIMATED') return '估算'
  return '未知'
}

function formatTime(t) {
  if (!t) return ''
  // 后端返回的是 ISO 本地时间（2026-09-22T18:48:45），截到分钟即可
  return String(t).replace('T', ' ').slice(0, 16)
}
</script>

<style scoped lang="scss">
.my-trips {
  padding-bottom: 40px;
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
  gap: 12px;
  flex-wrap: wrap;
}

.page-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: #303133;
}

.trip-list {
  background: #fff;
  border-radius: 10px;
  padding: 8px 20px;
  min-height: 200px;
}

.trip-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 0;
  border-bottom: 1px solid #f5f5f5;
  cursor: pointer;

  &:last-child {
    border-bottom: none;
  }

  &:hover .trip-arrow {
    color: #409eff;
  }
}

.trip-main {
  flex: 1;
  min-width: 0;
}

.trip-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}

.trip-title {
  font-size: 15px;
  font-weight: 500;
  color: #303133;
}

.trip-meta {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  font-size: 13px;
  color: #909399;

  span {
    display: flex;
    align-items: center;
    gap: 3px;
  }

  .trip-time {
    margin-left: auto;
  }
}

.trip-arrow {
  color: #c0c4cc;
  transition: color 0.2s;
}

.empty-hint {
  max-width: 420px;
  margin: 8px auto 16px;
  font-size: 13px;
  line-height: 1.7;
  color: #909399;
}

.load-more {
  text-align: center;
  padding: 20px 0;
}

.detail-head {
  font-size: 13px;
  color: #606266;
  margin-bottom: 10px;

  .detail-model {
    color: #909399;
  }
}

.detail-guide {
  font-size: 14px;
  line-height: 1.9;
  color: #303133;
  white-space: pre-wrap;
  background: #f5f7fa;
  border-radius: 8px;
  padding: 12px 14px;
  margin-bottom: 16px;
}

@media (max-width: 768px) {
  .trip-list {
    padding: 4px 12px;
  }
  .trip-meta .trip-time {
    margin-left: 0;
  }
}
</style>
