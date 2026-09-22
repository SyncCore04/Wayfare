<template>
  <div class="plan-page container">
    <h2 class="page-title">AI 行程规划</h2>

    <el-steps :active="step" align-center finish-status="success" class="plan-steps">
      <el-step title="输入需求" />
      <el-step title="确认参数" />
      <el-step title="生成行程" />
      <el-step title="查看与编辑" />
    </el-steps>

    <!-- ==================== Step 1 · 输入 ==================== -->
    <div v-if="step === 0" class="step-panel">
      <el-input
        v-model="rawInput"
        type="textarea"
        :rows="4"
        maxlength="200"
        show-word-limit
        placeholder="周末想去寿阳玩两天，喜欢古建筑，预算 500"
      />

      <!-- 本次将参考的偏好 -->
      <div class="pref-block">
        <div class="block-head">
          <span class="block-title">本次将参考的偏好</span>
          <el-button v-if="prefChips.length" link size="small" @click="toggleAllPrefs">
            {{ allPrefsOn ? '全部取消' : '全部选中' }}
          </el-button>
        </div>

        <div v-if="!profileLoaded" class="block-hint">正在读取你的偏好…</div>

        <div v-else-if="!profile || !profileHasContent" class="block-hint">
          你还没有填写旅行偏好，<router-link to="/profile?tab=preference" class="link">去完善</router-link>
        </div>

        <div v-else-if="profile.allowAiUse === 0" class="block-hint muted">
          已关闭偏好使用 —— 你在个人中心关掉了「允许 AI 使用我的偏好」，本次不会参考任何偏好
        </div>

        <template v-else>
          <div class="chip-row">
            <el-check-tag
              v-for="chip in prefChips"
              :key="chip.key"
              :checked="!excludedPrefs.includes(chip.key)"
              @change="togglePref(chip.key)"
            >{{ chip.label }}</el-check-tag>
          </div>
          <p v-if="excludedPrefs.length" class="block-hint warn">
            已取消 {{ excludedPrefs.length }} 项。注意：当前版本只支持偏好整体开关，
            取消单项目前不会改变实际注入内容；若想完全不用偏好，请点「全部取消」。
          </p>
        </template>
      </div>

      <!-- 本次临时忌口 -->
      <div class="pref-block">
        <div class="block-head">
          <span class="block-title">本次临时忌口</span>
          <span class="block-sub">与长期画像里的忌口取并集，只影响这一次</span>
        </div>
        <div class="tag-adder">
          <el-input
            v-model="tabooInput"
            size="small"
            placeholder="输入后回车添加，如「香菜」"
            @keyup.enter="addTempTaboo"
          />
          <el-button size="small" @click="addTempTaboo">添加</el-button>
        </div>
        <div v-if="tempTaboos.length" class="chip-row">
          <el-tag v-for="(t, i) in tempTaboos" :key="t" closable type="danger" effect="light" @close="tempTaboos.splice(i, 1)">
            {{ t }}
          </el-tag>
        </div>
      </div>

      <div class="step-actions">
        <el-button type="primary" size="large" :loading="parsing" :disabled="!rawInput.trim()" @click="startParse">
          开始规划
        </el-button>
      </div>
    </div>

    <!-- ==================== Step 2 · 确认参数 ==================== -->
    <div v-else-if="step === 1" class="step-panel">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="confirm-tip"
        title="AI 已经理解你的需求，但下面这些参数是它「猜」的 —— 确认或改掉，再开始生成"
      />

      <el-form label-position="top" class="confirm-form">
        <el-form-item>
          <template #label>
            目的地
            <el-tag v-if="needConfirm.has('destination')" size="small" type="warning" effect="light">AI 猜测，请确认</el-tag>
          </template>
          <el-input v-model="form.destination" placeholder="如「寿阳」" />
        </el-form-item>

        <el-form-item>
          <template #label>
            出发日期
            <el-tag v-if="needConfirm.has('startDate')" size="small" type="warning" effect="light">AI 猜测，请确认</el-tag>
          </template>
          <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" placeholder="选择出发日期" />
        </el-form-item>

        <el-form-item>
          <template #label>
            天数
            <el-tag v-if="needConfirm.has('days')" size="small" type="warning" effect="light">AI 猜测，请确认</el-tag>
          </template>
          <el-input-number v-model="form.days" :min="1" :max="10" />
        </el-form-item>

        <el-form-item>
          <template #label>
            预算
            <el-tag v-if="needConfirm.has('budgetMode')" size="small" type="warning" effect="light">AI 猜测，请确认</el-tag>
          </template>
          <div class="inline-row">
            <el-input-number v-model="form.budgetTotal" :min="0" :step="100" />
            <el-radio-group v-model="form.budgetMode">
              <el-radio value="PER_PERSON">人均</el-radio>
              <el-radio value="TOTAL">总计</el-radio>
            </el-radio-group>
          </div>
          <p class="field-hint">人均与总计差好几倍，所以这里必须你确认一次</p>
        </el-form-item>

        <el-form-item>
          <template #label>
            交通方式
            <el-tag v-if="needConfirm.has('transport')" size="small" type="warning" effect="light">AI 猜测，请确认</el-tag>
          </template>
          <el-select v-model="form.transport" placeholder="不限" clearable>
            <el-option label="公共交通" value="PUBLIC" />
            <el-option label="自驾" value="DRIVE" />
            <el-option label="步行" value="WALK" />
            <el-option label="混合" value="MIX" />
          </el-select>
        </el-form-item>

        <el-form-item>
          <template #label>
            同行人
            <el-tag v-if="needConfirm.has('companion')" size="small" type="warning" effect="light">AI 猜测，请确认</el-tag>
          </template>
          <el-input v-model="form.companion" placeholder="如「情侣」「带长辈」" />
        </el-form-item>

        <el-form-item>
          <template #label>
            节奏
            <el-tag v-if="needConfirm.has('pace')" size="small" type="warning" effect="light">AI 猜测，请确认</el-tag>
          </template>
          <el-radio-group v-model="form.pace">
            <el-radio :value="1">慢</el-radio>
            <el-radio :value="2">适中</el-radio>
            <el-radio :value="3">紧凑</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>

      <p class="confirm-note">确认后 AI 会检索真实景点并排布行程，预计需要 30–60 秒（视模型与地图额度而定）。</p>

      <div class="step-actions">
        <el-button size="large" @click="step = 0">返回修改需求</el-button>
        <el-button type="primary" size="large" @click="startGenerate">确认，开始生成</el-button>
      </div>
    </div>

    <!-- ==================== Step 3 · 生成中 ==================== -->
    <div v-else-if="step === 2" class="step-panel">
      <div class="stage-list">
        <div v-for="s in STAGES" :key="s.key" class="stage-item" :class="'state-' + (stageState[s.key] || 'idle')">
          <span class="stage-dot"></span>
          <span class="stage-label">{{ s.label }}</span>
          <span v-if="stageState[s.key] === 'RUNNING'" class="stage-text">进行中…</span>
          <span v-else-if="stageState[s.key] === 'DONE'" class="stage-text done">已完成</span>
          <span v-else-if="stageState[s.key] === 'FALLBACK'" class="stage-text fallback">已降级</span>
          <span v-else class="stage-text">等待</span>
        </div>
      </div>

      <p v-if="fallbackNotice" class="fallback-notice">{{ fallbackNotice }}</p>

      <p class="generating-note">
        正在生成，可以先等一下 —— 行程骨架一出来就会立刻显示（不用等攻略文案写完）。
      </p>

      <div class="step-actions">
        <el-button size="large" @click="cancelGenerate">取消生成</el-button>
      </div>
    </div>

    <!-- ==================== Step 4 · 结果与编辑 ==================== -->
    <div v-else class="step-panel">
      <TripResult
        :draft="draft"
        :intent="intent"
        :meta="meta"
        :trip-id="tripId"
        :copy-text="copyText"
        :copy-streaming="copyStreaming"
        :shortage="shortage"
        :shortage-hint="shortageHint"
        :validation="violations"
        :profile-used="profileUsedFlag"
        :profile-summary="profileSummary"
        :saving-order="savingOrder"
        :regenerating="regenerating"
        @replan="handleReplan"
        @regenerate-copy="handleRegenerateCopy"
        @publish="goPublish"
        @save-order="handleSaveOrder"
      />

      <div class="step-actions">
        <el-button size="large" @click="resetAll">再规划一次</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import TripResult from '@/components/TripResult.vue'
