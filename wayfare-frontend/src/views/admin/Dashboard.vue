<template>
  <div class="dashboard">
    <el-row :gutter="20">
      <el-col :span="6" v-for="card in statCards" :key="card.title">
        <el-card class="stat-card" shadow="hover">
          <div class="stat-content">
            <div class="stat-icon" :style="{ background: card.color }">
              <el-icon :size="28"><component :is="card.icon" /></el-icon>
            </div>
            <div class="stat-info">
              <span class="stat-value">{{ card.value }}</span>
              <span class="stat-label">{{ card.title }}</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="12">
        <el-card>
          <template #header>最新作品</template>
          <el-table :data="latestWorks" size="small" v-loading="loading">
            <el-table-column prop="id" label="ID" width="60" />
            <el-table-column prop="title" label="标题" show-overflow-tooltip />
            <el-table-column prop="likeCount" label="点赞" width="80" />
            <el-table-column prop="viewCount" label="浏览" width="80" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
                  {{ row.status === 1 ? '已发布' : '草稿' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header>最新用户</template>
          <el-table :data="latestUsers" size="small" v-loading="loading">
            <el-table-column prop="id" label="ID" width="60" />
            <el-table-column prop="username" label="用户名" />
            <el-table-column prop="nickname" label="昵称" />
            <el-table-column label="角色" width="80">
              <template #default="{ row }">
                <el-tag :type="row.role === 'admin' ? 'danger' : 'primary'" size="small">
                  {{ row.role === 'admin' ? '管理员' : '普通用户' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
                  {{ row.status === 1 ? '正常' : '禁用' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getAdminWorkList } from '@/api/work'
import { getUserList } from '@/api/user'

const loading = ref(false)
const latestWorks = ref([])
const latestUsers = ref([])

const statCards = ref([
  { title: '作品总数', value: 0, icon: 'Picture', color: '#409eff' },
  { title: '用户总数', value: 0, icon: 'User', color: '#67c23a' },
  { title: '今日点赞', value: 0, icon: 'Star', color: '#e6a23c' },
  { title: '待审核', value: 0, icon: 'CircleCheck', color: '#f56c6c' }
])

onMounted(() => {
  fetchData()
})

async function fetchData() {
  loading.value = true
  try {
    const [workRes, userRes] = await Promise.all([
      getAdminWorkList({ pageNum: 1, pageSize: 5 }),
      getUserList({ pageNum: 1, pageSize: 5 })
    ])
    latestWorks.value = workRes.data.records || []
    latestUsers.value = userRes.data.records || []
    statCards.value[0].value = workRes.data.total || 0
    statCards.value[1].value = userRes.data.total || 0
  } catch (e) {
    // 忽略
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="scss">
.stat-card {
  .stat-content {
    display: flex;
    align-items: center;
    gap: 16px;
  }
  .stat-icon {
    width: 56px;
    height: 56px;
    border-radius: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
    color: #fff;
  }
  .stat-info {
    display: flex;
    flex-direction: column;
  }
  .stat-value {
    font-size: 28px;
    font-weight: 700;
    color: #303133;
  }
  .stat-label {
    font-size: 13px;
    color: #909399;
    margin-top: 4px;
  }
}
</style>
