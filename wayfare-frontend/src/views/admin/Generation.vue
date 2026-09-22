<template>
  <div class="generation-page" v-loading="loading">
    <!-- ==================== 顶部：窗口与刷新 ==================== -->
    <el-card class="toolbar" shadow="never">
      <div class="toolbar-row">
        <span class="label">统计窗口</span>
        <el-date-picker
          v-model="range"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          :clearable="false"
          @change="loadAll"
        />
        <el-tag size="small" type="info" effect="plain">默认今天 · 卡片与图表都跟随这个窗口</el-tag>
        <el-button class="refresh-btn" :icon="Refresh" circle @click="loadAll" />
      </div>
    </el-card>

    <!-- ==================== 一、统计卡片 ==================== -->
    <div class="stat-grid">
      <el-card v-for="card in statCards" :key="card.label" class="stat-card" shadow="never">
        <div class="stat-label">{{ card.label }}</div>
        <div class="stat-value">{{ card.value }}</div>
        <div v-if="card.hint" class="stat-hint">{{ card.hint }}</div>
      </el-card>
    </div>

    <!-- ==================== 二、图表区 ==================== -->
    <el-alert
      v-if="costUnavailable"
      type="warning"
      :closable="false"
      show-icon
      class="cost-alert"
      title="单价未配置，成本曲线与成本卡片是空的"
    >
      <div class="sub">
        预估成本 = 单价 × token ÷ 100 万，单价来自「连接器管理」页的 `llm.price.{厂商}-input/-output`。
        按官网实际报价填好之后，这里才会有数字 —— <b>宁可显示空，也不编一个看起来合理的小数</b>。
      </div>
    </el-alert>

    <el-row :gutter="16" class="chart-row">
      <el-col :span="24">
        <el-card shadow="never" class="chart-card">
          <template #header>
            <div class="chart-header">
              <span class="chart-title">近 7 天生成次数与成本</span>
              <span class="chart-hint">双 Y 轴 · 窗口固定为最近 7 天（与上方统计窗口无关）</span>
            </div>
          </template>
          <div ref="trendRef" class="chart chart-trend"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="chart-row">
      <el-col :span="12">
        <el-card shadow="never" class="chart-card">
          <template #header>
            <div class="chart-header">
              <span class="chart-title">各阶段平均耗时</span>
              <span class="chart-hint">按天线的顺序排列 · 单位为毫秒</span>
            </div>
          </template>
          <div ref="stageRef" class="chart chart-mid"></div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never" class="chart-card">
          <template #header>
            <div class="chart-header">
              <span class="chart-title">地图模式分布</span>
              <span class="chart-hint">降级体系到底跑没跑，看这张图</span>
            </div>
          </template>
          <div ref="modeRef" class="chart chart-mid"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="chart-row">
      <el-col :span="12">
        <el-card shadow="never" class="chart-card">
          <template #header>
            <div class="chart-header">
              <span class="chart-title">失败原因 Top 5</span>
              <span class="chart-hint">按 error_code 聚合</span>
            </div>
          </template>
          <div ref="errRef" class="chart chart-mid"></div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never" class="chart-card">
          <template #header>
            <div class="chart-header">
              <span class="chart-title">耗时大头在哪</span>
              <span class="chart-hint">按平均耗时降序，一眼看出瓶颈阶段</span>
            </div>
          </template>
          <div ref="hotRef" class="chart chart-mid"></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ==================== 三、生成明细 ==================== -->
    <el-card shadow="never" class="block">
      <template #header>
        <div class="chart-header">
          <span class="chart-title">生成明细</span>
          <span class="chart-hint">按阶段逐行 · 一次生成占 5~7 行；展开可看这次生成的全部分段</span>
        </div>
      </template>

      <div class="filter-row">
        <el-select v-model="filters.success" placeholder="成功与否" clearable style="width: 130px">
          <el-option label="成功" :value="true" />
          <el-option label="失败" :value="false" />
        </el-select>
        <el-select v-model="filters.stage" placeholder="阶段" clearable style="width: 140px">
          <el-option v-for="s in STAGES" :key="s" :label="s" :value="s" />
        </el-select>
        <el-input v-model="filters.model" placeholder="模型名，如 qwen3.8-flash" clearable style="width: 220px" />
        <el-input v-model="filters.destination" placeholder="目的地（模糊）" clearable style="width: 170px" />
        <el-select v-model="filters.mapMode" placeholder="地图模式" clearable style="width: 160px">
          <el-option v-for="m in MAP_MODES" :key="m" :label="m" :value="m" />
        </el-select>
        <el-button type="primary" @click="searchLogs">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>

      <el-table :data="logs.records" size="small" border @expand-change="expandRow">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-cell">
              <div v-if="!row.tripId" class="sub">该阶段还没有关联行程（PARSE 阶段常常如此），没有可展开的分段。</div>
              <template v-else>
                <div v-if="breakdownLoading[row.tripId]" class="sub">正在加载这次生成的分段…</div>
                <template v-else-if="breakdowns[row.tripId]">
                  <el-table :data="breakdowns[row.tripId].stages" size="small">
                    <el-table-column prop="stage" label="阶段" width="110" />
                    <el-table-column prop="promptTokens" label="输入 token" width="110" />
                    <el-table-column prop="completionTokens" label="输出 token" width="110" />
                    <el-table-column prop="tokens" label="合计" width="100" />
                    <el-table-column prop="durationMs" label="耗时(ms)" width="110" />
                    <el-table-column prop="callCount" label="调用次数" width="100" />
                  </el-table>
                  <div class="sub expand-total">
                    本次生成合计：token {{ breakdowns[row.tripId].totalTokens ?? '未知' }} ·
                    预估成本 {{ costText(breakdowns[row.tripId].estCost) }}
                  </div>
                </template>
              </template>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="userName" label="用户" width="110">
          <template #default="{ row }">{{ row.userName || (row.userId ? '#' + row.userId : '—') }}</template>
        </el-table-column>
        <el-table-column prop="destination" label="目的地" width="110">
          <template #default="{ row }">{{ row.destination || '—' }}</template>
        </el-table-column>
        <el-table-column prop="days" label="天数" width="70">
          <template #default="{ row }">{{ row.days ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="stage" label="阶段" width="110" />
        <el-table-column prop="model" label="模型" width="150">
          <template #default="{ row }">{{ row.model || '—' }}</template>
        </el-table-column>
        <el-table-column label="耗时(ms)" width="100">
          <template #default="{ row }">{{ row.durationMs ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="token" width="110">
          <template #default="{ row }">
            {{ row.totalTokens ?? '—' }}
            <span v-if="row.totalTokens === null" class="sub">未计费</span>
          </template>
        </el-table-column>
        <el-table-column label="地图模式" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.mapMode" :type="modeTagType(row.mapMode)" size="small">{{ row.mapMode }}</el-tag>
            <span v-else class="sub">—</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'" size="small">
              {{ row.success ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorCode" label="错误码" min-width="140">
          <template #default="{ row }">
            <span v-if="row.errorCode" class="mono">{{ row.errorCode }}</span>
            <span v-else class="sub">—</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pager"
        layout="total, sizes, prev, pager, next"
        :total="logs.total"
        :current-page="logs.page"
        :page-size="logs.size"
        :page-sizes="[10, 20, 50, 100]"
        @current-change="onLogsPage"
        @size-change="onLogsSize"
      />
    </el-card>

    <!-- ==================== 四、外部调用日志 ==================== -->
    <el-card shadow="never" class="block">
      <template #header>
        <div class="chart-header">
          <span class="chart-title">外部调用日志</span>
          <span class="chart-hint">
            传输层视角：每次 HTTP 尝试一条（GET 重试会留两条）。日志只追加，没有删除入口
          </span>
        </div>
      </template>

      <div class="filter-row">
        <el-radio-group v-model="calls.connector" @change="loadCalls">
          <el-radio-button :value="''">全部</el-radio-button>
          <el-radio-button value="LLM">LLM</el-radio-button>
          <el-radio-button value="BAIDU_MAP">BAIDU_MAP</el-radio-button>
        </el-radio-group>
        <span class="sub">
          <b>安全验收点</b>：request_summary 在写入时已脱敏（先脱敏再截断），这里看到的应当是
          <code>ak=abcd****</code> 这种形态，绝不该有完整密钥。
        </span>
      </div>

      <el-alert v-if="calls.error" type="error" :closable="false" show-icon :title="calls.error" class="cost-alert" />

      <el-table :data="calls.records" size="small" border>
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="connector" label="连接器" width="110" />
        <el-table-column prop="apiName" label="接口" width="200">
          <template #default="{ row }"><span class="mono">{{ row.apiName || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="HTTP" width="80">
          <template #default="{ row }">{{ row.httpStatus ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="耗时(ms)" width="100">
          <template #default="{ row }">{{ row.durationMs ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="结果" width="80">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'" size="small">{{ row.success ? '成功' : '失败' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="requestSummary" label="请求摘要（已脱敏）" min-width="240">
          <template #default="{ row }"><span class="mono">{{ row.requestSummary || '—' }}</span></template>
        </el-table-column>
        <el-table-column prop="errorMsg" label="失败原因" min-width="180">
          <template #default="{ row }">{{ row.errorMsg || '—' }}</template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pager"
        layout="total, prev, pager, next"
        :total="calls.total"
        :current-page="calls.page"
        :page-size="calls.size"
        @current-change="onCallsPage"
      />
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
// 按需引入 echarts（echarts/core + 只注册用到的图表与组件）。
// 为什么不做整体 import：全量引入会把 echarts 的一千多个模块全拖进来 ——
// 实测本次构建因此卡在 Rollup 压缩阶段 7 分钟以上还没结束，产物也要多背约 1MB。
// ⚠️ 按需引入的代价：**漏注册任何一项，对应的图表会静默空白**（不报错）。
// 下面这一组覆盖了本页全部图表：折线/柱状/饼图 + 坐标系 + 提示框 + 图例 + Canvas 渲染器。
// 以后要加新图表类型（如散点、地图），必须同时在 use() 里补上对应的 Chart 组件。
import * as echarts from 'echarts/core'
import { LineChart, BarChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([LineChart, BarChart, PieChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])
import { getGenerationStats, getGenerationTrend, getGenerationLogs, getExternalCalls, getTripBreakdown } from '@/api/admin'

const STAGES = ['PARSE', 'CANDIDATE', 'PREORDER', 'COMPOSE', 'VALIDATE', 'ROUTE', 'COPY']
const MAP_MODES = ['VERIFIED', 'CACHED', 'ESTIMATED']

const loading = ref(false)
const trendRef = ref(null)
const stageRef = ref(null)
const modeRef = ref(null)
const errRef = ref(null)
const hotRef = ref(null)

const charts = {}

function today() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** 统计窗口，默认「今天」—— 手册要求的顶部卡片就是今日口径，换成范围只是顺带的增强 */
const range = ref([today(), today()])

const stats = ref({})
const days = ref([])
const logs = reactive({ total: 0, page: 1, size: 20, records: [] })
const filters = reactive({ success: null, stage: null, model: '', destination: '', mapMode: null })
const breakdowns = reactive({})
const breakdownLoading = reactive({})
const calls = reactive({ connector: '', total: 0, page: 1, size: 20, records: [], error: '' })

// ---------- 卡片 ----------

const costText = (value) => (value === null || value === undefined ? '未配置单价' : `¥ ${Number(value).toFixed(4)}`)

const statCards = computed(() => {
  const s = stats.value
  const total = s.totalCount
  return [
    { label: '生成阶段数', value: total ?? '—', hint: '一次生成写 5~7 条阶段日志' },
    { label: '生成次数', value: s.tripCount ?? '—', hint: '按 DISTINCT trip_id 计（不是阶段数）' },
    { label: '成功率', value: rateText(s.successRate) },
    { label: '平均耗时', value: msText(s.avgDurationMs) },
    { label: 'P95 耗时', value: msText(s.p95DurationMs), hint: '排除没有耗时记录的行' },
    { label: '总 token', value: s.totalTokens ?? '—' },
    { label: '平均单次 token', value: s.avgTokensPerTrip ?? '—', hint: '按生成次数摊' },
    { label: '总成本', value: costText(s.totalEstCost), hint: '预估，随单价配置变化' },
    { label: '平均单次成本', value: costText(s.avgCostPerTrip) }
  ]
})

const costUnavailable = computed(() => {
  const s = stats.value
  const hasRows = (s.totalCount ?? 0) > 0
  return hasRows && s.totalEstCost === null
})

function rateText(rate) {
  if (rate === null || rate === undefined) return '—'
  return `${(Number(rate) * 100).toFixed(1)}%`
}

function msText(ms) {
  if (ms === null || ms === undefined) return '—'
  return `${ms} ms`
}

// ---------- 加载 ----------

async function loadAll() {
  loading.value = true
  try {
    const [from, to] = range.value
    const [s, t] = await Promise.all([getGenerationStats(from, to), getGenerationTrend(7)])
    stats.value = s
    days.value = t.days || []
    await nextTick()
    renderCharts()
    await loadLogs()
    await loadCalls()
  } finally {
    loading.value = false
  }
}

async function loadLogs() {
  const [from, to] = range.value
  const params = { from, to, page: logs.page, size: logs.size }
  Object.entries(filters).forEach(([k, v]) => {
    // 只传真正填了的条件：空串会被后端当「没填」，但少传一个键更清楚
    if (v !== null && v !== undefined && v !== '') params[k] = v
  })
  const res = await getGenerationLogs(params)
  logs.total = res.total
  logs.page = res.page
  logs.size = res.size
  logs.records = res.records || []
}

function searchLogs() {
  logs.page = 1
  loadLogs()
}

function resetFilters() {
  Object.assign(filters, { success: null, stage: null, model: '', destination: '', mapMode: null })
  searchLogs()
}

function onLogsPage(p) {
  logs.page = p
  loadLogs()
}

function onLogsSize(s) {
  logs.size = s
  logs.page = 1
  loadLogs()
}

async function loadCalls() {
  const res = await getExternalCalls({ connector: calls.connector || undefined, page: calls.page, size: calls.size })
  calls.error = res.error || ''
  calls.total = res.total ?? 0
  calls.records = res.records || []
}

function onCallsPage(p) {
  calls.page = p
  loadCalls()
}

/** 展开某一行时按需拉该 trip 的分段，并缓存 —— 反复展开不该反复查库 */
async function expandRow(row, expandedRows) {
  const isExpanding = expandedRows.some((r) => r.id === row.id)
  if (!isExpanding || !row.tripId || breakdowns[row.tripId] || breakdownLoading[row.tripId]) return
  breakdownLoading[row.tripId] = true
  try {
    breakdowns[row.tripId] = await getTripBreakdown(row.tripId)
  } finally {
    breakdownLoading[row.tripId] = false
  }
}

// ---------- 图表 ----------

function renderCharts() {
  const dark = { color: '#303133' }
  const axisStyle = {
    axisLine: { lineStyle: { color: '#dcdfe6' } },
    axisLabel: { color: '#606266' },
    splitLine: { lineStyle: { color: '#f0f2f5' } }
  }

  // ① 近 7 天趋势（双 Y 轴）
  // 成本为 null 时**不画点**（null 会让折线出现断口）—— 用 0 代替会谎称「那天没花钱」
  const trend = echarts.init(trendRef.value)
  trend.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['生成次数', '预估成本(元)'], textStyle: dark },
    grid: { left: 50, right: 60, top: 40, bottom: 30 },
    xAxis: { type: 'category', data: days.value.map((d) => String(d.statDate).slice(5)), ...axisStyle },
    yAxis: [
      { type: 'value', name: '次数', minInterval: 1, ...axisStyle },
      { type: 'value', name: '元', ...axisStyle, splitLine: { show: false } }
    ],
    series: [
      {
        name: '生成次数',
        type: 'line',
        smooth: true,
        itemStyle: { color: '#409eff' },
        areaStyle: { opacity: 0.08 },
        data: days.value.map((d) => d.totalCount ?? 0)
      },
      {
        name: '预估成本(元)',
        type: 'line',
        smooth: true,
        yAxisIndex: 1,
        itemStyle: { color: '#e6a23c' },
        connectNulls: false,
        data: days.value.map((d) => (d.estCost === null || d.estCost === undefined ? null : Number(d.estCost)))
      }
    ]
  })
  charts.trend = trend

  const stageRows = stats.value.stageBreakdown || []
  // ② 各阶段平均耗时（横向柱状，按管线顺序）
  const stage = echarts.init(stageRef.value)
  stage.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 90, right: 30, top: 20, bottom: 30 },
    xAxis: { type: 'value', name: 'ms', ...axisStyle },
    yAxis: { type: 'category', data: stageRows.map((r) => r.stage), ...axisStyle },
    series: [
      {
        name: '平均耗时',
        type: 'bar',
        itemStyle: { color: '#409eff' },
        label: { show: true, position: 'right', formatter: '{c}' },
        data: stageRows.map((r) => r.avgDurationMs ?? 0)
      }
    ]
  })
  charts.stage = stage

  // ③ mapMode 分布
  const modeRows = stats.value.mapModeBreakdown || []
  const mode = echarts.init(modeRef.value)
  mode.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0, textStyle: dark },
    // 颜色跟三色语义对齐：绿=实测 / 灰=缓存 / 橙=估算（与行程页角标一致）
    color: ['#67c23a', '#909399', '#e6a23c', '#c0c4cc'],
    series: [
      {
        name: '地图模式',
        type: 'pie',
        radius: ['40%', '65%'],
        label: { formatter: '{b}\n{c}' },
        data: modeRows.map((r) => ({ name: r.mapMode || 'UNKNOWN', value: r.count }))
      }
    ]
  })
  charts.mode = mode

  // ④ 失败原因 Top 5
  const errRows = (stats.value.topErrors || []).slice(0, 5)
  const err = echarts.init(errRef.value)
  err.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 140, right: 30, top: 20, bottom: 30 },
    xAxis: { type: 'value', minInterval: 1, ...axisStyle },
    yAxis: { type: 'category', data: errRows.map((r) => r.errorCode), ...axisStyle },
    series: [
      {
        name: '次数',
        type: 'bar',
        itemStyle: { color: '#f56c6c' },
        label: { show: true, position: 'right', formatter: '{c}' },
        data: errRows.map((r) => r.cnt)
      }
    ]
  })
  charts.err = err

  // ⑤ 耗时大头（同一份数据的降序视图，用于一眼看出瓶颈阶段）
  const hotRows = [...stageRows].sort((a, b) => (b.avgDurationMs ?? 0) - (a.avgDurationMs ?? 0))
  const hot = echarts.init(hotRef.value)
  hot.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 90, right: 60, top: 20, bottom: 30 },
    xAxis: { type: 'value', name: 'ms', ...axisStyle },
    yAxis: { type: 'category', data: hotRows.map((r) => r.stage), ...axisStyle },
    series: [
      {
        name: '平均耗时',
        type: 'bar',
        itemStyle: { color: '#909399' },
        label: { show: true, position: 'right', formatter: '{c} ms' },
        data: hotRows.map((r) => r.avgDurationMs ?? 0)
      }
    ]
  })
  charts.hot = hot
}

