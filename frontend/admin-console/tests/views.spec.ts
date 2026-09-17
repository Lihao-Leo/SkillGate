import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'

const { gatewayApi } = vi.hoisted(() => ({
  gatewayApi: {
    listSkills: vi.fn(),
    uploadSkill: vi.fn(),
    skillDetail: vi.fn(),
    setDefaultVersion: vi.fn(),
    updateSkillConfig: vi.fn(),
    adminListKeys: vi.fn(),
    adminResetSecret: vi.fn(),
    adminUpdateKeyStatus: vi.fn(),
    adminRecharge: vi.fn(),
    adminIssueKey: vi.fn(),
    adminListModelProviders: vi.fn(),
  },
}))
vi.mock('../src/api/gateway', () => ({ gatewayApi }))

import SkillsView from '../src/views/SkillsView.vue'
import AppKeysView from '../src/views/AppKeysView.vue'
import ConnectView from '../src/views/ConnectView.vue'
import { useCredentialsStore } from '../src/stores/credentials'
import { createRouter, createMemoryHistory } from 'vue-router'

function mountView(view: typeof SkillsView) {
  return mount(view, { global: { plugins: [ElementPlus] } })
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  setActivePinia(createPinia())
  useCredentialsStore().save({ appKey: 'sk-1', secret: 's', adminToken: 'adm' })
})

describe('SkillsView', () => {
  it('渲染列表并透传分页参数', async () => {
    gatewayApi.listSkills.mockResolvedValue({
      total: 1, pageNo: 1, pageSize: 20,
      items: [{
        skillCode: 'video-gen', name: '视频生成', description: 'd', visibility: 'PRIVATE',
        defaultVersion: '1.0.0', status: 1, pricingConfig: null,
      }],
    })
    const wrapper = mountView(SkillsView)
    await flushPromises()
    expect(gatewayApi.listSkills).toHaveBeenCalledWith(1, 20)
    expect(wrapper.find('[data-testid="skills-table"]').text()).toContain('video-gen')
    expect(wrapper.find('[data-testid="skills-table"]').text()).toContain('视频生成')
  })

  it('上传表单：填写字段与 zip 后调 uploadSkill 并刷新列表', async () => {
    gatewayApi.listSkills.mockResolvedValue({ total: 0, pageNo: 1, pageSize: 20, items: [] })
    gatewayApi.uploadSkill.mockResolvedValue(
      { version: '1.0.0', packageSha256: 'abc', packageSize: 10, status: 0, changelog: '', uploadedAt: '2026-09-15' })
    const wrapper = mountView(SkillsView)
    await flushPromises()

    await wrapper.find('[data-testid="open-upload"]').trigger('click')
    await flushPromises()

    await wrapper.find('[data-testid="upload-skillcode"]').setValue('video-gen')
    await wrapper.find('[data-testid="upload-version"]').setValue('1.0.0')
    await wrapper.find('[data-testid="upload-name"]').setValue('视频生成')
    await wrapper.find('[data-testid="upload-abilities"]').setValue('llm.chat, video.generate')

    const fileInput = wrapper.find('[data-testid="upload-file"]')
    Object.defineProperty(fileInput.element, 'files', {
      value: [new File([new Uint8Array([1, 2, 3])], 'pkg.zip', { type: 'application/zip' })],
    })
    await fileInput.trigger('change')

    await wrapper.find('[data-testid="upload-submit"]').trigger('click')
    await flushPromises()

    expect(gatewayApi.uploadSkill).toHaveBeenCalledTimes(1)
    const [fields, file] = gatewayApi.uploadSkill.mock.calls[0]
    expect(fields.skillCode).toBe('video-gen')
    expect(fields.version).toBe('1.0.0')
    expect(fields.visibility).toBe('PRIVATE')
    // 逗号分隔自动转 JSON 数组（服务端要求合法 JSON）
    expect(fields.requiredAbilities).toBe(JSON.stringify(['llm.chat', 'video.generate']))
    expect((file as File).name).toBe('pkg.zip')
    // 上传成功后刷新列表
    expect(gatewayApi.listSkills).toHaveBeenCalledTimes(2)
  })

  it('详情抽屉：版本列表 + 设为默认回滚', async () => {
    gatewayApi.listSkills.mockResolvedValue({
      total: 1, pageNo: 1, pageSize: 20,
      items: [{
        skillCode: 'video-gen', name: '视频生成', description: 'd', visibility: 'PRIVATE',
        defaultVersion: '1.0.0', status: 1, pricingConfig: '{"model":"FREE"}',
      }],
    })
    gatewayApi.skillDetail.mockResolvedValue({
      skill: {
        skillCode: 'video-gen', name: '视频生成', description: 'd', visibility: 'PRIVATE',
        defaultVersion: '1.0.0', status: 1, pricingConfig: '{"model":"FREE"}',
      },
      versions: [
        { version: '1.0.0', packageSha256: 'a', packageSize: 1, status: 1, changelog: 'init', uploadedAt: '2026-09-15' },
        { version: '1.1.0', packageSha256: 'b', packageSize: 2, status: 1, changelog: 'fix', uploadedAt: '2026-09-15' },
      ],
    })
    gatewayApi.setDefaultVersion.mockResolvedValue({})
    const wrapper = mount(SkillsView, {
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await flushPromises()

    await wrapper.find('[data-testid="detail-video-gen"]').trigger('click')
    await flushPromises()
    // 抽屉内容经 transition/teleport 渲染，等待一拍后从 document 查询
    await new Promise(resolve => setTimeout(resolve, 20))
    expect(gatewayApi.skillDetail).toHaveBeenCalledWith('video-gen')
    expect(document.body.innerHTML).toContain('1.1.0')

    const makeDefault = document.body
      .querySelector('[data-testid="make-default-1.1.0"]') as HTMLElement
    makeDefault.click()
    await flushPromises()
    expect(gatewayApi.setDefaultVersion).toHaveBeenCalledWith('video-gen', '1.1.0')
    wrapper.unmount()
  })
})

describe('ConnectView 冷启动自助签发', () => {
  it('仅填 AdminToken 即可签发新 Key 并自动填入表单', async () => {
    gatewayApi.adminIssueKey.mockResolvedValue(
      { appKeyId: 'sk-issued-1', appSecret: 'SEC-ONCE' })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/connect', name: 'connect', component: { template: '<div/>' } },
        { path: '/skills', name: 'skills', component: { template: '<div/>' } },
      ],
    })
    const wrapper = mount(ConnectView, { global: { plugins: [ElementPlus, router] } })

    await wrapper.find('[data-testid="admintoken-input"]').setValue('adm-token')
    await wrapper.find('[data-testid="issue-key-btn"]').trigger('click')
    await flushPromises()

    expect(gatewayApi.adminIssueKey).toHaveBeenCalledWith('admin-console')
    const appKeyInput = wrapper.find('[data-testid="appkey-input"]')
    expect((appKeyInput.element as HTMLInputElement).value).toBe('sk-issued-1')

    // 保存后进入业务面
    await wrapper.find('[data-testid="save-btn"]').trigger('click')
    await flushPromises()
    const store = useCredentialsStore()
    expect(store.appKey).toBe('sk-issued-1')
    expect(store.secret).toBe('SEC-ONCE')
  })
})

