import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App as AntdApp, ConfigProvider } from 'antd'
import { MemoryRouter } from 'react-router-dom'

const { gatewayApi } = vi.hoisted(() => ({
  gatewayApi: {
    listSkills: vi.fn(),
    uploadSkill: vi.fn(),
    skillDetail: vi.fn(),
    setDefaultVersion: vi.fn(),
    updateSkillConfig: vi.fn(),
    adminListSkills: vi.fn(),
    adminSkillDetail: vi.fn(),
    adminUploadSkill: vi.fn(),
    adminSetDefaultVersion: vi.fn(),
    adminReviewVersion: vi.fn(),
    adminListKeys: vi.fn(),
    adminResetSecret: vi.fn(),
    adminUpdateKeyStatus: vi.fn(),
    adminRecharge: vi.fn(),
    adminIssueKey: vi.fn(),
    adminListModelProviders: vi.fn(),
    balance: vi.fn(),
  },
}))
vi.mock('../src/api/gateway', () => ({ gatewayApi }))

import SkillsPage from '../src/pages/Skills'
import ConnectPage from '../src/pages/Connect'
import { saveCredentials, useCredentialsStore } from '../src/stores/credentials'
import { saveSession } from '../src/stores/auth'

function flushPromises() {
  return act(async () => {})
}

function renderWithProviders(ui: React.ReactElement) {
  return render(
    <ConfigProvider>
      <AntdApp>
        <MemoryRouter initialEntries={['/']}>{ui}</MemoryRouter>
      </AntdApp>
    </ConfigProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  saveCredentials({ appKey: 'sk-1', secret: 's', adminToken: 'adm' })
  // SkillsView 的管理面按钮按权限渲染：默认以 SUPER_ADMIN 登录
  saveSession({
    token: 'jwt-test', username: 'admin', displayName: '管理员',
    role: 'SUPER_ADMIN', roleName: '超级管理员', permissions: [],
  })
})

const skillRow = {
  tenantId: 'admin-console', skillCode: 'video-gen', name: '视频生成', description: 'd',
  visibility: 'PRIVATE', defaultVersion: '1.0.0', status: 1, pricingConfig: null,
}

