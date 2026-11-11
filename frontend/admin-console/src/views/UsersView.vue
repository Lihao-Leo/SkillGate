<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { gatewayApi } from '../api/gateway'
import type { RoleView, UserPage, UserView } from '../api/gateway'
import { ApiError } from '../api/client'

const loading = ref(false)
const page = reactive({ pageNo: 1, pageSize: 20, total: 0 })
const keyword = ref('')
const rows = ref<UserView[]>([])
const roles = ref<RoleView[]>([])

const createVisible = ref(false)
const createForm = reactive({ username: '', password: '', displayName: '', roleCode: 'VIEWER' })
const resetResult = reactive({ visible: false, username: '', password: '' })

async function load() {
  loading.value = true
  try {
    const result: UserPage = await gatewayApi.adminListUsers(
      keyword.value.trim() || undefined, page.pageNo, page.pageSize)
    rows.value = result.rows
    page.total = result.total
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

async function loadRoles() {
  try {
    roles.value = await gatewayApi.adminListRoles()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function create() {
  try {
    await gatewayApi.adminCreateUser({
      username: createForm.username.trim(),
      password: createForm.password,
      displayName: createForm.displayName.trim() || undefined,
      roleCode: createForm.roleCode,
    })
    ElMessage.success('用户已创建')
    createVisible.value = false
    createForm.username = ''
    createForm.password = ''
    createForm.displayName = ''
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function changeRole(row: UserView, roleCode: string) {
  try {
    await gatewayApi.adminChangeUserRole(row.username, roleCode)
    ElMessage.success(`已切换 ${row.username} 的角色`)
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function toggleStatus(row: UserView) {
  try {
    await gatewayApi.adminChangeUserStatus(row.username, row.status === 1 ? 0 : 1)
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function resetPassword(row: UserView) {
  try {
    const confirmed = await ElMessageBox.confirm(
      `确认重置 ${row.username} 的密码？旧密码立即失效。`, '重置密码', { type: 'warning' })
      .then(() => true).catch(() => false)
    if (!confirmed) return
    const result = await gatewayApi.adminResetUserPassword(row.username)
    resetResult.username = result.username
    resetResult.password = result.password
    resetResult.visible = true
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function roleTagType(builtin: number): 'danger' | 'primary' | 'info' {
  return builtin === 1 ? 'danger' : 'info'
}

onMounted(() => {
  load()
  loadRoles()
})
</script>

<template>
  <div class="view">
    <div class="view-head">
      <h2>用户与角色</h2>
      <div class="spacer" />
      <el-input v-model="keyword" data-testid="user-keyword" placeholder="按用户名搜索"
                clearable style="width: 220px" @keyup.enter="load" @clear="load" />
      <el-button data-testid="user-search" @click="load">搜索</el-button>
      <el-button type="primary" data-testid="user-create"
                 @click="createVisible = true">新建用户</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" data-testid="user-table">
      <el-table-column prop="username" label="用户名" min-width="140" />
      <el-table-column prop="displayName" label="显示名" min-width="120" />
      <el-table-column label="角色" min-width="170">
        <template #default="{ row }">
          <el-select :model-value="row.role" size="small" style="width: 150px"
                     @change="(v: string) => changeRole(row, v)">
            <el-option v-for="role in roles" :key="role.roleCode" :value="role.roleCode"
                       :label="`${role.roleName}（${role.roleCode}）`" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="lastLoginAt" label="最近登录" min-width="160" />
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small"
                     @click="toggleStatus(row)">{{ row.status === 1 ? '禁用' : '启用' }}</el-button>
          <el-button link type="warning" size="small" @click="resetPassword(row)">重置密码</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-model:current-page="page.pageNo" :page-size="page.pageSize"
                   :total="page.total" layout="total, prev, pager, next"
                   style="margin-top: 14px" @current-change="load" />

    <el-dialog v-model="createVisible" title="新建用户" width="440px">
      <el-form label-width="86px">
        <el-form-item label="用户名">
          <el-input v-model="createForm.username" data-testid="create-username" />
        </el-form-item>
        <el-form-item label="初始密码">
          <el-input v-model="createForm.password" data-testid="create-password"
                    placeholder="不少于 8 位" show-password />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="createForm.displayName" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="createForm.roleCode" data-testid="create-role" style="width: 100%">
            <el-option v-for="role in roles" :key="role.roleCode" :value="role.roleCode"
                       :label="`${role.roleName}（${role.roleCode}）`" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" data-testid="create-submit" @click="create">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resetResult.visible" title="密码已重置" width="420px">
      <p>请将新密码告知 <b>{{ resetResult.username }}</b>（仅本次展示）：</p>
      <code class="reset-pwd" data-testid="reset-password-value">{{ resetResult.password }}</code>
    </el-dialog>
  </div>
</template>

<style scoped>
.view-head { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.view-head h2 { font-size: 17px; margin: 0; }
.spacer { flex: 1; }
.reset-pwd {
  display: inline-block; padding: 8px 14px; border-radius: 8px;
  background: #f4f6fa; font-size: 15px; letter-spacing: 0.5px;
}
</style>
