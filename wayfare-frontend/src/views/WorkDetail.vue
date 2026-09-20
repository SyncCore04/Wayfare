<template>
  <div class="work-detail container" v-loading="loading">
    <template v-if="work">
      <div class="detail-layout">
        <!-- 左侧：图片展示 -->
        <div class="image-section">
          <el-carousel :interval="0" arrow="always" class="image-carousel" v-if="images.length > 1">
            <el-carousel-item v-for="(img, idx) in images" :key="idx">
              <img :src="img" :alt="work.title" class="detail-image" />
            </el-carousel-item>
          </el-carousel>
          <img v-else :src="images[0] || defaultCover" :alt="work.title" class="detail-image single" />
        </div>

        <!-- 右侧：信息区 -->
        <div class="info-section">
          <h1 class="work-title">{{ work.title }}</h1>

          <!-- 作者信息 -->
          <div class="author-bar">
            <div class="author-info" @click="goUserHome">
              <el-avatar :size="44" :src="work.author?.avatar">
                {{ work.author?.nickname?.charAt(0) }}
              </el-avatar>
              <div class="author-text">
                <span class="author-name">{{ work.author?.nickname || work.author?.username }}</span>
                <span class="publish-time">{{ formatTime(work.publishedAt || work.createdAt) }}</span>
              </div>
            </div>
            <el-button
              v-if="!isOwner && userStore.isLoggedIn"
              :type="isFollowing ? 'default' : 'primary'"
              :icon="isFollowing ? 'Minus' : 'Plus'"
              @click="handleFollow"
            >
              {{ isFollowing ? '已关注' : '关注' }}
            </el-button>
          </div>

          <!-- 攻略信息：目的地 + 行程天数 -->
          <div class="trip-meta" v-if="work.destination || work.tripDays">
            <span v-if="work.destination" class="meta-item">
              <el-icon><LocationInformation /></el-icon>{{ work.destination }}
            </span>
            <span v-if="work.tripDays" class="meta-item days">
              <el-icon><Calendar /></el-icon>{{ work.tripDays }} 天行程
            </span>
          </div>

          <!-- 攻略描述 -->
          <div class="work-desc" v-if="work.description">
            {{ work.description }}
          </div>

          <!-- 完整行程（P5 占位） -->
          <div class="itinerary-placeholder">
            <div class="placeholder-head">
              <el-icon><Compass /></el-icon>
              <span>完整行程</span>
            </div>
            <p class="placeholder-body">
              这里将展示 AI 生成的逐日行程：每天的景点顺序、停留时长与交通方式。
              行程规划能力在 P5 阶段接入，当前仅占位。
            </p>
            <el-button plain disabled size="small">查看完整行程（待接入）</el-button>
          </div>

          <!-- 标签 -->
          <div class="work-tags" v-if="work.tags?.length">
            <el-tag
              v-for="tag in work.tags"
              :key="tag.id"
              size="large"
              effect="plain"
              class="tag-item"
            >
              # {{ tag.name }}
            </el-tag>
          </div>

          <!-- 操作栏 -->
          <div class="action-bar">
            <button class="action-btn" :class="{ active: isLiked }" @click="handleLike">
              <el-icon :size="20"><Star v-if="!isLiked" /><StarFilled v-else /></el-icon>
              <span>{{ work.likeCount || 0 }}</span>
            </button>
            <button class="action-btn" :class="{ active: isFavorited }" @click="handleFavorite">
              <el-icon :size="20"><Collection v-if="!isFavorited" /><CollectionTag v-else /></el-icon>
              <span>收藏</span>
            </button>
            <button class="action-btn" @click="shareWork">
              <el-icon :size="20"><Share /></el-icon>
              <span>分享</span>
            </button>
            <div class="view-count">
              <el-icon :size="18"><View /></el-icon>
              <span>{{ work.viewCount || 0 }} 浏览</span>
            </div>
          </div>

          <!-- 编辑/删除按钮（作者本人） -->
          <div class="owner-actions" v-if="isOwner">
            <el-button type="primary" plain @click="$router.push(`/edit/${work.id}`)">
              <el-icon><Edit /></el-icon>编辑
            </el-button>
            <el-button type="danger" plain @click="handleDelete">
              <el-icon><Delete /></el-icon>删除
            </el-button>
          </div>
        </div>
      </div>

      <!-- 评论区 -->
      <div class="comment-section card">
        <h3 class="section-title">评论 ({{ commentTotal }})</h3>

        <!-- 发表评论 -->
        <div class="comment-input" v-if="userStore.isLoggedIn">
          <el-avatar :size="36" :src="userStore.avatar">
            {{ userStore.nickname?.charAt(0) }}
          </el-avatar>
          <div class="input-area">
            <el-input
              v-model="commentText"
              type="textarea"
              :rows="2"
              placeholder="说点什么..."
              maxlength="500"
              show-word-limit
            />
            <el-button type="primary" :disabled="!commentText.trim()" @click="submitComment">
              发表评论
            </el-button>
          </div>
        </div>
        <div v-else class="comment-login-tip">
          请<router-link to="/login" class="link">登录</router-link>后发表评论
        </div>

        <!-- 评论列表 -->
        <div class="comment-list" v-loading="commentLoading">
          <div v-for="comment in comments" :key="comment.id" class="comment-item">
            <el-avatar :size="36" :src="comment.user?.avatar" @click="goUser(comment.user?.id)">
              {{ comment.user?.nickname?.charAt(0) || comment.user?.username?.charAt(0) }}
            </el-avatar>
            <div class="comment-content">
              <div class="comment-header">
                <span class="comment-user" @click="goUser(comment.user?.id)">
                  {{ comment.user?.nickname || comment.user?.username || '匿名用户' }}
                </span>
                <span class="comment-time">{{ formatTime(comment.createdAt) }}</span>
              </div>
              <p class="comment-text">{{ comment.content }}</p>
              <div class="comment-actions">
                <span class="action-link" @click="replyTo(comment)">回复</span>
                <el-button
                  v-if="comment.userId === userStore.userId || userStore.isAdmin"
                  type="danger"
                  link
                  size="small"
                  @click="deleteComment(comment.id)"
                >删除</el-button>
              </div>

              <!-- 回复列表 -->
              <div class="reply-list" v-if="comment.replies && comment.replies.length > 0">
                <div v-for="reply in comment.replies" :key="reply.id" class="reply-item">
                  <el-avatar :size="28" :src="reply.user?.avatar" @click="goUser(reply.user?.id)">
                    {{ reply.user?.nickname?.charAt(0) || reply.user?.username?.charAt(0) }}
                  </el-avatar>
                  <div class="reply-content">
                    <div class="reply-header">
                      <span class="reply-user" @click="goUser(reply.user?.id)">
                        {{ reply.user?.nickname || reply.user?.username || '匿名用户' }}
                      </span>
                      <span v-if="reply.replyToUser" class="reply-to">
                        回复 <span class="reply-to-user" @click="goUser(reply.replyToUser?.id)">
                          @{{ reply.replyToUser?.nickname || reply.replyToUser?.username }}
                        </span>
                      </span>
                      <span class="reply-time">{{ formatTime(reply.createdAt) }}</span>
                    </div>
                    <p class="reply-text">{{ reply.content }}</p>
                    <div class="reply-actions">
                      <span class="action-link" @click="replyToReply(comment, reply)">回复</span>
                      <el-button
                        v-if="reply.userId === userStore.userId || userStore.isAdmin"
                        type="danger"
                        link
                        size="small"
                        @click="deleteComment(reply.id)"
                      >删除</el-button>
                    </div>
                  </div>
                </div>
              </div>

              <!-- 回复输入框 -->
              <div class="reply-input" v-if="replyTarget && replyTarget.commentId === comment.id">
                <el-avatar :size="28" :src="userStore.avatar">
                  {{ userStore.nickname?.charAt(0) }}
                </el-avatar>
                <div class="reply-input-area">
                  <el-input
                    v-model="replyText"
                    size="small"
                    :placeholder="`回复 @${replyTarget.nickname}`"
                    maxlength="500"
                    @keyup.enter="submitReply"
                  />
                  <div class="reply-input-btns">
                    <el-button size="small" @click="cancelReply">取消</el-button>
                    <el-button size="small" type="primary" :disabled="!replyText.trim()" @click="submitReply">
                      回复
                    </el-button>
                  </div>
                </div>
              </div>
            </div>
          </div>
          <el-empty v-if="!commentLoading && comments.length === 0" description="暂无评论，快来抢沙发" />
        </div>

        <!-- 加载更多评论 -->
        <div class="comment-more" v-if="hasMoreComments">
          <el-button type="primary" plain :loading="commentLoading" @click="loadMoreComments">
            加载更多评论
          </el-button>
        </div>
      </div>

      <!-- 相似攻略推荐 -->
      <div class="similar-section" v-if="similarWorks.length">
        <h3 class="section-title">相似攻略推荐</h3>
        <div class="similar-grid">
          <WorkCard v-for="w in similarWorks" :key="w.id" :work="w" />
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import WorkCard from '@/components/WorkCard.vue'
import { useUserStore } from '@/stores/user'
import { getWorkDetail, deleteWork } from '@/api/work'
import { getCommentList, createComment, deleteComment as delComment } from '@/api/comment'
import { likeWork, unlikeWork, checkLike, favoriteWork, unfavoriteWork, checkFavorite, followUser, unfollowUser, checkFollow } from '@/api/social'
import { getSimilarWorks, recordBrowse } from '@/api/recommend'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const work = ref(null)
const loading = ref(false)
const images = ref([])