describe('AppKeysView', () => {
  it('渲染 key 列表；重置 Secret 一次性展示', async () => {
    gatewayApi.adminListKeys.mockResolvedValue({
      total: 1, pageNo: 1, pageSize: 20,
      items: [{ appKeyId: 'sk-key-1', tenantId: 't1', status: 1, createdAt: '2026-09-15' }],
    })
    gatewayApi.adminResetSecret.mockResolvedValue(
      { appKeyId: 'sk-key-1', appSecret: 'NEW-SECRET-ONCE' })
    const wrapper = mountView(AppKeysView)
    await flushPromises()
    expect(wrapper.find('[data-testid="keys-table"]').text()).toContain('sk-key-1')

    await wrapper.find('[data-testid="reset-sk-key-1"]').trigger('click')
    await flushPromises()
    expect(gatewayApi.adminResetSecret).toHaveBeenCalledWith('sk-key-1')
    expect(wrapper.find('[data-testid="once-appsecret"]').text()).toBe('NEW-SECRET-ONCE')
  })

  it('重置当前签名所用 key：本地凭证同步轮换', async () => {
    gatewayApi.adminListKeys.mockResolvedValue({
      total: 1, pageNo: 1, pageSize: 20,
      items: [{ appKeyId: 'sk-1', tenantId: 't1', status: 1, createdAt: '2026-09-15' }],
    })
    gatewayApi.adminResetSecret.mockResolvedValue({ appKeyId: 'sk-1', appSecret: 'ROTATED-SEC' })
    const wrapper = mountView(AppKeysView)
    await flushPromises()

    await wrapper.find('[data-testid="reset-sk-1"]').trigger('click')
    await flushPromises()
    // sk-1 即当前业务面凭证：Secret 应被一次性新值无缝替换
    expect(useCredentialsStore().secret).toBe('ROTATED-SEC')
    expect(useCredentialsStore().appKey).toBe('sk-1')
    expect(wrapper.find('[data-testid="once-appsecret"]').text()).toBe('ROTATED-SEC')
  })

  it('禁用当前签名所用 key：清空凭证并跳转连接配置', async () => {
    gatewayApi.adminListKeys.mockResolvedValue({
      total: 1, pageNo: 1, pageSize: 20,
      items: [{ appKeyId: 'sk-1', tenantId: 't1', status: 1, createdAt: '2026-09-15' }],
    })
    gatewayApi.adminUpdateKeyStatus.mockResolvedValue({})
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/connect', name: 'connect', component: { template: '<div/>' } },
        { path: '/app-keys', name: 'app-keys', component: { template: '<div/>' } },
      ],
    })
    await router.push('/app-keys')
    const wrapper = mount(AppKeysView, { global: { plugins: [ElementPlus, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="toggle-sk-1"]').trigger('click')
    await flushPromises()
    expect(gatewayApi.adminUpdateKeyStatus).toHaveBeenCalledWith('sk-1', 0)
    expect(useCredentialsStore().appKeyReady).toBe(false)
    expect(router.currentRoute.value.name).toBe('connect')
  })
})
