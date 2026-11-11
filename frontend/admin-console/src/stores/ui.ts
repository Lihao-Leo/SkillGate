import { create } from 'zustand'

/** 暗色主题（localStorage `sg-theme` 键延续 Vue 版用户偏好）。 */

const THEME_KEY = 'sg-theme'

interface UiState {
  dark: boolean
}

export const useUiStore = create<UiState>(() => ({ dark: localStorage.getItem(THEME_KEY) === 'dark' }))

export function toggleTheme() {
  const dark = !useUiStore.getState().dark
  useUiStore.setState({ dark })
  localStorage.setItem(THEME_KEY, dark ? 'dark' : 'light')
}