describe('SkillsPage', () => {
  it('渲染列表并透传分页参数（admin 面跨租户）', async () => {
    gatewayApi.adminListSkills.mockResolvedValue({
      total: 1, pageNo: 1, pageSize: 20, items: [skillRow],
    })
    renderWithProviders(<SkillsPage />)
    await flushPromises()
    await waitFor(() => {
      expect(gatewayApi.adminListSkills).toHaveBeenCalledWith({
        tenantId: undefined, skillCode: undefined, pageNo: 1, pageSize: 20,
      })
    })
    await waitFor(() => {
      expect(screen.getByTestId('skills-table').textContent).toContain('video-gen')
      expect(screen.getByTestId('skills-table').textContent).toContain('视频生成')
    })
  })

  it('上传表单：填写字段与 zip 后调 adminUploadSkill 并刷新列表', async () => {
    gatewayApi.adminListSkills
      .mockResolvedValueOnce({ total: 0, pageNo: 1, pageSize: 20, items: [] })
      .mockResolvedValueOnce({ total: 0, pageNo: 1, pageSize: 20, items: [] })
    gatewayApi.adminUploadSkill.mockResolvedValue(
      { version: '1.0.0', packageSha256: 'abc', packageSize: 10, status: 0, changelog: '', uploadedAt: '2026-09-15' })
    gatewayApi.adminListModelProviders.mockResolvedValue([{ alias: 'llm-text' }])
    renderWithProviders(<SkillsPage />)
    await flushPromises()

    fireEvent.click(screen.getByTestId('open-upload'))
    // antd Modal 挂载到 body portal
    await screen.findByTestId('upload-tenant')
    fireEvent.change(screen.getByTestId('upload-tenant'), { target: { value: 'admin-console' } })
    fireEvent.change(screen.getByTestId('upload-skillcode'), { target: { value: 'video-gen' } })
    fireEvent.change(screen.getByTestId('upload-version'), { target: { value: '1.0.0' } })
    fireEvent.change(screen.getByTestId('upload-name'), { target: { value: '视频生成' } })
    fireEvent.change(screen.getByTestId('upload-abilities'), { target: { value: 'llm.chat, video.generate' } })

    const fileInput = screen.getByTestId('upload-file') as HTMLInputElement
    Object.defineProperty(fileInput, 'files', {
      value: [new File([new Uint8Array([1, 2, 3])], 'pkg.zip', { type: 'application/zip' })],
    })
    fireEvent.change(fileInput)

    fireEvent.click(screen.getByTestId('upload-submit'))
    await flushPromises()

    expect(gatewayApi.adminUploadSkill).toHaveBeenCalledTimes(1)
    const [tenantId, skillCode, fields, file] = gatewayApi.adminUploadSkill.mock.calls[0]
    expect(tenantId).toBe('admin-console')
    expect(skillCode).toBe('video-gen')
    expect(fields.version).toBe('1.0.0')
    expect(fields.visibility).toBe('PRIVATE')
    // 逗号分隔自动转 JSON 数组（服务端要求合法 JSON）
    expect(fields.requiredAbilities).toBe(JSON.stringify(['llm.chat', 'video.generate']))
    expect((file as File).name).toBe('pkg.zip')
    // 上传成功后刷新列表
    expect(gatewayApi.adminListSkills).toHaveBeenCalledTimes(2)
  })

  it('详情抽屉：版本列表 + 回滚确认后切默认版本', async () => {
    gatewayApi.adminListSkills.mockResolvedValue({
      total: 1, pageNo: 1, pageSize: 20, items: [skillRow],
    })
    gatewayApi.adminSkillDetail.mockResolvedValue({
      skill: {
        tenantId: 'admin-console', skillCode: 'video-gen', name: '视频生成', description: 'd',
        visibility: 'PRIVATE', defaultVersion: '1.0.0', status: 1, pricingConfig: '{"model":"FREE"}',
      },
      versions: [
        { version: '1.0.0', packageSha256: 'a', packageSize: 1, status: 1, changelog: 'init', uploadedAt: '2026-09-15' },
        { version: '1.1.0', packageSha256: 'b', packageSize: 2, status: 1, changelog: 'fix', uploadedAt: '2026-09-15' },
      ],
    })
    gatewayApi.adminSetDefaultVersion.mockResolvedValue({})
    renderWithProviders(<SkillsPage />)
    await flushPromises()

    fireEvent.click(screen.getByTestId('detail-video-gen'))
    await flushPromises()
    // 抽屉内容经 portal 渲染
    await screen.findByTestId('versions-table')
    expect(gatewayApi.adminSkillDetail).toHaveBeenCalledWith('admin-console', 'video-gen')
    expect(document.body.textContent).toContain('1.1.0')

    const makeDefault = document.body
      .querySelector('[data-testid="make-default-1.1.0"]') as HTMLElement
    expect(makeDefault).not.toBeNull()
    fireEvent.click(makeDefault)
    await flushPromises()
    // 回滚确认弹窗（antd modal.confirm 挂 body）；antd 中文两字按钮文案带空格（"回 滚"）
    const confirmOk = await screen.findByRole('button', { name: /^回\s*滚$/ })
    fireEvent.click(confirmOk)
    await waitFor(() => {
      expect(gatewayApi.adminSetDefaultVersion).toHaveBeenCalledWith(
        'admin-console', 'video-gen', '1.1.0')
    })
  })
})

describe('ConnectPage', () => {
  it('仅填 AdminToken 即可签发新 Key 并自动填入表单', async () => {
    gatewayApi.adminIssueKey.mockResolvedValue(
      { appKeyId: 'sk-issued-1', appSecret: 'SEC-ONCE' })
    renderWithProviders(<ConnectPage />)

    fireEvent.change(screen.getByTestId('admintoken-input'), { target: { value: 'adm-token' } })
    fireEvent.click(screen.getByTestId('issue-key-btn'))
    await flushPromises()

    expect(gatewayApi.adminIssueKey).toHaveBeenCalledWith('admin-console')
    const appKeyInput = screen.getByTestId('appkey-input') as HTMLInputElement
    expect(appKeyInput.value).toBe('sk-issued-1')

    // 保存后进入业务面（凭证已写入 store）
    fireEvent.click(screen.getByTestId('save-btn'))
    await flushPromises()
    const store = useCredentialsStore.getState()
    expect(store.appKey).toBe('sk-issued-1')
    expect(store.secret).toBe('SEC-ONCE')
  })
})
