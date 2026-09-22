<template>
  <div class="trip-result">
    <!-- ① 顶部状态条：地图可信度一眼可见（P3 的数据诚信机制在这里落地） -->
    <div class="status-bar" :class="statusClass">
      <el-icon class="status-icon"><component :is="statusIcon" /></el-icon>
      <span>{{ statusText }}</span>
      <span v-if="meta && meta.durationMs" class="status-meta">
        耗时 {{ (meta.durationMs / 1000).toFixed(1) }} 秒
        <template v-if="meta.rounds != null"> · 重排 {{ meta.rounds }} 轮</template>
      </span>
    </div>

    <!-- ② 需要用户知道的三类问题，都如实展示，不藏 -->
    <el-alert
      v-if="shortage"
      type="warning"
      :closable="false"
      show-icon
      class="notice"
      title="该目的地可玩点位较少，已为你放宽检索范围"
      :description="shortageHint || '候选池不足，部分点位可能不够贴合你的偏好'"
    />
    <el-alert
      v-if="violations.length"
      type="warning"
      :closable="false"
      show-icon
      class="notice"
      title="行程里有未完全解决的问题"
    >
      <ul class="violation-list">
        <li v-for="(v, i) in violations" :key="i">{{ v.message || v.rule || v }}</li>
      </ul>
    </el-alert>

    <!-- ③ 画像使用可见性：让用户看到「偏好真的被用了」（而不是靠感觉） -->
    <div v-if="profileUsed" class="profile-hint">
      <el-icon><User /></el-icon>
      <span>本次已参考你的偏好：{{ profileSummary || '已启用画像' }}</span>
    </div>

    <!-- ④ 地图区 -->
    <div class="map-panel">
      <div v-if="!hasCoords" class="map-placeholder">
        <el-icon :size="28"><MapLocation /></el-icon>
        <p>{{ coordPlaceholder }}</p>
      </div>
      <div v-else-if="!mapAk" class="map-placeholder">
        <el-icon :size="28"><MapLocation /></el-icon>
        <p>已有点位坐标（{{ coordCount }} 个），但未配置前端地图 AK，暂不渲染地图。</p>
      </div>
      <div v-else ref="mapEl" class="map-canvas"></div>
    </div>

    <!-- ⑤ 时间轴：按天分页 -->
    <el-tabs v-model="activeDay" class="day-tabs">
      <el-tab-pane
        v-for="day in localDays"
        :key="day.dayIndex"
        :label="`第 ${day.dayIndex} 天`"
        :name="String(day.dayIndex)"
      >
        <div class="day-card">
          <h4 class="day-title">{{ day.title || `第 ${day.dayIndex} 天` }}</h4>
          <p v-if="day.summary" class="day-summary">{{ day.summary }}</p>

          <div v-if="!day.items || !day.items.length" class="day-empty">这一天还没有安排</div>

          <div v-for="(item, idx) in day.items" :key="item.poiUid || item.poiRef || idx" class="item-row">
            <div class="item-time">
              <span>{{ item.startTime || '--:--' }}</span>
              <span class="time-sep">-</span>
              <span>{{ item.endTime || '--:--' }}</span>
              <span v-if="item.stayMinutes" class="stay">{{ item.stayMinutes }} 分钟</span>
            </div>

            <div class="item-main">
              <div class="item-head">
                <span class="item-name">{{ item.poiName || item.poiRef }}</span>
                <!-- 来源角标：这是「事实数据永不来自大模型」的可视化出口 -->
                <el-tag size="small" :type="badgeType(item.verifyStatus)" effect="light">
                  {{ badgeText(item.verifyStatus) }}
                </el-tag>
                <el-tag v-if="item.itemType === 'FOOD'" size="small" type="success" effect="plain">餐饮</el-tag>
              </div>
              <p v-if="item.reason" class="item-reason">{{ item.reason }}</p>
              <p v-if="item.note" class="item-note">{{ item.note }}</p>
              <p v-if="item.address" class="item-address">{{ item.address }}</p>
            </div>

            <div class="item-cost">
              <span v-if="item.costEstimate != null">约 {{ item.costEstimate }} 元</span>
              <span v-else class="unknown">花费未知</span>
            </div>

            <div class="item-actions">
              <el-button link size="small" :disabled="idx === 0" @click="move(day, idx, -1)">上移</el-button>
              <el-button link size="small" :disabled="idx === day.items.length - 1" @click="move(day, idx, 1)">下移</el-button>
              <el-button link size="small" type="danger" @click="removeItem(day, idx)">删除</el-button>
            </div>
          </div>

          <div class="day-actions">
            <el-button size="small" plain @click="emit('replan', day.dayIndex)">这一天太赶，重新排</el-button>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- ⑥ 攻略文案（打字机：边收边渲染） -->
    <div class="copy-panel">
      <div class="copy-head">
        <h4>攻略文案</h4>
        <span v-if="copyStreaming" class="copy-status">正在生成…</span>
      </div>
      <p v-if="copyText" class="copy-text">{{ copyText }}<span v-if="copyStreaming" class="cursor">▌</span></p>
      <p v-else-if="copyStreaming" class="copy-text"><span class="cursor">▌</span></p>
      <el-empty v-else description="还没有文案" :image-size="60" />
    </div>

    <!-- ⑦ 费用汇总：只汇总有数据的，缺什么就说什么 -->
    <div class="cost-panel">
      <h4>费用汇总</h4>
      <div class="cost-grid">
        <div class="cost-item">
          <span class="cost-label">餐饮</span>
          <span class="cost-value">{{ cost.food }} 元</span>
        </div>
        <div class="cost-item">
          <span class="cost-label">其它花费</span>
          <span class="cost-value">{{ cost.other }} 元</span>
        </div>
        <div class="cost-item total">
          <span class="cost-label">合计</span>
          <span class="cost-value">{{ cost.total }} 元</span>
        </div>
      </div>
      <p class="cost-note">
        以上只汇总行程里带花费估算的条目；<b>住宿与交通没有数据，未计入</b>。
        估算模式下这些数字本身也是模型给的粗略值，不是实测票价。
      </p>
    </div>

    <!-- ⑧ 底部动作 -->
    <div class="footer-actions">
      <el-button :loading="savingOrder" @click="saveOrder">保存顺序</el-button>
      <el-button :loading="regenerating" @click="emit('regenerate-copy')">重新生成文案</el-button>
      <el-button type="primary" @click="emit('publish')">发布为攻略</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  draft: { type: Object, default: null },
  intent: { type: Object, default: null },
  meta: { type: Object, default: null },
  tripId: { type: [Number, String], default: null },
  copyText: { type: String, default: '' },
  copyStreaming: { type: Boolean, default: false },
  shortage: { type: Boolean, default: false },
  shortageHint: { type: String, default: '' },
  validation: { type: Array, default: () => [] },
  profileUsed: { type: Number, default: 0 },
  profileSummary: { type: String, default: '' },
  savingOrder: { type: Boolean, default: false },
  regenerating: { type: Boolean, default: false }
})

