<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { gatewayApi, type SkillDetail, type SkillPage } from '../api/gateway'

/** JSON 宽松解析（失败返回 null，用于回填默认值） */
const JsonCodecSafe = {
  parse(text: string): any | null {
    if (!text.trim()) return null
    try {
      return JSON.parse(text)
    } catch {
      return null
    }
  },
}

const page = ref<SkillPage | null>(null)
const query = reactive({ pageNo: 1, pageSize: 20 })
const loading = ref(false)
const detail = ref<SkillDetail | null>(null)
const detailVisible = ref(false)
const configForm = reactive({
  timeoutSeconds: 600,
  pricingMode: 'PER_EXECUTION' as 'PER_EXECUTION' | 'FREE',
  pricingPoints: 1,
})

// 上传表单（§10.1：zip + required_abilities + output_config + pricing_config + invocation_spec）
const uploadVisible = ref(false)
const uploadSubmitting = ref(false)
const uploadForm = reactive({
  skillCode: '', version: '', name: '', description: '', visibility: 'PRIVATE',
  requiredAbilities: '', changelog: '', publish: false,
  // 执行与计费（结构化生成 outputConfig / pricingConfig JSON）
  timeoutSeconds: 600,
  pricingMode: 'PER_EXECUTION' as 'PER_EXECUTION' | 'FREE',
  pricingPoints: 1,
  invocationSpec: '',
})
const uploadFile = ref<File | null>(null)
const modelAliases = ref<string[]>([])
const abilitySelectionText = ref('')

// 试运行（§10.1 后台直接执行）：execute → 2s 轮询 → 终态展示产物
const runDialog = reactive({
  visible: false, skillCode: '', version: '', instructions: '', context: '',
  submitting: false, taskId: '', status: '', progress: null as number | null,
  artifacts: [] as { artifactId: string; type: string; url: string }[], polling: false,
  materials: [] as { type: string; url: string; filename: string }[],
  uploading: false,
  countable: false, defaultCount: 1, maxCount: 1, count: 1,
})
let runTimer: ReturnType<typeof setInterval> | null = null