const isLiked = ref(false)
const isFavorited = ref(false)
const isFollowing = ref(false)

const comments = ref([])
const commentTotal = ref(0)
const commentLoading = ref(false)
const commentPage = ref(1)
const hasMoreComments = ref(false)
const commentText = ref('')
const replyTarget = ref(null) // { commentId, nickname, replyToUserId }
const replyText = ref('')

const similarWorks = ref([])

const defaultCover = 'data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iNDAwIiBoZWlnaHQ9IjMwMCIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj48cmVjdCB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIiBmaWxsPSIjZTZmMWZiIi8+PHRleHQgeD0iNTAlIiB5PSI1MCUiIGZvbnQtZmFtaWx5PSJBcmlhbCIgZm9udC1zaXplPSIxNiIgZmlsbD0iIzg1YjdlYiIgdGV4dC1hbmNob3I9Im1pZGRsZSIgZHk9Ii4zZW0iPuihjOi1sOmbhiDCtyDmlLvnlaXlm77niYc8L3RleHQ+PC9zdmc+'

const isOwner = computed(() => userStore.isLoggedIn && work.value?.author?.id === userStore.userId)

onMounted(() => {
  fetchWorkDetail()
})

async function fetchWorkDetail() {
  loading.value = true
  try {
    const res = await getWorkDetail(route.params.id)
    work.value = res.data
    images.value = res.data.imageUrls?.length ? res.data.imageUrls : [res.data.coverUrl].filter(Boolean)

    // 记录浏览历史
    if (userStore.isLoggedIn) {
      recordBrowse(work.value.id).catch(() => {})
      // 检查点赞/收藏/关注状态
      checkLike(work.value.id).then(r => { isLiked.value = r.data.liked }).catch(() => {})
      checkFavorite(work.value.id).then(r => { isFavorited.value = r.data.favorited }).catch(() => {})
      if (work.value.author?.id) {
        checkFollow(work.value.author.id).then(r => { isFollowing.value = r.data.following }).catch(() => {})
      }
    }

    fetchComments()
    fetchSimilarWorks()
  } catch (e) {
    // 错误已处理
  } finally {
    loading.value = false
  }
}