const emit = defineEmits(['replan', 'regenerate-copy', 'publish', 'save-order'])

// 本地可编辑副本：编辑（上移/下移/删除）先动这里，点「保存顺序」才提交后端
const localDays = ref([])
const activeDay = ref('1')

watch(() => props.draft, (d) => {
  const days = (d && d.days) ? JSON.parse(JSON.stringify(d.days)) : []
  localDays.value = days
  if (days.length && !days.some(x => String(x.dayIndex) === activeDay.value)) {
    activeDay.value = String(days[0].dayIndex)
  }
}, { immediate: true })

const violations = computed(() => props.validation || [])

// ---------- 地图模式（三色状态条）----------
const mapMode = computed(() => (props.meta && props.meta.mapMode) || null)

const statusClass = computed(() => {
  if (mapMode.value === 'VERIFIED') return 'ok'
  if (mapMode.value === 'CACHED') return 'cached'
  if (mapMode.value === 'ESTIMATED') return 'estimated'
  return 'unknown'
})

const statusIcon = computed(() => {
  if (mapMode.value === 'VERIFIED') return 'CircleCheck'
  if (mapMode.value === 'CACHED') return 'Clock'
  if (mapMode.value === 'ESTIMATED') return 'Warning'
  return 'InfoFilled'
})