async function load() {
  loading.value = true
  try {
    page.value = await gatewayApi.listSkills(query.pageNo, query.pageSize)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function openDetail(skillCode: string) {
  try {
    detail.value = await gatewayApi.skillDetail(skillCode)
    const output = JsonCodecSafe.parse(detail.value.skill.outputConfig ?? '') ?? {}
    configForm.timeoutSeconds = Number(output.timeoutSeconds ?? 600)
    const pricing = JsonCodecSafe.parse(detail.value.skill.pricingConfig ?? '') ?? {}
    // 历史按量(METERED)配置在表单中按按次展示，保存后即转为按次扣点
    configForm.pricingMode = (pricing.mode ?? pricing.model) === 'FREE' ? 'FREE' : 'PER_EXECUTION'
    configForm.pricingPoints = Number(pricing.points ?? pricing.capPoints ?? 1)
    detailVisible.value = true
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

/** 默认版本切换 = 秒级回滚（§10.1） */
async function makeDefault(version: string) {
  if (!detail.value) return
  try {
    await gatewayApi.setDefaultVersion(detail.value.skill.skillCode, version)
    ElMessage.success(`默认版本已切至 ${version}`)
    detail.value = await gatewayApi.skillDetail(detail.value.skill.skillCode)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function saveConfig() {
  if (!detail.value) return
  const outputConfig = JSON.stringify({ timeoutSeconds: configForm.timeoutSeconds })
  const pricingConfig = configForm.pricingMode === 'FREE'
    ? JSON.stringify({ mode: 'FREE' })
    : JSON.stringify({ mode: 'PER_EXECUTION', points: configForm.pricingPoints })
  try {
    await gatewayApi.updateSkillConfig(detail.value.skill.skillCode, outputConfig, pricingConfig)
    ElMessage.success('运营配置已更新（即时生效）')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function openUpload() {
  Object.assign(uploadForm, {
    skillCode: '', version: '', name: '', description: '', visibility: 'PRIVATE',
    requiredAbilities: '', changelog: '', publish: false,
    timeoutSeconds: 600, pricingMode: 'PER_EXECUTION', pricingPoints: 1,
    invocationSpec: '',
  })
  uploadFile.value = null
  abilitySelectionText.value = ''
  uploadVisible.value = true
  Promise.resolve(gatewayApi.adminListModelProviders?.())
    .then(models => { modelAliases.value = models.map(m => m.alias) })
    .catch(() => { modelAliases.value = [] })
}

function onFileChange(event: Event) {
  const files = (event.target as HTMLInputElement).files
  uploadFile.value = files && files.length > 0 ? files[0] : null
}

/** 执行设置 → outputConfig JSON（产出内容由 Skill 包自身决定，仅登记超时契约） */
function buildOutputConfig(): string {
  return JSON.stringify({ timeoutSeconds: uploadForm.timeoutSeconds })
}

/** 计费设置 → pricingConfig JSON（§4.8：FREE / PER_EXECUTION(points)） */
function buildPricingConfig(): string {
  if (uploadForm.pricingMode === 'FREE') return JSON.stringify({ mode: 'FREE' })
  return JSON.stringify({ mode: 'PER_EXECUTION', points: uploadForm.pricingPoints })
}

/** JSON 字段宽松校验：填了就必须是合法 JSON */
function tryParseJson(text: string, label: string): string | undefined {
  if (!text.trim()) return undefined
  try {
    JSON.parse(text)
    return text
  } catch {
    ElMessage.warning(`${label} 不是合法 JSON`)
    return null as unknown as undefined
  }
}

/** 计费配置校验（§4.8）：PER_EXECUTION 需 points>0 */
function validatePricingConfig(text: string): string | null {
  if (!text.trim()) return null
  try {
    const pricing = JSON.parse(text)
    const mode = pricing.mode ?? pricing.model
    if (mode === 'PER_EXECUTION' && !(Number(pricing.points) > 0)) {
      return '按次扣点需要大于 0 的点数'
    }
    return null
  } catch {
    return '计费配置不是合法 JSON'
  }
}

/** requiredAbilities：表单用逗号分隔书写，提交时转 JSON 数组（服务端要求合法 JSON） */
function abilitiesToJson(text: string): string | undefined {
  const items = text.split(/[,，\n]/).map(item => item.trim()).filter(Boolean)
  return items.length === 0 ? undefined : JSON.stringify(items)
}

async function submitUpload() {
  if (!uploadForm.skillCode.trim() || !uploadForm.version.trim()) {
    ElMessage.warning('skillCode 与 version 必填')
    return
  }
  if (!uploadFile.value) {
    ElMessage.warning('请选择 Skill 包（zip）')
    return
  }
  if (!uploadFile.value.name.toLowerCase().endsWith('.zip')) {
    ElMessage.warning('仅支持 zip 包')
    return
  }
  if (tryParseJson(uploadForm.invocationSpec, '调用说明') === null) return
  const pricingProblem = validatePricingConfig(buildPricingConfig())
  if (pricingProblem) {
    ElMessage.warning(pricingProblem)
    return
  }
  uploadSubmitting.value = true
  try {
    const version = await gatewayApi.uploadSkill({
      skillCode: uploadForm.skillCode.trim(),
      version: uploadForm.version.trim(),
      name: uploadForm.name.trim(),
      description: uploadForm.description.trim(),
      visibility: uploadForm.visibility,
      requiredAbilities: abilitiesToJson(abilitySelectionText.value) ?? '',
      outputConfig: buildOutputConfig(),
      pricingConfig: buildPricingConfig(),
      invocationSpec: uploadForm.invocationSpec.trim(),
      changelog: uploadForm.changelog.trim(),
      publish: uploadForm.publish ? 'true' : '',
    }, uploadFile.value)
    ElMessage.success(`已上传 ${uploadForm.skillCode}@${version.version}（${uploadForm.publish ? '已提交发布' : '待审核'}）`)
    uploadVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    uploadSubmitting.value = false
  }
}

const visibilityText: Record<string, string> = { PUBLIC: '公开', PRIVATE: '私有' }

/** pricingConfig JSON → 人类可读（如「3 点/次」「免费」「按量」） */
function pricingText(text: string | null | undefined): string {
  const pricing = JsonCodecSafe.parse(text ?? '') ?? {}
  const mode = pricing.mode ?? pricing.model
  if (mode === 'FREE') return '免费'
  if (mode === 'METERED') return `按量（顶 ${pricing.capPoints ?? '-'} 点）`
  return `${pricing.points ?? 1} 点/次`
}

async function openRun(skillCode: string, version?: string) {
  Object.assign(runDialog, {
    visible: true, skillCode, version: version ?? '', instructions: '', context: '',
    submitting: false, taskId: '', status: '', progress: null, artifacts: [], polling: false,
    materials: [], uploading: false,
    countable: false, defaultCount: 1, maxCount: 1, count: 1,
  })
  // 数量控制（§4.4）：可数技能才显示/传 count，规则来自 output_config
  try {
    const detail = await gatewayApi.skillDetail(skillCode)
    if (detail.skill.outputConfig) {
      const output = JSON.parse(detail.skill.outputConfig)
      runDialog.countable = Boolean(output.countable)
      runDialog.defaultCount = Number(output.defaultCount ?? 1)
      runDialog.maxCount = Number(output.maxCount ?? output.defaultCount ?? 1)
      runDialog.count = runDialog.defaultCount
    }
    if (version) {
      runDialog.version = version
    } else if (detail.skill.defaultVersion) {
      runDialog.version = detail.skill.defaultVersion
    }
  } catch { /* 数量信息缺失时按不可数处理 */ }
}

/** 素材 multipart 直传 → confirm 状态 → oss:// 引用（§5.1/§4.3） */
async function onRunMaterial(event: Event) {
  const files = (event.target as HTMLInputElement).files
  if (!files || files.length === 0) return
  runDialog.uploading = true
  try {
    const material = await gatewayApi.uploadMaterial(files[0], 'document')
    runDialog.materials.push({
      type: 'document', url: material.materialUrl, filename: material.filename,
    })
    ElMessage.success(`素材已上传确认：${material.filename}`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    runDialog.uploading = false
    ;(event.target as HTMLInputElement).value = ''
  }
}

async function submitRun() {
  if (!runDialog.instructions.trim() && !runDialog.context.trim()) {
    ElMessage.warning('请填写任务指令或上下文 JSON')
    return
  }
  let context: unknown
  if (runDialog.context.trim()) {
    try {
      context = JSON.parse(runDialog.context)
    } catch {
      ElMessage.warning('上下文不是合法 JSON')
      return
    }
  }
  runDialog.submitting = true
  try {
    const result = await gatewayApi.execute({
      skillCode: runDialog.skillCode,
      version: runDialog.version.trim() || undefined,
      count: runDialog.countable ? runDialog.count : undefined,
      clientRequestId: `console-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      instructions: runDialog.instructions.trim() || undefined,
      context,
      materials: runDialog.materials.map(m => ({ type: m.type, url: m.url })),
    })
    runDialog.taskId = result.taskId
    runDialog.status = result.status ?? 'PENDING'
    runDialog.polling = true
    runTimer = setInterval(pollRun, 2000)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    runDialog.submitting = false
  }
}

async function pollRun() {
  if (!runDialog.taskId) return
  try {
    const view = await gatewayApi.execution(runDialog.taskId)
    runDialog.status = view.status
    runDialog.progress = view.progress ?? null
    runDialog.artifacts = view.artifacts ?? []
    if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(view.status)) {
      stopRunPolling()
    }
  } catch { /* 轮询失败容忍 */ }
}

function stopRunPolling() {
  if (runTimer) clearInterval(runTimer)
  runTimer = null
  runDialog.polling = false
}

/** 版本审核流转：通过(1)/驳回·废弃(2)；按钮可见性由版本状态驱动 */
async function review(version: string, status: 1 | 2) {
  if (!detail.value) return
  const actionText = status === 1 ? '审核通过并发布' : '驳回/废弃'
  try {
    await gatewayApi.reviewVersion(detail.value.skill.skillCode, version, status)
    ElMessage.success(`${version} ${actionText}成功`)
    detail.value = await gatewayApi.skillDetail(detail.value.skill.skillCode)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

/** 删除 Skill（硬删元数据与版本记录，清理 COS 包对象；历史执行记录保留审计） */
async function confirmDelete(skillCode: string) {
  try {
    await ElMessageBox.confirm(
      `确定删除 Skill「${skillCode}」？将删除全部版本记录并清理对象存储中的包文件，不可恢复。`,
      '删除确认', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch {
    return
  }
  try {
    await gatewayApi.deleteSkill(skillCode)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

/** 市场上/下架 */
async function toggleVisibility(skillCode: string, current: string) {
  const target = current === 'PUBLIC' ? 'PRIVATE' : 'PUBLIC'
  try {
    await gatewayApi.setVisibility(skillCode, target as 'PUBLIC' | 'PRIVATE')
    ElMessage.success(target === 'PUBLIC' ? '已上架市场' : '已下架市场')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
</script>

<template>
  <div class="page-header">
    <div>
      <h2>Skill 管理</h2>
      <p class="sub">Skill 元数据、版本与审核、默认版本（秒级回滚）与运营配置</p>
    </div>
    <div>
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="openUpload" data-testid="open-upload">
        <el-icon style="margin-right: 4px"><UploadFilled /></el-icon>上传 Skill
      </el-button>
    </div>
  </div>

  <el-card>
    <el-table :data="page?.items ?? []" v-loading="loading" data-testid="skills-table">
      <el-table-column label="Skill" min-width="220">
        <template #default="{ row }">
          <div class="skill-cell">
            <span class="avatar">{{ (row.name ?? row.skillCode).slice(0, 1) }}</span>
            <div>
              <div class="skill-name">{{ row.name }}</div>
              <div class="skill-code mono">{{ row.skillCode }}</div>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag :type="row.kind === 'AGENT' ? 'warning' : 'primary'" size="small" effect="light">
            {{ row.kind === 'AGENT' ? '智能体' : '代码' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="可见性" width="90">
        <template #default="{ row }">
          <el-tag :type="row.visibility === 'PUBLIC' ? 'success' : 'info'" size="small" effect="light">
            {{ visibilityText[row.visibility] ?? row.visibility }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="defaultVersion" label="默认版本" width="100" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small" effect="light">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="计费" width="120">
        <template #default="{ row }">{{ pricingText(row.pricingConfig) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row.skillCode)"
                     :data-testid="`detail-${row.skillCode}`">详情</el-button>
          <el-button link type="success" @click="openRun(row.skillCode, row.defaultVersion)"
                     :data-testid="`run-${row.skillCode}`">试运行</el-button>
          <el-button link :type="row.visibility === 'PUBLIC' ? 'danger' : 'warning'" size="small"
                     @click="toggleVisibility(row.skillCode, row.visibility)"
                     :data-testid="`shelf-${row.skillCode}`">
            {{ row.visibility === 'PUBLIC' ? '下架' : '上架' }}
          </el-button>
          <el-button link type="danger" size="small" @click="confirmDelete(row.skillCode)"
                     :data-testid="`delete-${row.skillCode}`">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="query.pageNo" :page-size="query.pageSize"
                   :total="page?.total ?? 0" layout="total, prev, pager, next"
                   @current-change="load" style="margin-top: 14px" />
  </el-card>

  <el-dialog v-model="uploadVisible" title="上传 Skill 包" width="640px" :close-on-click-modal="false">
    <el-form label-width="110px" data-testid="upload-form">
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="skillCode" required>
            <el-input v-model="uploadForm.skillCode" placeholder="如 video-gen"
                      data-testid="upload-skillcode" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="版本" required>
            <el-input v-model="uploadForm.version" placeholder="如 1.0.0（不覆盖同名版本）"
                      data-testid="upload-version" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="名称">
            <el-input v-model="uploadForm.name" data-testid="upload-name" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="可见性">
            <el-select v-model="uploadForm.visibility" data-testid="upload-visibility" style="width: 100%">
              <el-option label="私有（PRIVATE）" value="PRIVATE" />
              <el-option label="公开（PUBLIC）" value="PUBLIC" />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="描述">
        <el-input v-model="uploadForm.description" type="textarea" :rows="2"
                  data-testid="upload-description" />
      </el-form-item>
      <el-form-item label="包文件" required>
        <input type="file" accept=".zip" @change="onFileChange" data-testid="upload-file"
               style="width: 100%" />
        <span class="upload-hint">≤100MB；自动识别类型：含 main.py = 代码技能，含 SKILL.md = 智能体技能（平台内置 runner 执行，包零改造）；sha256 由服务端计算登记</span>
      </el-form-item>
      <el-form-item label="所需能力">
        <el-input v-model="abilitySelectionText" data-testid="upload-abilities"
                  placeholder="可留空；如 llm.chat, video.generate（逗号分隔，自动转 JSON 数组）" />
        <span class="upload-hint">可留空——不影响上传与执行；声明后便于运营识别技能依赖。可用别名见「模型与能力」</span>
      </el-form-item>
      <el-form-item label="执行超时">
        <el-input-number v-model="uploadForm.timeoutSeconds" :min="60" :max="3600" :step="60"
                         data-testid="upload-timeout" />
        <span class="upload-hint" style="margin-left: 8px">秒；一般保持 600 即可，长任务可上传后在详情里调整</span>
      </el-form-item>
      <el-form-item label="计费模式">
        <el-radio-group v-model="uploadForm.pricingMode" data-testid="upload-pricing-mode">
          <el-radio-button value="PER_EXECUTION">按次扣点</el-radio-button>
          <el-radio-button value="FREE">免费</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="uploadForm.pricingMode === 'PER_EXECUTION'" label="每次扣点">
        <el-input-number v-model="uploadForm.pricingPoints" :min="1"
                         data-testid="upload-pricing-points" />
        <span class="upload-hint" style="margin-left: 8px">点（该 Skill 执行一次消耗多少积分）</span>
      </el-form-item>
      <el-form-item label="调用说明">
        <el-input v-model="uploadForm.invocationSpec" type="textarea" :rows="3"
                  placeholder="JSON（materials/instructions/context 契约，可留空）" />
      </el-form-item>
      <el-form-item label="changelog">
        <el-input v-model="uploadForm.changelog" placeholder="本版本变更说明" />
      </el-form-item>
      <el-form-item label="提交发布">
        <el-switch v-model="uploadForm.publish" data-testid="upload-publish" />
        <span class="upload-hint" style="margin-left: 8px">开启则上传后直接提交审核发布</span>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="uploadSubmitting" @click="submitUpload"
                   data-testid="upload-submit">上传</el-button>
      </el-form-item>
    </el-form>
  </el-dialog>

  <el-drawer v-model="detailVisible" size="620px" :title="detail?.skill.name ?? '详情'"
             :with-header="true">
    <template v-if="detail">
      <h4 class="drawer-title">版本列表 <span class="sub">sha256 / changelog / 审核状态</span></h4>
      <el-table :data="detail.versions" size="small" data-testid="versions-table">
        <el-table-column prop="version" label="版本" width="90" />
        <el-table-column prop="packageSha256" label="sha256" show-overflow-tooltip class-name="mono" />
        <el-table-column prop="changelog" label="changelog" show-overflow-tooltip />
        <el-table-column label="存储" width="200">
          <template #default="{ row }">
            <el-tooltip :content="row.packageUrl ?? row.ossKey ?? ''" placement="top">
              <span class="mono store-cell" :data-testid="`store-${row.version}`">
                {{ row.packageUrl ?? row.ossKey ?? '-' }}
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" effect="light"
                    :type="row.status === 1 ? 'success' : row.status === 2 ? 'danger' : 'info'">
              {{ row.status === 1 ? '已发布' : row.status === 2 ? '已废弃' : '待审核' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="190">
          <template #default="{ row }">
            <el-button v-if="row.status === 0" link type="success" size="small"
                       @click="review(row.version, 1)"
                       :data-testid="`approve-${row.version}`">通过</el-button>
            <el-button v-if="row.status === 0" link type="danger" size="small"
                       @click="review(row.version, 2)"
                       :data-testid="`reject-${row.version}`">驳回</el-button>
            <el-button v-if="row.status === 1" link type="danger" size="small"
                       @click="review(row.version, 2)"
                       :data-testid="`deprecate-${row.version}`">废弃</el-button>
            <el-button v-if="row.version !== detail.skill.defaultVersion && row.status === 1"
                       link type="primary" size="small" @click="makeDefault(row.version)"
                       :data-testid="`make-default-${row.version}`">设为默认</el-button>
            <el-tag v-if="row.version === detail.skill.defaultVersion"
                    size="small" type="primary" effect="plain">默认</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <h4 class="drawer-title" style="margin-top: 22px">运营配置 <span class="sub">保存后即时生效</span></h4>
      <el-form label-width="120px">
        <el-form-item label="执行超时（秒）">
          <el-input-number v-model="configForm.timeoutSeconds" :min="60" :max="3600" :step="60" />
        </el-form-item>
        <el-form-item label="计费模式">
          <el-radio-group v-model="configForm.pricingMode" data-testid="config-pricing-mode">
            <el-radio-button value="PER_EXECUTION">按次扣点</el-radio-button>
            <el-radio-button value="FREE">免费分发</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="configForm.pricingMode === 'PER_EXECUTION'" label="每次扣点">
          <el-input-number v-model="configForm.pricingPoints" :min="1"
                           data-testid="config-pricing-points" />
          <span class="upload-hint" style="margin-left: 8px">点（执行一次消耗多少积分）</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="saveConfig" data-testid="save-config">保存配置</el-button>
        </el-form-item>
      </el-form>
    </template>
  </el-drawer>

  <el-dialog v-model="runDialog.visible" :title="`试运行 · ${runDialog.skillCode}`"
             width="640px" :close-on-click-modal="false" @closed="stopRunPolling"
             data-testid="run-dialog">
    <template v-if="!runDialog.taskId">
      <el-form label-width="90px">
        <el-form-item label="版本">
          <el-input v-model="runDialog.version" placeholder="留空 = 默认版本"
                    data-testid="run-version" />
        </el-form-item>
        <el-form-item v-if="runDialog.countable" label="产出数量">
          <el-input-number v-model="runDialog.count" :min="1" :max="runDialog.maxCount"
                           data-testid="run-count" />
          <span class="hint" style="margin-left: 8px">最多 {{ runDialog.maxCount }}</span>
        </el-form-item>
        <el-form-item label="任务指令">
          <el-input v-model="runDialog.instructions" type="textarea" :rows="3"
                    placeholder="本次要做什么（对应该技能 instructions）"
                    data-testid="run-instructions" />
        </el-form-item>
        <el-form-item label="上下文">
          <el-input v-model="runDialog.context" type="textarea" :rows="3"
                    placeholder='JSON（可选），如 {"business":"母婴零售","members":12000}' 
                    data-testid="run-context" />
        </el-form-item>
        <el-form-item label="素材">
          <div style="width: 100%">
            <input type="file" :disabled="runDialog.uploading" @change="onRunMaterial"
                   data-testid="run-material" />
            <div v-if="runDialog.materials.length" class="run-materials" data-testid="run-materials">
              <el-tag v-for="(m, i) in runDialog.materials" :key="i" closable size="small"
                      @close="runDialog.materials.splice(i, 1)">{{ m.filename }}</el-tag>
            </div>
          </div>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="runDialog.submitting" @click="submitRun"
                     data-testid="run-submit">执行</el-button>
        </el-form-item>
      </el-form>
    </template>
    <template v-else>
      <div class="run-status">
        <span>taskId：<span class="mono" data-testid="run-taskid">{{ runDialog.taskId }}</span></span>
        <el-tag :type="runDialog.status === 'SUCCEEDED' ? 'success'
                        : runDialog.status === 'FAILED' ? 'danger' : 'primary'">
          {{ runDialog.status }}<template v-if="runDialog.progress != null"> {{ runDialog.progress }}%</template>
        </el-tag>
      </div>
      <el-alert v-if="['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(runDialog.status)"
                :type="runDialog.status === 'SUCCEEDED' ? 'success' : 'error'" :closable="false"
                :title="runDialog.status === 'SUCCEEDED' ? '执行完成，产物已登记（可在执行监控按 taskId 查询详情）'
                        : '执行失败，详情见执行监控'"
                data-testid="run-terminal" style="margin-top: 12px" />
      <el-alert v-else type="info" :closable="false" title="执行中，每 2 秒自动刷新…" style="margin-top: 12px" />
      <template v-if="runDialog.artifacts.length">
        <h4 style="margin: 14px 0 8px">产物</h4>
        <ul class="run-artifacts" data-testid="run-artifacts">
          <li v-for="artifact in runDialog.artifacts" :key="artifact.artifactId">
            <a :href="artifact.url" target="_blank" rel="noopener">{{ artifact.artifactId }}（{{ artifact.type }}）</a>
          </li>
        </ul>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.upload-hint { color: var(--text-sub); font-size: 12px; display: block; margin-top: 4px; }
.skill-cell { display: flex; align-items: center; gap: 10px; }
.skill-cell .avatar {
  width: 36px; height: 36px; border-radius: 10px; flex: none;
  background: linear-gradient(135deg, #eef1fe, #e5e9ff);
  color: #3b5bf5; font-weight: 700; display: grid; place-items: center;
}
.skill-name { font-weight: 600; }
.skill-code { font-size: 12px; color: var(--text-sub); }
.drawer-title { display: flex; align-items: baseline; gap: 8px; margin: 4px 0 12px; }
.drawer-title .sub { font-size: 12px; font-weight: 400; color: var(--text-sub); }
</style>
<style scoped>
.store-cell {
  font-size: 11px;
  color: var(--text-sub);
  display: inline-block;
  max-width: 190px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
