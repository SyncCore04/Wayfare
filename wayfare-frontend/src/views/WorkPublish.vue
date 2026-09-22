<template>
  <div class="publish-page container">
    <div class="publish-card">
      <h2 class="page-title">{{ isEdit ? '编辑攻略' : '发布攻略' }}</h2>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <!-- 图片上传 -->
        <el-form-item label="攻略图片" prop="imageUrls">
          <div class="upload-area">
            <div class="image-list">
              <div v-for="(img, idx) in form.imageUrls" :key="idx" class="image-item">
                <img :src="img" :alt="`图片${idx + 1}`" />
                <div class="image-actions">
                  <el-icon class="action-icon" @click="moveImage(idx, -1)" v-if="idx > 0"><Top /></el-icon>
                  <el-icon class="action-icon" @click="moveImage(idx, 1)" v-if="idx < form.imageUrls.length - 1"><Bottom /></el-icon>
                  <el-icon class="action-icon delete" @click="removeImage(idx)"><Delete /></el-icon>
                </div>
                <span class="image-index" v-if="idx === 0">封面</span>
              </div>
              <div class="upload-btn" v-if="form.imageUrls.length < 9" @click="triggerUpload">
                <el-icon :size="32" color="#c0c4cc"><Plus /></el-icon>
                <span class="upload-text">上传图片</span>
                <span class="upload-hint">支持JPG/PNG，单张≤10MB</span>
              </div>
            </div>
            <input
              ref="fileInputRef"
              type="file"
              accept="image/jpeg,image/png,image/jpg"
              multiple
              style="display:none"
              @change="handleFileSelect"
            />
          </div>
          <div class="upload-tip">第一张图片将作为封面图，最多上传9张（可用图片上的箭头调整顺序）</div>
        </el-form-item>

        <!-- 标题 -->
        <el-form-item label="攻略标题" prop="title">
          <el-input v-model="form.title" placeholder="给你的攻略起个标题" maxlength="50" show-word-limit />
        </el-form-item>

        <!-- 描述 -->
        <el-form-item label="攻略描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="4"
            placeholder="写写这趟旅行的路线安排、值得停留的地方、踩过的坑..."
            maxlength="2000"
            show-word-limit
          />
        </el-form-item>

        <!-- 标签 -->
        <el-form-item label="选择标签" prop="tagIds">
          <el-select
            v-model="form.tagIds"
            multiple
            filterable
            placeholder="选择标签，最多5个"
            :max-collapse-tags="5"
            style="width: 100%"
          >
            <el-option v-for="tag in allTags" :key="tag.id" :label="tag.name" :value="tag.id" />
          </el-select>
          <div class="tag-tip">选热门标签更容易被找到；新建标签需要管理员在后台添加</div>
        </el-form-item>

        <!-- 分类：数据来自 GET /categories/tree，不再硬编码 -->
        <el-form-item label="攻略分类" prop="categoryId">
          <el-select v-model="form.categoryId" placeholder="选择分类" style="width: 100%" :loading="categoryLoading">
            <template v-for="cat in categories" :key="cat.id">
              <el-option-group v-if="cat.children && cat.children.length" :label="cat.name">
                <el-option v-for="sub in cat.children" :key="sub.id" :label="sub.name" :value="sub.id" />
              </el-option-group>
              <el-option v-else :label="cat.name" :value="cat.id" />
            </template>
          </el-select>
        </el-form-item>

        <!-- 目的地 + 天数：攻略特有的两个筛选维度 -->
        <div class="field-row">
          <el-form-item label="目的地" prop="destination" class="field-half">
            <el-input v-model="form.destination" placeholder="例如：泉州 / 京都" maxlength="50" />
          </el-form-item>
          <el-form-item label="行程天数" prop="tripDays" class="field-half">
            <el-input-number v-model="form.tripDays" :min="1" :max="60" style="width: 100%" />
          </el-form-item>
        </div>

        <!-- 提交按钮 -->
        <el-form-item>
          <div class="submit-area">
            <el-button size="large" @click="$router.back()">取消</el-button>
            <el-button size="large" :loading="submitting" @click="handleSubmit(0)">存草稿</el-button>
            <el-button type="primary" size="large" :loading="submitting" @click="handleSubmit(1)">
              {{ isEdit ? '保存修改' : '发布攻略' }}
            </el-button>
          </div>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { uploadImages } from '@/api/file'
import { createWork, updateWork, getWorkDetail } from '@/api/work'
import { getTrip, publishTrip } from '@/api/trip'
import { getAllTags } from '@/api/tag'
import { getCategoryTree } from '@/api/category'

const route = useRoute()
const router = useRouter()
const formRef = ref()
const fileInputRef = ref()
const submitting = ref(false)
const allTags = ref([])

const isEdit = computed(() => !!route.params.id)