import { getTravelProfile } from '@/api/profile'
import { parseIntent, planStream, replan, regenerateCopy, updateItemOrder } from '@/api/trip'
import { parseEventData } from '@/utils/sseParser'

const router = useRouter()

// 后端六阶段（与 AiStageRecord.STAGE_* 一一对应）。
// 手册列的六项漏了 COMPOSE（编排行程），这里按实际阶段补齐 —— 少一个阶段会让进度条卡住不动
const STAGES = [
  { key: 'PARSE', label: '理解需求' },
  { key: 'CANDIDATE', label: '检索景点' },
  { key: 'PREORDER', label: '排布路线' },
  { key: 'COMPOSE', label: '编排行程' },
  { key: 'VALIDATE', label: '校验约束' },
  { key: 'ROUTE', label: '查证距离' }
]

const step = ref(0)
const rawInput = ref('')
const tabooInput = ref('')
const tempTaboos = ref([])

// ---------- 画像 ----------
const profile = ref(null)
const profileLoaded = ref(false)
const excludedPrefs = ref([])

const profileHasContent = computed(() => {
  const p = profile.value
  if (!p) return false
  return !!(p.cuisines || p.flavors || p.taboos || p.travelStyles ||
    p.pace != null || p.budgetLevel != null || p.companions ||
    p.walkLimitKm != null || p.hotelPref || p.notes)
})

