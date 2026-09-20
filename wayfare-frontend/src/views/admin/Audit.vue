<template>
  <div class="audit-page">
    <el-card>
      <el-tabs v-model="activeTab">
        <el-tab-pane label="待审核作品" name="pending">
          <el-table :data="pendingWorks" v-loading="loading" stripe>
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column label="封面" width="80">
              <template #default="{ row }">
                <el-image :src="row.coverUrl" style="width: 50px; height: 50px; border-radius: 4px" fit="cover" />
              </template>
            </el-table-column>
            <el-table-column prop="title" label="标题" show-overflow-tooltip />
            <el-table-column prop="author.nickname" label="作者" width="100" />
            <el-table-column prop="description" label="描述" show-overflow-tooltip />
            <el-table-column prop="createdAt" label="提交时间" width="160">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button type="success" link size="small" @click="handleAudit(row, 1)">通过</el-button>
                <el-button type="danger" link size="small" @click="handleAudit(row, 0)">拒绝</el-button>
                <el-button type="primary" link size="small" @click="viewDetail(row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!loading && pendingWorks.length === 0" description="暂无待审核作品" />
        </el-tab-pane>

        <el-tab-pane label="审核记录" name="history">
          <el-table :data="historyList" v-loading="historyLoading" stripe>
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="title" label="作品标题" show-overflow-tooltip />
            <el-table-column prop="author.nickname" label="作者" width="100" />
            <el-table-column label="审核结果" width="100">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
                  {{ row.status === 1 ? '已通过' : '已拒绝' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="updatedAt" label="审核时间" width="160">
              <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { getAdminWorkList, auditWork } from '@/api/work'

const router = useRouter()
const activeTab = ref('pending')
const pendingWorks = ref([])
const historyList = ref([])
const loading = ref(false)
const historyLoading = ref(false)

onMounted(() => {
  fetchPending()
})

async function fetchPending() {
  loading.value = true
  try {
    const res = await getAdminWorkList({ pageNum: 1, pageSize: 50, status: 0 })
    pendingWorks.value = res.data.records || []
  } catch (e) {
    // 忽略
  } finally {
    loading.value = false
  }
}

async function fetchHistory() {
  historyLoading.value = true
  try {
    // 获取已审核的作品（状态非0）
    const res = await getAdminWorkList({ pageNum: 1, pageSize: 50 })
    historyList.value = (res.data.records || []).filter(w => w.status !== 0)
  } catch (e) {
    // 忽略
  } finally {
    historyLoading.value = false
  }
}

async function handleAudit(row, status) {
  const action = status === 1 ? '通过' : '拒绝'
  try {
    await ElMessageBox.confirm(`确定${action}作品「${row.title}」吗？`, '审核确认', { type: 'warning' })
    await auditWork(row.id, status)
    ElMessage.success(`${action}成功`)
    fetchPending()
  } catch (e) {
    if (e !== 'cancel') { /* 错误已处理 */ }
  }
}

function viewDetail(row) {
  router.push(`/work/${row.id}`)
}

function formatTime(time) {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm') : ''
}
</script>