/** 从 AI 行程发布时带的行程 id（P5-B/C）；编辑模式下不使用 */
const tripIdFromQuery = computed(() => route.query.tripId || null)

// 分类改为从 GET /categories/tree 拉取（旧版这里写死了 7 个摄影分类，
// 数据库改了分类前端根本不跟着变 —— 那是 P0 阶段要修掉的假实现之一）
const categories = ref([])
const categoryLoading = ref(false)

const form = reactive({
  title: '',
  description: '',
  categoryId: null,
  destination: '',
  tripDays: 1,
  tagIds: [],
  imageUrls: [],
  coverUrl: ''
})

const rules = {
  title: [{ required: true, message: '请输入攻略标题', trigger: 'blur' }],
  imageUrls: [{ required: true, message: '请至少上传一张图片', trigger: 'change' }],
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
  destination: [{ required: true, message: '请输入目的地', trigger: 'blur' }],
  tripDays: [{ required: true, message: '请填写行程天数', trigger: 'change' }]
}

onMounted(() => {
  fetchTags()
  fetchCategories()
  if (isEdit.value) {
    fetchWorkData()
  } else if (tripIdFromQuery.value) {
    prefillFromTrip()
  }
})

/**
 * 从 AI 行程预填（P5-B/C 的「发布为攻略」入口会带 tripId 过来）。
 *
 * 只预填「标题 + 描述 + 目的地 + 天数」：图片与分类仍要用户自己选 ——
 * 封面图是发布页的必填项，而 AI 生成不了图片，硬造一个空封面只会让发布失败。
 */
async function prefillFromTrip() {
  try {
    const res = await getTrip(tripIdFromQuery.value)
    const trip = res.data || {}
    if (!form.title) form.title = trip.title || ''
    if (!form.description) {
      // 优先用攻略文案，没有就退回用户的原始需求 —— 总之不留空
      form.description = trip.guideText || trip.rawInput || ''
    }
    if (!form.destination) form.destination = trip.destination || ''
    if (!form.tripDays) form.tripDays = trip.days || null
    ElMessage.info('已从你的 AI 行程预填，补上图片与分类即可发布')
  } catch (e) {
    // 行程取不到就不预填，用户照常手填
  }
}

async function fetchCategories() {
  categoryLoading.value = true
  try {
    const res = await getCategoryTree()
    categories.value = res.data || []
  } catch (e) {
    // request.js 已统一提示
  } finally {
    categoryLoading.value = false
  }
}

async function fetchTags() {
  try {
    const res = await getAllTags()
    allTags.value = res.data || []
  } catch (e) {
    // 忽略
  }
}

async function fetchWorkData() {
  try {
    const res = await getWorkDetail(route.params.id)
    const work = res.data
    form.title = work.title
    form.description = work.description
    form.categoryId = work.categoryId
    form.destination = work.destination || ''
    form.tripDays = work.tripDays || 1
    form.tagIds = (work.tags || []).map(t => t.id)
    form.imageUrls = work.imageUrls?.length ? work.imageUrls : (work.coverUrl ? [work.coverUrl] : [])
    form.coverUrl = work.coverUrl
  } catch (e) {
    // 错误已处理
  }
}

function triggerUpload() {
  fileInputRef.value?.click()
}

async function handleFileSelect(e) {
  const files = Array.from(e.target.files)
  if (!files.length) return

  // 校验：与后端限制保持一致（spring.servlet.multipart.max-file-size: 10MB），
  // 旧版这里写 5MB 与后端不一致，会让用户被前端无故拦下
  const MAX_SIZE = 10 * 1024 * 1024
  const validFiles = files.filter(f => {
    if (f.size > MAX_SIZE) {
      ElMessage.warning(`${f.name} 超过10MB，已跳过`)
      return false
    }
    if (!['image/jpeg', 'image/png', 'image/jpg'].includes(f.type)) {
      ElMessage.warning(`${f.name} 格式不支持，已跳过`)
      return false
    }
    return true
  })

  if (!validFiles.length) {
    e.target.value = ''
    return
  }

  if (form.imageUrls.length + validFiles.length > 9) {
    ElMessage.warning('最多上传9张图片')
    e.target.value = ''
    return
  }

  // 上传
  const formData = new FormData()
  validFiles.forEach(f => formData.append('files', f))

  try {
    const res = await uploadImages(formData)
    // 后端返回 List<UploadVO>，每个元素包含 fileUrl 字段
    const urls = (res.data || []).map(item => item.fileUrl)
    form.imageUrls = [...form.imageUrls, ...urls]
    if (!form.coverUrl && form.imageUrls.length) {
      form.coverUrl = form.imageUrls[0]
    }
    ElMessage.success(`成功上传 ${urls.length} 张图片`)
  } catch (e) {
    // 错误已处理
  } finally {
    e.target.value = ''
  }
}