const prefChips = computed(() => {
  const p = profile.value
  if (!p) return []
  const list = []
  if (p.cuisines) list.push({ key: 'cuisines', label: `菜系：${p.cuisines}` })
  if (p.flavors) list.push({ key: 'flavors', label: `口味：${p.flavors}` })
  if (p.taboos) list.push({ key: 'taboos', label: `忌口：${p.taboos}` })
  if (p.travelStyles) list.push({ key: 'travelStyles', label: `风格：${p.travelStyles}` })
  if (p.pace != null) list.push({ key: 'pace', label: `节奏：${paceText(p.pace)}` })
  if (p.budgetLevel != null) list.push({ key: 'budgetLevel', label: `预算：${budgetText(p.budgetLevel)}` })
  if (p.companions) list.push({ key: 'companions', label: `同行：${p.companions}` })
  if (p.walkLimitKm != null) list.push({ key: 'walkLimitKm', label: `步行上限：${p.walkLimitKm} 公里` })
  if (p.hotelPref) list.push({ key: 'hotelPref', label: `住宿：${p.hotelPref}` })
  if (p.notes) list.push({ key: 'notes', label: `备注：${p.notes}` })
  return list
})

const allPrefsOn = computed(() => excludedPrefs.value.length === 0)

function paceText(v) {
  return v === 1 ? '慢' : v === 2 ? '适中' : v === 3 ? '紧凑' : String(v)
}
function budgetText(v) {
  return v === 1 ? '经济' : v === 2 ? '舒适' : v === 3 ? '品质' : String(v)
}

function togglePref(key) {
  const i = excludedPrefs.value.indexOf(key)
  if (i >= 0) excludedPrefs.value.splice(i, 1)
  else excludedPrefs.value.push(key)
}