const statusText = computed(() => {
  if (mapMode.value === 'VERIFIED') return '距离与时长为地图实测'
  if (mapMode.value === 'CACHED') return '部分数据来自本地缓存'
  if (mapMode.value === 'ESTIMATED') return '未启用地图校验，里程与时间为估算值'
  return '地图状态未知'
})

// ---------- 地图区 ----------
const mapEl = ref(null)
const mapAk = import.meta.env.VITE_BAIDU_MAP_AK || ''

const allItems = computed(() => {
  const list = []
  for (const day of localDays.value) {
    for (const item of day.items || []) list.push(item)
  }
  return list
})

const coordItems = computed(() => allItems.value.filter(i => i.lng != null && i.lat != null))
const hasCoords = computed(() => coordItems.value.length > 0)
const coordCount = computed(() => coordItems.value.length)

const coordPlaceholder = computed(() => {
  if (mapMode.value === 'ESTIMATED') {
    return '未启用地图校验，本次行程没有实测坐标 —— 因此也不显示地图，而不是给你一张空白地图。'
  }
  return '本次行程没有可用的点位坐标。'
})

// ---------- 来源角标 ----------
function badgeType(status) {
  if (status === 'VERIFIED') return 'success'
  if (status === 'CACHED') return 'info'
  if (status === 'ESTIMATED') return 'warning'
  if (status === 'USER') return 'primary'
  return 'info'
}

function badgeText(status) {
  if (status === 'VERIFIED') return '实测'
  if (status === 'CACHED') return '缓存'
  if (status === 'ESTIMATED') return '估算'
  if (status === 'USER') return '手动'
  return '未知来源'
}

// ---------- 编辑 ----------
function move(day, idx, delta) {
  const items = day.items
  const target = idx + delta
  if (target < 0 || target >= items.length) return
  const tmp = items[idx]
  items[idx] = items[target]
  items[target] = tmp
}

function removeItem(day, idx) {
  day.items.splice(idx, 1)
  ElMessage.info('已从当前视图移除，点「保存顺序」后生效')
}

function saveOrder() {
  const payload = localDays.value.map(d => ({
    dayIndex: d.dayIndex,
    itemIds: (d.items || []).map(i => i.id).filter(id => id != null)
  }))
  // 有 id 才说明这条行程已落库（生成完成时后端已组装并保存）
  if (!payload.some(p => p.itemIds.length)) {
    ElMessage.warning('这条行程还没有落库的条目，无法保存顺序')
    return
  }
  emit('save-order', payload)
}

// ---------- 费用汇总 ----------
const cost = computed(() => {
  let food = 0
  let other = 0
  for (const item of allItems.value) {
    if (item.costEstimate == null) continue
    const v = Number(item.costEstimate) || 0
    if (item.itemType === 'FOOD') food += v
    else other += v
  }
  const round = n => Math.round(n * 100) / 100
  return { food: round(food), other: round(other), total: round(food + other) }
})
</script>