function removeImage(idx) {
  form.imageUrls.splice(idx, 1)
  if (idx === 0 && form.imageUrls.length) {
    form.coverUrl = form.imageUrls[0]
  }
}

function moveImage(idx, direction) {
  const targetIdx = idx + direction
  if (targetIdx < 0 || targetIdx >= form.imageUrls.length) return
  const temp = form.imageUrls[idx]
  form.imageUrls[idx] = form.imageUrls[targetIdx]
  form.imageUrls[targetIdx] = temp
  if (idx === 0 || targetIdx === 0) {
    form.coverUrl = form.imageUrls[0]
  }
}

/**
 * 提交。status: 1 立即发布 / 0 存草稿
 */
async function handleSubmit(status = 1) {
  await formRef.value.validate()
  submitting.value = true
  try {
    // 标签只提交数字 id：后端 tagIds 是 List<Long>，
    // 若混入选择框里手打的字符串会造成反序列化失败（400）
    const tagIds = (form.tagIds || []).filter(v => typeof v === 'number' && !Number.isNaN(v))
    const droppedCount = (form.tagIds || []).length - tagIds.length
    if (droppedCount > 0) {
      ElMessage.info(`已忽略 ${droppedCount} 个新标签（新建标签需管理员添加）`)
    }

    const payload = {
      title: form.title,
      description: form.description,
      categoryId: form.categoryId,
      destination: form.destination,
      tripDays: form.tripDays,
      tagIds,
      imageUrls: form.imageUrls,
      // 封面取第一张，与后端「coverUrl 非必填、服务端取 imageUrls[0]」的约定一致
      coverUrl: form.imageUrls[0] || '',
      status
    }

    if (isEdit.value) {
      await updateWork(route.params.id, payload)
      ElMessage.success(status === 0 ? '已保存为草稿' : '修改成功')
    } else {
      const res = await createWork(payload)
      ElMessage.success(status === 0 ? '已保存为草稿' : '发布成功')
      // 从 AI 行程来的：发布成功后建立关联，卡片才会出现「AI 生成」角标、
      // 详情页才会出现「完整行程」区块。
      // 关联失败**不回滚发布** —— 攻略本身已经建好了，为一个关联失败把它删掉
      // 反而会让用户白填一遍表单，所以只提示
      if (tripIdFromQuery.value && res.data?.id) {
        try {
          await publishTrip(tripIdFromQuery.value, res.data.id)
        } catch (e) {
          ElMessage.warning('攻略已发布，但与行程的关联失败（详情页可能不显示完整行程）')
        }
      }
    }
    router.push('/profile?tab=works')
  } catch (e) {
    // 错误已由 request.js 统一提示
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped lang="scss">
.publish-page {
  max-width: 800px;
  padding-bottom: 40px;
}

.publish-card {
  background: #fff;
  border-radius: 12px;
  padding: 32px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.04);
}

.page-title {
  font-size: 24px;
  font-weight: 700;
  margin: 0 0 28px;
  color: #303133;
  text-align: center;
}

// 目的地与天数并排，窄屏时自动上下堆叠
.field-row {
  display: flex;
  gap: 16px;

  .field-half {
    flex: 1;
    min-width: 0;
  }
}

.upload-area {
  width: 100%;
}

.image-list {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.image-item {
  position: relative;
  width: 140px;
  height: 140px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid #e4e7ed;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.image-actions {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0,0,0,0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  opacity: 0;
  transition: opacity 0.2s;

  .image-item:hover & {
    opacity: 1;
  }
}

.action-icon {
  color: #fff;
  font-size: 18px;
  cursor: pointer;
  padding: 4px;

  &:hover {
    color: #409eff;
  }

  &.delete:hover {
    color: #f56c6c;
  }
}

.image-index {
  position: absolute;
  top: 6px;
  left: 6px;
  background: #409eff;
  color: #fff;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
}

.upload-btn {
  width: 140px;
  height: 140px;
  border: 2px dashed #dcdfe6;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s;

  &:hover {
    border-color: #409eff;
    background: #f5f7fa;

    .upload-text {
      color: #409eff;
    }
  }
}

.upload-text {
  font-size: 13px;
  color: #606266;
  margin-top: 6px;
}

.upload-hint {
  font-size: 11px;
  color: #c0c4cc;
  margin-top: 2px;
}

.upload-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 8px;
}

.tag-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 6px;
}

.submit-area {
  display: flex;
  justify-content: center;
  gap: 16px;
  padding-top: 16px;
}

@media (max-width: 768px) {
  .publish-page {
    padding: 0 12px;
  }
  .publish-card {
    padding: 20px 16px;
  }
  .image-item, .upload-btn {
    width: 100px;
    height: 100px;
  }
  .field-row {
    flex-direction: column;
    gap: 0;
  }
}
</style>