function toggleAllPrefs() {
  excludedPrefs.value = allPrefsOn.value ? prefChips.value.map(c => c.key) : []
}

function addTempTaboo() {
  const raw = tabooInput.value.trim()
  if (!raw) return
  if (tempTaboos.value.includes(raw)) {
    ElMessage.warning(`「${raw}」已经添加过了`)
    tabooInput.value = ''
    return
  }
  tempTaboos.value.push(raw)
  tabooInput.value = ''
}

// ---------- 意图 ----------
const parsing = ref(false)
const intent = ref(null)
const needConfirm = computed(() => new Set((intent.value && intent.value.needConfirm) || []))

const form = reactive({
  destination: '',
  startDate: '',
  days: null,
  budgetTotal: null,
  budgetMode: 'TOTAL',
  transport: '',
  companion: '',
  pace: null
})

// ---------- 生成 ----------
const generating = ref(false)
const stageState = ref({})
const fallbackNotice = ref('')
const draft = ref(null)
const meta = ref(null)
const tripId = ref(null)
const copyText = ref('')
const copyStreaming = ref(false)
const shortage = ref(false)
const shortageHint = ref('')
const violations = ref([])
let abortController = null

// ---------- 结果区动作 ----------
const savingOrder = ref(false)
const regenerating = ref(false)

const profileUsedFlag = computed(() => {
  if (excludedPrefs.value.length && excludedPrefs.value.length === prefChips.value.length) return 0
  if (meta.value && meta.value.profileUsed != null) return meta.value.profileUsed
  return 0
})

const profileSummary = computed(() => {
  const on = prefChips.value.filter(c => !excludedPrefs.value.includes(c.key))
  return on.map(c => c.label).join(' / ')
})

onMounted(loadProfile)

onBeforeUnmount(() => {
  if (abortController) abortController.abort()
})

async function loadProfile() {
  try {
    const res = await getTravelProfile()
    profile.value = res.data || null
  } catch (e) {
    profile.value = null
  } finally {
    profileLoaded.value = true
  }
}

/** 组装请求体：只有全部取消时才把 useProfile 关掉（见 chips 区的说明） */
function buildPayload(useForm) {
  const allOff = prefChips.value.length > 0 && excludedPrefs.value.length === prefChips.value.length
  const useProfile = !allOff && !(profile.value && profile.value.allowAiUse === 0)

  const overrides = {}
  if (tempTaboos.value.length) overrides.taboos = [...tempTaboos.value]
  if (useForm && form.pace != null) overrides.pace = form.pace

  const body = {
    rawInput: rawInput.value.trim(),
    useProfile,
    overrides: Object.keys(overrides).length ? overrides : null
  }
  if (useForm) {
    // 用户确认过的参数以自然语言补进输入，让意图解析拿到确定值
    // （后端没有「带参数直接生成」的入口，这里用最直接的方式把确认结果传下去）
    body.rawInput = composeConfirmedInput()
  }
  return body
}

function composeConfirmedInput() {
  const parts = [rawInput.value.trim()]
  const extra = []
  if (form.destination) extra.push(`目的地是${form.destination}`)
  if (form.days) extra.push(`一共 ${form.days} 天`)
  if (form.startDate) extra.push(`${form.startDate} 出发`)
  if (form.budgetTotal != null) {
    extra.push(`预算 ${form.budgetTotal} 元（${form.budgetMode === 'PER_PERSON' ? '人均' : '总计'}）`)
  }
  if (form.transport) {
    const t = { PUBLIC: '公共交通', DRIVE: '自驾', WALK: '步行', MIX: '混合交通' }[form.transport]
    extra.push(`交通方式以${t}为主`)
  }
  if (form.companion) extra.push(`同行人是${form.companion}`)
  if (form.pace != null) extra.push(`节奏要${paceText(form.pace)}一点`)
  return extra.length ? `${parts.join('；')}。${extra.join('，')}。` : parts.join('；')
}