<style scoped lang="scss">
.trip-result {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.status-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 14px;

  &.ok { background: #f0f9eb; color: #529b2e; }
  &.cached { background: #ecf5ff; color: #337ecc; }
  &.estimated { background: #fdf6ec; color: #b88230; }
  &.unknown { background: #f4f4f5; color: #909399; }

  .status-meta {
    margin-left: auto;
    font-size: 12px;
    opacity: 0.8;
  }
}

.notice {
  margin: 0;
}

.violation-list {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.7;
}

.profile-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #606266;
  background: #f5f7fa;
  padding: 8px 12px;
  border-radius: 6px;
}

.map-panel {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  overflow: hidden;
}

.map-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 32px 20px;
  color: #909399;
  background: #fafafa;

  p {
    margin: 0;
    font-size: 13px;
    line-height: 1.7;
    max-width: 460px;
    text-align: center;
  }
}

.map-canvas {
  width: 100%;
  height: 360px;
}

.day-tabs {
  :deep(.el-tabs__header) {
    margin-bottom: 12px;
  }
}

.day-card {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 16px;
}

.day-title {
  margin: 0 0 6px;
  font-size: 15px;
  font-weight: 500;
  color: #303133;
}

.day-summary {
  margin: 0 0 14px;
  font-size: 13px;
  color: #909399;
  line-height: 1.7;
}

.day-empty {
  padding: 20px;
  text-align: center;
  font-size: 13px;
  color: #c0c4cc;
}

.item-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 0;
  border-top: 1px solid #f5f5f5;

  &:first-of-type {
    border-top: none;
  }
}

.item-time {
  flex: 0 0 96px;
  font-size: 13px;
  color: #606266;
  line-height: 1.6;

  .time-sep {
    margin: 0 2px;
    color: #c0c4cc;
  }

  .stay {
    display: block;
    font-size: 12px;
    color: #909399;
  }
}

.item-main {
  flex: 1;
  min-width: 0;
}

.item-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.item-name {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.item-reason {
  margin: 4px 0 0;
  font-size: 13px;
  color: #606266;
  line-height: 1.7;
}

.item-note {
  margin: 4px 0 0;
  font-size: 12px;
  color: #b88230;
  line-height: 1.6;
}

.item-address {
  margin: 4px 0 0;
  font-size: 12px;
  color: #909399;
}

.item-cost {
  flex: 0 0 84px;
  text-align: right;
  font-size: 13px;
  color: #606266;

  .unknown {
    color: #c0c4cc;
  }
}

.item-actions {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.day-actions {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed #ebeef5;
}

.copy-panel,
.cost-panel {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 16px;

  h4 {
    margin: 0 0 10px;
    font-size: 14px;
    font-weight: 500;
    color: #303133;
  }
}

.copy-head {
  display: flex;
  align-items: center;
  gap: 10px;

  .copy-status {
    font-size: 12px;
    color: #409eff;
  }
}

.copy-text {
  margin: 0;
  font-size: 14px;
  line-height: 1.9;
  color: #303133;
  white-space: pre-wrap;
}

.cursor {
  color: #409eff;
  animation: blink 1s steps(1) infinite;
}

@keyframes blink {
  50% { opacity: 0; }
}

.cost-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.cost-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  background: #f5f7fa;
  border-radius: 6px;

  &.total {
    background: #ecf5ff;
  }

  .cost-label {
    font-size: 12px;
    color: #909399;
  }

  .cost-value {
    font-size: 18px;
    font-weight: 500;
    color: #303133;
  }
}

.cost-note {
  margin: 12px 0 0;
  font-size: 12px;
  color: #909399;
  line-height: 1.7;
}

.footer-actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
  padding-top: 4px;
}

@media (max-width: 768px) {
  .item-row {
    flex-wrap: wrap;
  }
  .item-time {
    flex: 0 0 auto;
  }
  .item-actions {
    flex-direction: row;
    width: 100%;
    justify-content: flex-end;
  }
  .cost-grid {
    grid-template-columns: 1fr;
  }
  .footer-actions {
    flex-direction: column;

    .el-button {
      width: 100%;
      margin-left: 0;
    }
  }
}
</style>