async function fetchComments() {
  commentLoading.value = true
  try {
    const res = await getCommentList(work.value.id, { pageNum: commentPage.value, pageSize: 10 })
    comments.value = commentPage.value === 1 ? (res.data.records || []) : [...comments.value, ...(res.data.records || [])]
    commentTotal.value = res.data.total || 0
    hasMoreComments.value = comments.value.length < commentTotal.value
  } catch (e) {
    // 忽略
  } finally {
    commentLoading.value = false
  }
}

function loadMoreComments() {
  commentPage.value++
  fetchComments()
}

async function submitComment() {
  if (!commentText.value.trim()) return
  try {
    await createComment({
      workId: work.value.id,
      content: commentText.value.trim()
    })
    ElMessage.success('评论成功')
    commentText.value = ''
    commentPage.value = 1
    fetchComments()
  } catch (e) {
    // 错误已处理
  }
}

function replyTo(comment) {
  const nickname = comment.user?.nickname || comment.user?.username || '用户'
  replyTarget.value = {
    commentId: comment.id,
    nickname: nickname,
    replyToUserId: comment.userId
  }
  replyText.value = ''
}

function replyToReply(comment, reply) {
  const nickname = reply.user?.nickname || reply.user?.username || '用户'
  replyTarget.value = {
    commentId: comment.id,
    nickname: nickname,
    replyToUserId: reply.userId
  }
  replyText.value = ''
}