async function startParse() {
  parsing.value = true
  try {
    const res = await parseIntent(buildPayload(false))
    const d = res.data || {}
    intent.value = d
    // 回填可编辑表单
    form.destination = d.destination || ''
    form.startDate = d.startDate || ''
    form.days = d.days != null ? d.days : null
    form.budgetTotal = d.budgetTotal != null ? d.budgetTotal : null
    form.budgetMode = d.budgetMode || 'TOTAL'
    form.transport = d.transport || ''
    form.companion = d.companion || ''
    form.pace = d.pace != null ? d.pace : null
    step.value = 1
  } catch (e) {
    // 拦截器已提示
  } finally {
    parsing.value = false
  }
}

async function startGenerate() {
  step.value = 2
  generating.value = true
  stageState.value = {}
  fallbackNotice.value = ''
  draft.value = null
  meta.value = null
  tripId.value = null
  copyText.value = ''
  copyStreaming.value = false
  shortage.value = false
  shortageHint.value = ''
  violations.value = []

  abortController = new AbortController()

  try {
    await planStream(buildPayload(true), {
      signal: abortController.signal,
      onEvent: handleEvent
    })
  } catch (e) {
    if (e && e.name === 'AbortError') {
      ElMessage.info('已取消生成')
      step.value = 1
    } else if (e && e.status === 401) {
      ElMessage.error('登录已过期，请重新登录')
      router.push('/login')
    } else {
      ElMessage.error((e && e.message) || '生成失败')
      step.value = 1
    }
  } finally {
    generating.value = false
    copyStreaming.value = false
    abortController = null
  }
}

function handleEvent(evt) {
  const data = parseEventData(evt)

  if (evt.event === 'stage') {
    if (data && data.stage) {
      stageState.value = { ...stageState.value, [data.stage]: data.status }
      if (data.status === 'FALLBACK') {
        fallbackNotice.value = data.message || '地图不可用，已切换估算模式'
      }
    }
    return
  }

  if (evt.event === 'itinerary') {
    // 骨架到手就立刻渲染 —— 不等文案（这就是降级出口的意义）
    draft.value = data
    step.value = 3
    copyStreaming.value = true
    return
  }

  if (evt.event === 'delta') {
    copyText.value += (data && data.text) || ''
    return
  }

  if (evt.event === 'done') {
    meta.value = data || null
    if (data && data.tripId != null) tripId.value = data.tripId
    copyStreaming.value = false
    return
  }

  if (evt.event === 'error') {
    copyStreaming.value = false
    const code = data && data.code
    const msg = (data && data.message) || '生成过程中出错'
    if (code === 'CLIENT_DISCONNECTED') return
    ElMessage.warning(msg)
    // 行程已经推过来的话，留在结果页（手册要求：文案失败不清空行程）
    if (!draft.value) step.value = 1
  }
}

function cancelGenerate() {
  if (abortController) {
    abortController.abort()
    abortController = null
  }
  generating.value = false
  copyStreaming.value = false
  ElMessage.info('已取消生成')
  step.value = 1
}

async function handleReplan(dayIndex) {
  if (!tripId.value) {
    ElMessage.warning('这条行程还没落库，暂时无法重排')
    return
  }
  try {
    const res = await replan(tripId.value, `第${dayIndex}天太赶，请重新排布这一天`)
    const o = res.data || {}
    if (o.trip) draft.value = o.trip
    if (o.meta) meta.value = o.meta
    if (o.shortage != null) shortage.value = o.shortage
    if (o.shortageHint) shortageHint.value = o.shortageHint
    violations.value = o.validation || []
    ElMessage.success(`第 ${dayIndex} 天已重新排布`)
  } catch (e) {
    // 拦截器已提示
  }
}

