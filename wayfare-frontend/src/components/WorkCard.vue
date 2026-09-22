<template>
  <div class="work-card" @click="goDetail">
    <div class="card-cover">
      <img :src="work.coverUrl || work.imageUrls?.[0] || defaultCover" :alt="work.title" loading="lazy" />
      <!-- AI 生成角标（P5-C）：让用户能区分人工原创与 AI 辅助生成 ——
           这是内容诚信，也是本项目的差异化卖点。数据来自后端回填的 aiGenerated
           （work 表没有 trip_id，关联存在反方向的 trip.work_id 上） -->
      <div v-if="work.aiGenerated" class="ai-badge" title="这条攻略由 AI 行程规划生成">
        <el-icon><MagicStick /></el-icon><span>AI 生成</span>
      </div>
      <div class="card-overlay">
        <div class="overlay-stats">
          <span><el-icon><View /></el-icon>{{ work.viewCount || 0 }}</span>
          <span><el-icon><Star /></el-icon>{{ work.likeCount || 0 }}</span>
        </div>
      </div>
    </div>
    <div class="card-info">
      <h3 class="card-title">{{ work.title }}</h3>

      <!-- 攻略特有的两个维度：目的地 + 天数（摄影作品没有这两个字段） -->
      <div class="card-meta">
        <span v-if="work.destination" class="meta-item meta-dest">
          <el-icon><LocationInformation /></el-icon>{{ work.destination }}
        </span>
        <span v-if="work.tripDays" class="meta-item meta-days">{{ work.tripDays }} 天</span>
      </div>

      <div class="card-tags" v-if="work.tags?.length">
        <el-tag v-for="tag in work.tags.slice(0, 3)" :key="tag.id" size="small" type="info" effect="plain">
          {{ tag.name }}
        </el-tag>
      </div>
      <div class="card-author" @click.stop="goUser">
        <el-avatar :size="24" :src="work.author?.avatar">
          {{ work.author?.nickname?.charAt(0) }}
        </el-avatar>
        <span class="author-name">{{ work.author?.nickname || work.author?.username }}</span>
        <!-- 点赞/收藏数常显（不放在 hover 遮罩里，否则手机上根本看不到） -->
        <span class="card-stats">
          <span title="点赞"><el-icon><Star /></el-icon>{{ work.likeCount || 0 }}</span>
          <span title="收藏"><el-icon><Collection /></el-icon>{{ work.collectCount || 0 }}</span>
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'

const props = defineProps({
  work: {
    type: Object,
    required: true
  }
})

const router = useRouter()
const defaultCover = 'data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iNDAwIiBoZWlnaHQ9IjMwMCIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj48cmVjdCB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIiBmaWxsPSIjZTZmMWZiIi8+PHRleHQgeD0iNTAlIiB5PSI1MCUiIGZvbnQtZmFtaWx5PSJBcmlhbCIgZm9udC1zaXplPSIxNiIgZmlsbD0iIzg1YjdlYiIgdGV4dC1hbmNob3I9Im1pZGRsZSIgZHk9Ii4zZW0iPuihjOi1sOmbhiDCtyDmlLvnlaXlm77niYc8L3RleHQ+PC9zdmc+'

function goDetail() {
  router.push(`/work/${props.work.id}`)
}

function goUser() {
  if (props.work.author?.id) {
    router.push(`/user/${props.work.author.id}`)
  }
}
</script>

<style scoped lang="scss">
.work-card {
  background: #fff;
  border-radius: 10px;
  overflow: hidden;
  cursor: pointer;
  transition: transform 0.2s, box-shadow 0.2s;
  break-inside: avoid;
  margin-bottom: 16px;

  &:hover {
    transform: translateY(-4px);
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
  }
}

.card-cover {
  position: relative;
  width: 100%;
  overflow: hidden;

  img {
    width: 100%;
    height: auto;
    display: block;
    transition: transform 0.3s;
  }

  &:hover img {
    transform: scale(1.05);
  }
}

// AI 生成角标（P5-C）：常显，不放进 hover 遮罩 —— 手机上 hover 根本触发不了
.ai-badge {
  position: absolute;
  top: 8px;
  right: 8px;
  display: flex;
  align-items: center;
  gap: 3px;
  padding: 2px 8px;
  font-size: 12px;
  line-height: 1.6;
  color: #fff;
  background: rgba(64, 158, 255, 0.92);
  border-radius: 10px;
}

.card-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: linear-gradient(to bottom, transparent 60%, rgba(0,0,0,0.5));
  opacity: 0;
  transition: opacity 0.2s;
  display: flex;
  align-items: flex-end;
  padding: 12px;

  .work-card:hover & {
    opacity: 1;
  }
}

.overlay-stats {
  display: flex;
  gap: 16px;
  color: #fff;
  font-size: 13px;

  span {
    display: flex;
    align-items: center;
    gap: 4px;
  }
}

.card-info {
  padding: 12px 14px;
}

.card-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  margin: 0 0 8px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 8px;

  .meta-item {
    display: inline-flex;
    align-items: center;
    gap: 3px;
    font-size: 12px;
    color: #606266;
  }

  .meta-dest {
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .meta-days {
    color: #e6a23c;
    background: #fdf6ec;
    padding: 1px 6px;
    border-radius: 8px;
  }
}

.card-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.card-author {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 10px;
  border-top: 1px solid #f0f0f0;
}

.card-stats {
  margin-left: auto;
  display: flex;
  gap: 10px;
  font-size: 12px;
  color: #909399;

  span {
    display: inline-flex;
    align-items: center;
    gap: 3px;
  }
}

.author-name {
  font-size: 13px;
  color: #909399;
}

@media (max-width: 768px) {
  .work-card {
    margin-bottom: 10px;
    border-radius: 8px;
  }
  .card-info {
    padding: 10px 12px;
  }
  .card-title {
    font-size: 14px;
  }
}
</style>