function cancelReply() {
  replyTarget.value = null
  replyText.value = ''
}

async function submitReply() {
  if (!replyText.value.trim() || !replyTarget.value) return
  try {
    await createComment({
      workId: work.value.id,
      content: replyText.value.trim(),
      parentId: replyTarget.value.commentId,
      replyToUserId: replyTarget.value.replyToUserId
    })
    ElMessage.success('回复成功')
    replyText.value = ''
    replyTarget.value = null
    commentPage.value = 1
    fetchComments()
  } catch (e) {
    // 错误已处理
  }
}

async function deleteComment(id) {
  try {
    await ElMessageBox.confirm('确定删除这条评论吗？', '提示', { type: 'warning' })
    await delComment(id)
    ElMessage.success('删除成功')
    comments.value = comments.value.filter(c => c.id !== id)
    commentTotal.value--
  } catch (e) {
    if (e !== 'cancel') { /* 错误已处理 */ }
  }
}

async function handleLike() {
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
  try {
    const res = await likeWork(work.value.id)
    isLiked.value = res.data.liked
    work.value.likeCount = res.data.count
  } catch (e) {
    // 错误已处理
  }
}

async function handleFavorite() {
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
  try {
    const res = await favoriteWork(work.value.id)
    isFavorited.value = res.data.favorited
    ElMessage.success(res.data.favorited ? '收藏成功' : '已取消收藏')
  } catch (e) {
    // 错误已处理
  }
}

async function handleFollow() {
  try {
    const res = await followUser(work.value.author.id)
    isFollowing.value = res.data.following
    ElMessage.success(res.data.following ? '关注成功' : '已取消关注')
  } catch (e) {
    // 错误已处理
  }
}

async function handleDelete() {
  try {
    await ElMessageBox.confirm('确定删除这个作品吗？删除后不可恢复。', '警告', { type: 'error' })
    await deleteWork(work.value.id)
    ElMessage.success('删除成功')
    router.push('/')
  } catch (e) {
    if (e !== 'cancel') { /* 错误已处理 */ }
  }
}

async function fetchSimilarWorks() {
  try {
    const res = await getSimilarWorks(work.value.id, 6)
    similarWorks.value = res.data || []
  } catch (e) {
    // 忽略
  }
}

function shareWork() {
  const url = window.location.href
  navigator.clipboard?.writeText(url).then(() => {
    ElMessage.success('链接已复制到剪贴板')
  }).catch(() => {
    ElMessage.info('请手动复制链接分享')
  })
}

function goUserHome() {
  if (work.value.author?.id) {
    router.push(`/user/${work.value.author.id}`)
  }
}

function goUser(id) {
  if (id) router.push(`/user/${id}`)
}

function formatTime(time) {
  if (!time) return ''
  return dayjs(time).format('YYYY-MM-DD HH:mm')
}
</script>

<style scoped lang="scss">
.work-detail {
  padding-bottom: 40px;
}

.detail-layout {
  display: flex;
  gap: 24px;
  margin-bottom: 24px;
  align-items: flex-start;
}

.image-section {
  flex: 1;
  min-width: 0;
}

.image-carousel {
  border-radius: 10px;
  overflow: hidden;
}

.detail-image {
  width: 100%;
  max-height: 600px;
  object-fit: contain;
  background: #000;
  border-radius: 10px;

  &.single {
    max-height: 600px;
  }
}

.info-section {
  width: 360px;
  flex-shrink: 0;
  background: #fff;
  border-radius: 10px;
  padding: 24px;
  position: sticky;
  top: 80px;
}

.work-title {
  font-size: 22px;
  font-weight: 700;
  margin: 0 0 16px;
  color: #303133;
}

.author-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 16px;
  border-bottom: 1px solid #f0f0f0;
  margin-bottom: 16px;
}

.author-info {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
}

.author-text {
  display: flex;
  flex-direction: column;
}

.author-name {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.publish-time {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.work-desc {
  font-size: 14px;
  color: #606266;
  line-height: 1.7;
  margin-bottom: 16px;
  white-space: pre-wrap;
}

// 目的地与天数
.trip-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 14px;

  .meta-item {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 13px;
    color: #606266;
  }

  .meta-item.days {
    color: #e6a23c;
    background: #fdf6ec;
    padding: 2px 10px;
    border-radius: 10px;
  }
}

// 完整行程占位块（P5 填内容）
.itinerary-placeholder {
  border: 1px dashed #dcdfe6;
  border-radius: 10px;
  padding: 14px 16px;
  margin-bottom: 16px;
  background: #fafcff;

  .placeholder-head {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 14px;
    font-weight: 600;
    color: #303133;
    margin-bottom: 6px;
  }

  .placeholder-body {
    font-size: 12px;
    color: #909399;
    line-height: 1.6;
    margin: 0 0 10px;
  }
}

.work-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 20px;

  .tag-item {
    cursor: default;
  }
}