async function handleRegenerateCopy() {
  if (!tripId.value) {
    ElMessage.warning('这条行程还没落库，暂时无法重新生成文案')
    return
  }
  regenerating.value = true
  copyText.value = ''
  copyStreaming.value = true
  try {
    await regenerateCopy(tripId.value, null, { onEvent: handleEvent })
  } catch (e) {
    ElMessage.error((e && e.message) || '文案重新生成失败')
  } finally {
    regenerating.value = false
    copyStreaming.value = false
  }
}

async function handleSaveOrder(payload) {
  savingOrder.value = true
  try {
    for (const day of payload) {
      if (day.itemIds.length) {
        await updateItemOrder(tripId.value, day.dayIndex, day.itemIds)
      }
    }
    ElMessage.success('顺序已保存')
  } catch (e) {
    // 拦截器已提示
  } finally {
    savingOrder.value = false
  }
}

function goPublish() {
  ElMessage.info('发布链路会在 P5-C 接入（需要把行程摘要与文案预填到发布页）')
}

function resetAll() {
  step.value = 0
  draft.value = null
  meta.value = null
  tripId.value = null
  copyText.value = ''
  intent.value = null
  violations.value = []
}
</script>

<style scoped lang="scss">
.plan-page {
  padding-bottom: 40px;
}

.page-title {
  margin: 0 0 20px;
  font-size: 22px;
  font-weight: 700;
  color: #303133;
}

.plan-steps {
  margin-bottom: 28px;
}

.step-panel {
  background: #fff;
  border-radius: 10px;
  padding: 24px;
}

.block-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.block-title {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.block-sub {
  font-size: 12px;
  color: #909399;
}

.block-hint {
  font-size: 13px;
  color: #909399;
  line-height: 1.7;
  margin: 0;

  &.muted {
    color: #c0c4cc;
  }

  &.warn {
    margin-top: 8px;
    color: #b88230;
  }
}

.link {
  color: #409eff;
  text-decoration: none;
}

.pref-block {
  margin-top: 18px;
  padding-top: 18px;
  border-top: 1px solid #f5f5f5;
}

.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.tag-adder {
  display: flex;
  gap: 8px;
  max-width: 320px;
}

.step-actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid #f5f5f5;
}

.confirm-tip {
  margin-bottom: 20px;
}

.confirm-form {
  max-width: 640px;

  :deep(.el-form-item__label) {
    display: flex;
    align-items: center;
    gap: 6px;
    font-weight: 500;
    color: #303133;
  }
}

.inline-row {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.field-hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: #909399;
}

.confirm-note {
  margin: 4px 0 0;
  font-size: 13px;
  color: #909399;
}

.stage-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 560px;
}

.stage-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  color: #909399;

  .stage-dot {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    background: #dcdfe6;
    flex: 0 0 auto;
  }

  .stage-text {
    margin-left: auto;
    font-size: 12px;
    color: #c0c4cc;
  }

  &.state-RUNNING {
    color: #409eff;

    .stage-dot {
      background: #409eff;
    }

    .stage-text {
      color: #409eff;
    }
  }

  &.state-DONE {
    color: #529b2e;

    .stage-dot {
      background: #529b2e;
    }

    .stage-text.done {
      color: #529b2e;
    }
  }

  &.state-FALLBACK {
    color: #b88230;

    .stage-dot {
      background: #e6a23c;
    }

    .stage-text.fallback {
      color: #b88230;
    }
  }
}

.fallback-notice {
  margin: 16px 0 0;
  padding: 10px 14px;
  background: #fdf6ec;
  color: #b88230;
  border-radius: 6px;
  font-size: 13px;
}

.generating-note {
  margin: 16px 0 0;
  font-size: 13px;
  color: #909399;
}

@media (max-width: 768px) {
  .step-panel {
    padding: 16px;
  }
  .tag-adder {
    max-width: 100%;
  }
  .step-actions {
    flex-direction: column;

    .el-button {
      width: 100%;
      margin-left: 0;
    }
  }
  .confirm-form {
    max-width: 100%;
  }
}
</style>