function resizeCharts() {
  Object.values(charts).forEach((c) => c && c.resize())
}

// ---------- 展示工具 ----------

/** 后端可能给 ISO 字符串，也可能是 Jackson 数组形式 [2026,9,22,21,47,11] —— 两种都吃掉 */
function formatTime(v) {
  if (!v) return '—'
  if (Array.isArray(v)) {
    const [y, mo, d, h, mi, s] = v
    const p = (n) => String(n ?? 0).padStart(2, '0')
    return `${y}-${p(mo)}-${p(d)} ${p(h)}:${p(mi)}:${p(s)}`
  }
  return String(v).replace('T', ' ').slice(0, 19)
}

function modeTagType(mode) {
  if (mode === 'VERIFIED') return 'success'
  if (mode === 'CACHED') return 'info'
  if (mode === 'ESTIMATED') return 'warning'
  return 'info'
}

onMounted(() => {
  loadAll()
  window.addEventListener('resize', resizeCharts)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
  // 必须 dispose：图表实例持有 canvas 与内部监听，不销毁会随路由切换累积
  Object.values(charts).forEach((c) => c && c.dispose())
})
</script>

<style scoped lang="scss">
.toolbar {
  margin-bottom: 16px;

  .toolbar-row {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
  }

  .label {
    color: #909399;
    font-size: 14px;
  }

  .refresh-btn {
    margin-left: auto;
  }
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.stat-card {
  :deep(.el-card__body) {
    padding: 14px 16px;
  }

  .stat-label {
    font-size: 13px;
    color: #909399;
  }

  .stat-value {
    margin-top: 6px;
    font-size: 22px;
    font-weight: 600;
    color: #303133;
  }

  .stat-hint {
    margin-top: 4px;
    font-size: 12px;
    color: #c0c4cc;
  }
}

.cost-alert {
  margin-bottom: 16px;
}

.chart-row {
  margin-bottom: 16px;
}

.chart-card {
  .chart-header {
    display: flex;
    align-items: baseline;
    gap: 10px;
    flex-wrap: wrap;
  }

  .chart-title {
    font-size: 15px;
    font-weight: 600;
  }

  .chart-hint {
    font-size: 12px;
    color: #909399;
  }
}

/* 图表容器必须有明确高度 —— 否则 ECharts 初始化时量到 0 高度，图表会静默不显示 */
.chart {
  width: 100%;

  &.chart-trend {
    height: 280px;
  }

  &.chart-mid {
    height: 260px;
  }
}

.block {
  margin-bottom: 16px;
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.expand-cell {
  padding: 6px 12px;
}

.expand-total {
  margin-top: 8px;
}

.sub {
  font-size: 12px;
  color: #909399;
}

.mono {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}
</style>