.action-bar {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 0;
  border-top: 1px solid #f0f0f0;
  border-bottom: 1px solid #f0f0f0;
  margin-bottom: 16px;
}

.action-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  background: none;
  border: none;
  cursor: pointer;
  color: #606266;
  font-size: 14px;
  padding: 4px 8px;
  border-radius: 6px;
  transition: all 0.2s;

  &:hover {
    background: #f5f7fa;
  }

  &.active {
    color: #f56c6c;
  }
}

.view-count {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 4px;
  color: #909399;
  font-size: 13px;
}

.owner-actions {
  display: flex;
  gap: 10px;
}

.comment-section {
  padding: 24px;
  margin-bottom: 24px;
}

.section-title {
  font-size: 18px;
  font-weight: 600;
  margin: 0 0 20px;
  color: #303133;
}

.comment-input {
  display: flex;
  gap: 12px;
  margin-bottom: 24px;
}

.input-area {
  flex: 1;

  .el-button {
    margin-top: 8px;
  }
}

.comment-login-tip {
  text-align: center;
  padding: 20px;
  color: #909399;
  font-size: 14px;

  .link {
    color: #409eff;
  }
}

.comment-list {
  .comment-item {
    display: flex;
    gap: 12px;
    padding: 16px 0;
    border-bottom: 1px solid #f5f5f5;

    &:last-child {
      border-bottom: none;
    }
  }
}

.comment-content {
  flex: 1;
}

.comment-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.comment-user {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  cursor: pointer;

  &:hover {
    color: #409eff;
  }
}

.comment-time {
  font-size: 12px;
  color: #c0c4cc;
}

.comment-text {
  font-size: 14px;
  color: #606266;
  line-height: 1.6;
  margin: 0 0 8px;
}

.comment-actions {
  .action-link {
    font-size: 13px;
    color: #909399;
    cursor: pointer;
    margin-right: 12px;

    &:hover {
      color: #409eff;
    }
  }
}

/* 回复列表 */
.reply-list {
  margin-top: 12px;
  padding: 12px;
  background: #f7f8fa;
  border-radius: 8px;
}

.reply-item {
  display: flex;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid #ebeef5;

  &:last-child {
    border-bottom: none;
  }
}

.reply-content {
  flex: 1;
  min-width: 0;
}

.reply-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
  flex-wrap: wrap;
}

.reply-user {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  cursor: pointer;

  &:hover {
    color: #409eff;
  }
}

.reply-to {
  font-size: 12px;
  color: #909399;
}

.reply-to-user {
  color: #409eff;
  cursor: pointer;

  &:hover {
    text-decoration: underline;
  }
}

.reply-time {
  font-size: 12px;
  color: #c0c4cc;
  margin-left: auto;
}

.reply-text {
  font-size: 13px;
  color: #606266;
  line-height: 1.6;
  margin: 0 0 6px;
}

.reply-actions {
  .action-link {
    font-size: 12px;
    color: #909399;
    cursor: pointer;
    margin-right: 10px;

    &:hover {
      color: #409eff;
    }
  }
}

/* 回复输入框 */
.reply-input {
  display: flex;
  gap: 10px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed #ebeef5;
}

.reply-input-area {
  flex: 1;
}

.reply-input-btns {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.comment-more {
  text-align: center;
  margin-top: 16px;
}

.similar-section {
  .similar-grid {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 16px;
  }
}

@media (max-width: 1024px) {
  .detail-layout {
    flex-direction: column;
  }
  .info-section {
    width: 100%;
    position: static;
  }
  .similar-grid {
    grid-template-columns: repeat(2, 1fr) !important;
  }
}

@media (max-width: 768px) {
  .work-detail {
    padding: 0 12px;
  }
  .detail-image {
    max-height: 400px;
  }
  .info-section {
    padding: 16px;
  }
  .work-title {
    font-size: 18px;
  }
  .comment-section {
    padding: 16px;
  }
  .similar-grid {
    grid-template-columns: 1fr !important;
  }
}
</style>
