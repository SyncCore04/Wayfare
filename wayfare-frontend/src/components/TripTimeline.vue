<template>
  <div class="trip-timeline">
    <el-tabs v-model="activeDay" class="day-tabs">
      <el-tab-pane
        v-for="day in days"
        :key="day.dayIndex"
        :label="`第 ${day.dayIndex} 天`"
        :name="String(day.dayIndex)"
      >
        <div class="day-card">
          <h4 class="day-title">{{ day.title || `第 ${day.dayIndex} 天` }}</h4>
          <p v-if="day.summary" class="day-summary">{{ day.summary }}</p>

          <div v-if="!day.items || !day.items.length" class="day-empty">这一天还没有安排</div>

          <div
            v-for="(item, idx) in day.items"
            :key="item.poiUid || item.poiRef || idx"
            class="item-row"
          >
            <div class="item-time">
              <span>{{ item.startTime || '--:--' }}</span>
              <span class="time-sep">-</span>
              <span>{{ item.endTime || '--:--' }}</span>
              <span v-if="item.stayMinutes" class="stay">{{ item.stayMinutes }} 分钟</span>
            </div>

            <div class="item-main">
              <div class="item-head">
                <span class="item-name">{{ item.poiName || item.poiRef }}</span>
                <!-- 来源角标：把「事实数据永不来自大模型」可视化 -->
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

            <div v-if="editable" class="item-actions">
              <el-button link size="small" :disabled="idx === 0" @click="emit('move', day.dayIndex, idx, -1)">上移</el-button>
              <el-button link size="small" :disabled="idx === day.items.length - 1" @click="emit('move', day.dayIndex, idx, 1)">下移</el-button>
              <el-button link size="small" type="danger" @click="emit('remove', day.dayIndex, idx)">删除</el-button>
            </div>
          </div>

          <div v-if="editable" class="day-actions">
            <el-button size="small" plain @click="emit('replan', day.dayIndex)">这一天太赶，重新排</el-button>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'

/**
 * 行程时间轴（P5-C 从 TripResult 抽出来，供两处共用）。
 *
 * 【两处用法，靠 editable 区分】
 *   - P5-B 的规划结果页：editable=true，上移/下移/删除/重排都可用
 *   - 攻略详情页：只读（默认），行程随攻略一起公开给所有人看，不该有编辑入口
 *
 * 【为什么编辑操作要 emit 出去而不是直接改 props】
 * props 是父组件的响应式数组，子组件直接改它虽然能生效，但会让「谁改了数据」变得不可追踪。
 * 统一 emit 回父组件，父组件持有唯一的可编辑副本 —— 这也是「保存顺序」能工作的前提。
 */
const props = defineProps({
  /** 天列表（TripDay 或 TripDraftDTO.DayDraft 的形状一致） */
  days: { type: Array, default: () => [] },
  /** 是否可编辑；默认 false（详情页只读） */
  editable: { type: Boolean, default: false }
})

const emit = defineEmits(['move', 'remove', 'replan'])

const activeDay = ref('1')

// 数据换了（比如重新生成）就回到第一天；只在当前选中项已不存在时才跳
watch(() => props.days, (list) => {
  const days = list || []
  if (days.length && !days.some(d => String(d.dayIndex) === activeDay.value)) {
    activeDay.value = String(days[0].dayIndex)
  }
}, { immediate: true })

/** 来源角标：绿实测 / 灰缓存 / 橙估算 / 蓝手动 */
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
</script>

<style scoped lang="scss">
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
}
</style>
