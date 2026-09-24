<template>
  <div class="connectors-page" v-loading="loading">
    <!-- ==================== 顶部状态条 ==================== -->
    <!-- 一眼回答「现在到底跑的哪家、是不是降级中、今天花了多少」——
         管理员最常问的三个问题，不放在折叠里。 -->
    <el-card class="status-bar" shadow="never">
      <div class="status-row">
        <div class="status-item">
          <span class="label">当前厂商</span>
          <strong>{{ llm.activeProvider || '未启用' }}</strong>
          <span v-if="llm.model" class="sub">{{ llm.model }}</span>
        </div>
        <div class="status-item">
          <span class="label">降级状态</span>
          <el-tag v-if="llm.isFallback" type="warning" size="small" effect="dark">已降级（主力不可用）</el-tag>
          <el-tag v-else-if="llm.enabled" type="success" size="small">主力正常</el-tag>
          <el-tag v-else type="info" size="small">大模型已关闭</el-tag>
        </div>
        <div class="status-item">
          <span class="label">地图能力</span>
          <el-tag :type="map.enabled ? 'success' : 'warning'" size="small" effect="dark">
            {{ map.enabled ? map.mode : '已关闭（估算模式）' }}
          </el-tag>
        </div>
        <div class="status-item">
          <span class="label">今日外部调用</span>
          <strong>{{ llm.todayCallCount ?? 0 }}</strong>
          <span class="sub">次</span>
        </div>
        <div class="status-item">
          <span class="label">今日生成 / token</span>
          <strong>{{ llm.todayGenerationCount ?? 0 }}</strong>
          <span class="sub">次 · {{ llm.todayTokens ?? 0 }} tokens</span>
        </div>
        <div class="status-item">
          <span class="label">今日预估成本</span>
          <strong>{{ costText }}</strong>
        </div>
        <el-button class="refresh-btn" :icon="Refresh" circle @click="loadAll" />
      </div>
    </el-card>

    <!-- ==================== 一、大模型 ==================== -->
    <el-card class="block" shadow="never">
      <template #header>
        <div class="block-header">
          <span class="block-title">大模型</span>
          <span class="block-hint">改动立即生效，无需重启</span>
        </div>
      </template>

      <el-form label-position="top" class="inline-form">
        <el-form-item label="当前使用厂商">
          <el-radio-group v-model="llm.activeProvider" @change="saveActiveProvider">
            <el-radio-button v-for="p in providers" :key="p.name" :value="p.name">
              {{ p.name }}
            </el-radio-button>
          </el-radio-group>
          <div class="field-hint">
            指定某家做文案对比时用的也是这个键；mock 是「一家都不可用」时的兜底，不参与降级链。
          </div>
        </el-form-item>

        <el-form-item label="降级顺序（逗号分隔，靠前优先）">
          <div class="row-inline">
            <el-input v-model="llm.fallbackOrder" placeholder="qwen,glm,deepseek" style="max-width: 340px" />
            <el-button type="primary" @click="saveFallbackOrder">保存</el-button>
          </div>
          <div class="field-hint">
            ⚠️ 传空串会被后端忽略（配置不变）—— 想回到 L1 默认值请改 application.yml。顺序里的名字必须与厂商名一致。
          </div>
        </el-form-item>

        <el-form-item label="大模型能力总开关">
          <el-switch
            v-model="llm.enabled"
            active-text="启用"
            inactive-text="关闭"
            @change="toggleLlmEnabled"
          />
          <div class="field-hint">关闭后前台的 AI 规划入口会直接报「能力已被管理员关闭」，不会走 Mock 兜底。</div>
        </el-form-item>
      </el-form>

      <div class="cards">
        <el-card v-for="p in providers" :key="p.name" class="provider-card" shadow="hover">
          <div class="card-head">
            <span class="card-name">{{ p.name }}</span>
            <el-tag v-if="!p.hasL1Config" type="info" size="small">内置</el-tag>
            <el-tag v-else-if="p.keyConfigured" type="success" size="small">已配置</el-tag>
            <el-tag v-else type="danger" size="small">未配置 Key</el-tag>
            <el-tag v-if="p.breakerOpen" type="danger" size="small" effect="dark">熔断中</el-tag>
          </div>

          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="模型名">
              {{ p.model || '—' }}
              <el-tag size="small" type="info" effect="plain">L1 只读</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="Base URL">{{ p.baseUrl || '—' }}</el-descriptions-item>
            <el-descriptions-item label="API Key">
              <span class="mono">{{ p.apiKeyMasked || '—' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="可用性">
              {{ p.available ? '可用' : '不可用' }}
              <span v-if="p.reason" class="sub">（{{ p.reason }}）</span>
            </el-descriptions-item>
            <el-descriptions-item label="熔断计数">
              {{ p.breakerFailCount ?? 0 }} / {{ p.breakerThreshold ?? '—' }}
              <span v-if="p.breakerOpen" class="sub">（{{ p.breakerRemainingSeconds ?? '?' }} 秒后放行试探）</span>
            </el-descriptions-item>
          </el-descriptions>

          <div class="card-foot">
            <el-button size="small" :loading="pinging[p.name]" @click="doPing(p.name)">测试连通性</el-button>
            <span v-if="pingResults[p.name]" class="ping-result">
              <el-tag :type="pingResults[p.name].success ? 'success' : 'danger'" size="small">
                {{ pingResults[p.name].success ? `成功 ${pingResults[p.name].durationMs ?? 0}ms` : (pingResults[p.name].failureType || '失败') }}
              </el-tag>
              <span v-if="pingResults[p.name].reason" class="sub">{{ pingResults[p.name].reason }}</span>
            </span>
          </div>
        </el-card>
      </div>

      <div class="block-note">
        ⚠️ 模型名 / Base URL / API Key 来自 <b>application.yml</b> 与 <b>.env.properties</b>（L1 启动期配置），
        这里只展示不可在线改 —— 要换模型请改配置后重启。各家版本的 Key 一律掩码，且不会写入任何日志。
      </div>
    </el-card>

    <!-- ==================== 二、百度地图 ==================== -->
    <el-card class="block" shadow="never">
      <template #header>
        <div class="block-header">
          <span class="block-title">百度地图</span>
          <el-button type="warning" size="small" @click="drillFallback">一键降级演练</el-button>
        </div>
      </template>

      <el-form label-position="top" class="inline-form">
        <el-form-item label="地图能力总开关">
          <el-switch
            v-model="map.enabled"
            active-text="开启"
            inactive-text="关闭"
            @change="toggleMap"
          />
          <div class="field-hint">
            关闭是<b>能力降级</b>而非功能降级：规划照常跑，距离与时长留空并标注「估算」，
            不会编造精确数字。且关闭后零外部调用，不消耗任何百度配额。
          </div>
        </el-form-item>

        <el-form-item label="百度地图 Web 服务 AK">
          <div class="row-inline">
            <el-input
              v-model="map.akInput"
              :placeholder="map.akMasked || '未配置（留空表示不修改）'"
              style="max-width: 340px"
              show-password
            />
            <el-button type="primary" :disabled="!map.akInput" @click="saveMapAk">保存新 AK</el-button>
            <el-button :disabled="!map.akMasked" @click="clearMapAk">清空</el-button>
          </div>
          <div class="field-hint">
            显示的是掩码（前 4 位 + ****），<b>输入框留空表示不修改</b> —— 千万不要把掩码串当新值提交回来，
            那会把 AK 写成乱码。改完立即生效，不用重启。
          </div>
        </el-form-item>

        <el-form-item label="连通性测试">
          <div class="row-inline">
            <el-input v-model="mapPingForm.city" placeholder="城市，如 泉州" style="max-width: 150px" />
            <el-input v-model="mapPingForm.keyword" placeholder="关键词，如 开元寺" style="max-width: 150px" />
            <el-button :loading="mapPinging" @click="doMapPing">测试连通性</el-button>
            <span class="field-hint inline">会真实发起一次 POI 检索 —— 地点检索免费额度只有 100 次/天，别连点。</span>
          </div>
          <div v-if="mapPingResult" class="ping-panel">
            <el-tag :type="mapPingResult.success ? 'success' : 'danger'" size="small">
              {{ mapPingResult.success ? '成功' : (mapPingResult.failureType || '失败') }}
            </el-tag>
            <span v-if="mapPingResult.mode" class="sub">模式 {{ mapPingResult.mode }}</span>
            <span v-if="mapPingResult.durationMs != null" class="sub">耗时 {{ mapPingResult.durationMs }}ms</span>
            <span v-if="mapPingResult.hitCount != null" class="sub">命中 {{ mapPingResult.hitCount }} 条</span>
            <div v-if="mapPingResult.samples && mapPingResult.samples.length" class="sub">
              样例：{{ mapPingResult.samples.join(' / ') }}
            </div>
            <div v-if="mapPingResult.reason" class="sub">{{ mapPingResult.reason }}</div>
          </div>
        </el-form-item>
      </el-form>

      <el-descriptions :column="3" size="small" border>
        <el-descriptions-item label="可用性">
          {{ map.available ? '可用' : '不可用' }}
        </el-descriptions-item>
        <el-descriptions-item label="当前模式">
          <el-tag :type="modeTagType(map.mode)" size="small">{{ map.mode || '—' }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="熔断">
          <el-tag :type="map.breakerOpen ? 'danger' : 'success'" size="small" effect="dark">
            {{ map.breakerOpen ? '已打开' : '正常' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="近 1 小时调用">{{ map.calls1h ?? 0 }} 次</el-descriptions-item>
        <el-descriptions-item label="近 1 小时失败率">{{ rateText(map.failRate1h) }}</el-descriptions-item>
        <el-descriptions-item label="今日调用">{{ map.todayCallCount ?? 0 }} 次</el-descriptions-item>
        <el-descriptions-item label="最近成功时间">{{ formatTime(map.lastSuccessAt) }}</el-descriptions-item>
        <el-descriptions-item label="最近失败时间">{{ formatTime(map.lastErrorAt) }}</el-descriptions-item>
        <el-descriptions-item label="最近失败原因">{{ map.lastErrorMessage || '—' }}</el-descriptions-item>
      </el-descriptions>
      <div v-if="map.reason" class="block-note">当前决策说明：{{ map.reason }}</div>
    </el-card>

    <!-- ==================== 三、行程参数 / 熔断 / 单价 ==================== -->
    <el-card class="block" shadow="never">
      <template #header>
        <div class="block-header">
          <span class="block-title">行程参数 · 熔断阈值 · 预估单价</span>
          <div>
            <el-button size="small" :disabled="!dirtyKeys.length" @click="resetParams">撤销改动</el-button>
            <el-button type="primary" size="small" :loading="savingParams" :disabled="!dirtyKeys.length" @click="saveParams">
              保存 {{ dirtyKeys.length }} 项
            </el-button>
          </div>
        </div>
      </template>

      <el-alert
        v-if="missingParamKeys.length"
        type="warning"
        :closable="false"
        show-icon
        class="miss-alert"
        :title="`以下 ${missingParamKeys.length} 个配置键在数据库里还不存在，暂时无法修改`"
      >
        <div class="sub">
          {{ missingParamKeys.join('、') }}<br />
          原因：`db/schema-trip.sql` 里新增的键需要先在数据库执行一次（执行 SQL 属于写库操作，交给项目维护者决定时机）。
          读不到这些键不会影响功能 —— 代码里有默认值兜底，行为一致。
        </div>
      </el-alert>

      <el-table :data="paramRows" size="small" class="param-table">
        <el-table-column prop="key" label="配置键" width="240">
          <template #default="{ row }">
            <span class="mono">{{ row.key }}</span>
          </template>
        </el-table-column>
        <el-table-column label="值" width="200">
          <template #default="{ row }">
            <el-input
              v-model="paramEdits[row.key]"
              :class="{ 'is-dirty': isDirty(row.key) }"
              size="small"
            />
          </template>
        </el-table-column>
        <el-table-column prop="description" label="说明" />
      </el-table>

      <div class="block-note">
        单价单位是「元 / 百万 token」，需要<b>按厂商官网实际报价</b>自己填 —— 留 0 表示未配置，
        此时成本显示「未配置」而不是 0。成本是会写进简历与答辩的数字，宁可不显示也不编造。
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  getConnectors,
  getLlmProviders,
  pingLlm,
  pingMap,
  getConfigs,
  setConfig,
  setLlmConfig,
  setMapEnabled
} from '@/api/admin'

/** 参数表要展示的键（顺序即界面顺序）：行程 → 熔断 → 单价 */
const PARAM_KEYS = [
  'trip.max-days',
  'trip.max-candidate',
  'trip.max-replan-rounds',
  'trip.poi-cache-ttl-hours',
  'trip.sse-timeout-ms',
  'llm.timeout-ms',
  'llm.breaker.fail-threshold',
  'llm.breaker.open-seconds',
  'map.breaker.fail-threshold',
  'map.breaker.open-seconds',
  'llm.price.qwen-input',
  'llm.price.qwen-output',
  'llm.price.glm-input',
  'llm.price.glm-output',
  'llm.price.deepseek-input',
  'llm.price.deepseek-output'
]

const loading = ref(false)
const pinging = reactive({})
const pingResults = reactive({})
const mapPinging = ref(false)
const mapPingResult = ref(null)
const savingParams = ref(false)

const providers = ref([])
const llm = reactive({ activeProvider: '', model: '', enabled: true, isFallback: false, todayCallCount: 0, todayTokens: 0, todayEstCost: null, todayGenerationCount: 0 })
const map = reactive({ enabled: false, mode: '', available: false, reason: '', breakerOpen: false, akMasked: '', akInput: '', lastSuccessAt: null, lastErrorAt: null, lastErrorMessage: null, calls1h: 0, failures1h: 0, failRate1h: null, todayCallCount: 0 })

/** 数据库里真实存在的配置行：key -> { value, description } */
const configByKey = ref({})
/** 参数表的本地编辑值：key -> string */
const paramEdits = reactive({})
const mapPingForm = reactive({ city: '泉州', keyword: '开元寺' })

const paramRows = computed(() =>
  PARAM_KEYS.filter((k) => configByKey.value[k]).map((k) => ({
    key: k,
    description: configByKey.value[k].description || ''
  }))
)
const missingParamKeys = computed(() => PARAM_KEYS.filter((k) => !configByKey.value[k]))
const dirtyKeys = computed(() =>
  PARAM_KEYS.filter((k) => configByKey.value[k] && paramEdits[k] !== configByKey.value[k].value)
)
const costText = computed(() => {
  const v = llm.todayEstCost
  if (v === null || v === undefined) return '未配置单价'
  return `¥ ${Number(v).toFixed(4)}`
})

// ---------- 加载 ----------

async function loadAll() {
  loading.value = true
  try {
    const [conn, prov, grouped] = await Promise.all([getConnectors(), getLlmProviders(), getConfigs()])
    // ⚠️ 必须先取 .data：request.js 的响应拦截器最后 `return res`，
    // 即把 { code, message, data, timestamp } 整包返回（项目统一惯例，另外 13 个页面都是这么用的）。
    // 早期这里漏了 .data，导致 applyConnectors 收到的是整包、conn.map 恒为 undefined：
    //   map.enabled = !!undefined       → 恒 false（表现为「开关点开又自己弹回关闭」）
    //   llm.enabled = undefined !== false → 恒 true（碰巧显示正确，所以更难发现）
    //   厂商卡片数 = 0，参数表也全空。
    applyConnectors(conn.data || {})
    providers.value = prov.data?.providers || []
    if (prov.data?.activeProvider) llm.activeProvider = prov.data.activeProvider
    applyConfigs(grouped.data || {})
  } catch (e) {
    // 拦截器已经弹过错误提示，这里只保证页面不至于卡在 loading
    console.warn('加载连接器状态失败', e)
  } finally {
    loading.value = false
  }
}

function applyConnectors(conn) {
  const l = conn.llm || {}
  const m = conn.map || {}
  Object.assign(llm, {
    activeProvider: l.activeProvider || llm.activeProvider,
    model: l.model || '',
    enabled: l.enabled !== false,
    isFallback: !!l.isFallback,
    todayCallCount: l.todayCallCount ?? 0,
    todayTokens: l.todayTokens ?? 0,
    todayEstCost: l.estCost ?? null,
    todayGenerationCount: l.todayGenerationCount ?? 0
  })
  Object.assign(map, {
    enabled: !!m.enabled,
    mode: m.mode || '',
    available: !!m.available,
    reason: m.reason || '',
    breakerOpen: !!m.breakerOpen,
    lastSuccessAt: m.lastSuccessAt ?? null,
    lastErrorAt: m.lastErrorAt ?? null,
    lastErrorMessage: m.lastErrorMessage ?? null,
    calls1h: m.calls1h ?? 0,
    failures1h: m.failures1h ?? 0,
    failRate1h: m.failRate1h ?? null,
    todayCallCount: m.todayCallCount ?? 0
  })
}

function applyConfigs(grouped) {
  const flat = {}
  Object.values(grouped || {}).forEach((rows) => {
    (rows || []).forEach((r) => {
      flat[r.configKey] = { value: r.configValue, description: r.description }
    })
  })
  configByKey.value = flat

  // 敏感项只拿掩码做占位提示，绝不放进可提交的输入框
  map.akMasked = flat['map.baidu.ak'] ? flat['map.baidu.ak'].value : ''
  map.akInput = ''

  const fallback = flat['llm.fallback-order']
  if (fallback) llm.fallbackOrder = fallback.value
  const enabled = flat['llm.enabled']
  if (enabled) llm.enabled = enabled.value === 'true'
  const active = flat['llm.active-provider']
  if (active && !llm.activeProvider) llm.activeProvider = active.value

  PARAM_KEYS.forEach((k) => {
    if (flat[k] && paramEdits[k] === undefined) paramEdits[k] = flat[k].value
  })
}

// ---------- 大模型 ----------

async function saveActiveProvider() {
  await setLlmConfig({ activeProvider: llm.activeProvider })
  ElMessage.success(`已切换为 ${llm.activeProvider}，立即生效`)
  await loadAll()
}

async function saveFallbackOrder() {
  if (!llm.fallbackOrder || !llm.fallbackOrder.trim()) {
    ElMessage.warning('降级顺序不能为空（空值会被后端忽略）')
    return
  }
  await setLlmConfig({ fallbackOrder: llm.fallbackOrder.trim() })
  ElMessage.success('降级顺序已保存，立即生效')
  await loadAll()
}

async function toggleLlmEnabled(val) {
  if (!val) {
    try {
      await ElMessageBox.confirm(
        '关闭后前台 AI 规划入口会直接提示「能力已被管理员关闭」（不会静默走 Mock）。确定关闭吗？',
        '关闭大模型能力',
        { type: 'warning', confirmButtonText: '关闭', cancelButtonText: '取消' }
      )
    } catch (e) {
      llm.enabled = true
      return
    }
  }
  await setConfig('llm.enabled', String(val))
  ElMessage.success('已保存，立即生效')
  await loadAll()
}

async function doPing(name) {
  pinging[name] = true
  try {
    // 指定厂商 ping —— 走的是「不降级」路径，结果才是这一家的真实状态
    // ⚠️ 取 .data：拦截器返回的是整包（见 loadAll 的说明）
    pingResults[name] = (await pingLlm(name)).data
  } finally {
    pinging[name] = false
  }
}

// ---------- 地图 ----------

async function toggleMap(val) {
  await setMapEnabled(val)
  ElMessage.success(val ? '地图能力已开启' : '地图能力已关闭，后续行程将标注为估算值')
  await loadAll()
}

async function saveMapAk() {
  const value = (map.akInput || '').trim()
  if (!value) return
  await setConfig('map.baidu.ak', value)
  ElMessage.success('AK 已保存，立即生效')
  await loadAll()
}

async function clearMapAk() {
  try {
    await ElMessageBox.confirm(
      '清空后地图将无法调用（等同未配置 AK），已有缓存仍可命中。确定清空吗？',
      '清空地图 AK',
      { type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  await setConfig('map.baidu.ak', '')
  ElMessage.success('AK 已清空')
  await loadAll()
}

async function doMapPing() {
  mapPinging.value = true
  try {
    // ⚠️ 取 .data：拦截器返回的是整包（见 loadAll 的说明）
    mapPingResult.value = (await pingMap(mapPingForm.city, mapPingForm.keyword)).data
  } finally {
    mapPinging.value = false
  }
}

/** 答辩演示按钮：关掉地图 → 明确告诉人「这不是坏了，是降级」 */
async function drillFallback() {
  try {
    await ElMessageBox.confirm(
      '即将关闭百度地图能力。关闭后新生成的行程不再有实测距离与时长，将标注为估算值；系统功能不受影响，随时可以再打开。',
      '一键降级演练',
      { type: 'warning', confirmButtonText: '关闭地图', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  await setMapEnabled(false)
  await loadAll()
  ElMessageBox.alert(
    '已切换为估算模式，后续生成的行程将标注为估算值，系统功能不受影响。',
    '降级完成',
    { type: 'success', confirmButtonText: '知道了' }
  )
}

// ---------- 参数表 ----------

function isDirty(key) {
  return configByKey.value[key] && paramEdits[key] !== configByKey.value[key].value
}

function resetParams() {
  PARAM_KEYS.forEach((k) => {
    if (configByKey.value[k]) paramEdits[k] = configByKey.value[k].value
  })
  ElMessage.info('已撤销未保存的改动')
}

async function saveParams() {
  const keys = dirtyKeys.value
  if (!keys.length) return
  savingParams.value = true
  const failed = []
  try {
    // 逐键写：后端只有单键写入接口，且每个键都要各自清一次缓存 —— 串行执行最直观
    for (const key of keys) {
      try {
        await setConfig(key, paramEdits[key])
      } catch (e) {
        failed.push(key)
      }
    }
    if (failed.length) {
      ElMessage.warning(`${keys.length - failed.length} 项已保存，${failed.length} 项失败：${failed.join('、')}`)
    } else {
      ElMessage.success(`${keys.length} 项已保存，立即生效`)
    }
    await loadAll()
  } finally {
    savingParams.value = false
  }
}

// ---------- 展示工具 ----------

/**
 * 时间格式化。后端可能给 ISO 字符串，也可能是 Jackson 的数组形式 [2026,9,22,21,47,11]
 * —— 两种都吃掉，页面才不会因为序列化配置变化而显示出 [object Object]。
 */
function formatTime(v) {
  if (!v) return '—'
  if (Array.isArray(v)) {
    const [y, mo, d, h, mi, s] = v
    const p = (n) => String(n ?? 0).padStart(2, '0')
    return `${y}-${p(mo)}-${p(d)} ${p(h)}:${p(mi)}:${p(s)}`
  }
  return String(v).replace('T', ' ').slice(0, 19)
}

/** 分母为 0 时后端给 null（「没跑过」≠「全失败」），这里也照实显示 */
function rateText(rate) {
  if (rate === null || rate === undefined) return '—'
  return `${(Number(rate) * 100).toFixed(1)}%`
}

function modeTagType(mode) {
  if (mode === 'VERIFIED') return 'success'
  if (mode === 'CACHED') return 'info'
  if (mode === 'ESTIMATED') return 'warning'
  return 'info'
}

onMounted(loadAll)
</script>

<style scoped lang="scss">
.status-bar {
  margin-bottom: 16px;

  .status-row {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 24px;
  }

  .status-item {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 14px;

    .label {
      color: #909399;
    }
    .sub {
      color: #909399;
      font-size: 12px;
    }
  }

  .refresh-btn {
    margin-left: auto;
  }
}

.block {
  margin-bottom: 16px;

  .block-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .block-title {
    font-size: 15px;
    font-weight: 600;
  }

  .block-hint {
    font-size: 12px;
    color: #909399;
  }

  .block-note {
    margin-top: 12px;
    padding: 10px 12px;
    background: #fafafa;
    border-left: 3px solid #dcdfe6;
    border-radius: 4px;
    font-size: 12px;
    line-height: 1.7;
    color: #606266;
  }
}

.inline-form {
  :deep(.el-form-item) {
    margin-bottom: 18px;
  }
}

.row-inline {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.field-hint {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: #909399;

  &.inline {
    margin-top: 0;
  }
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 12px;
  margin-top: 4px;
}

.provider-card {
  .card-head {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 10px;
  }

  .card-name {
    font-size: 15px;
    font-weight: 600;
  }

  .card-foot {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
    margin-top: 10px;
  }

  .ping-result {
    display: flex;
    align-items: center;
    gap: 6px;
  }
}

.sub {
  font-size: 12px;
  color: #909399;
}

.mono {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}

.ping-panel {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 8px;
  padding: 10px;
  background: #fafafa;
  border-radius: 4px;
}

.miss-alert {
  margin-bottom: 12px;
}

.param-table {
  .is-dirty {
    :deep(.el-input__wrapper) {
      box-shadow: 0 0 0 1px #e6a23c inset;
    }
  }
}
</style>
