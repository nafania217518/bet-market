import { create } from 'zustand'

export type Theme = 'light' | 'dark'

const system = () => window.matchMedia('(prefers-color-scheme: dark)')

/** The stored choice, or null when none (or localStorage throws). */
function stored(): Theme | null {
  try {
    const t = localStorage.getItem('theme')
    return t === 'light' || t === 'dark' ? t : null
  } catch { return null }
}

/** Current theme for the header switch. */
export const useTheme = create<{ theme: Theme }>(() => ({ theme: 'light' }))

/** Sets the `dark` class on <html>: stored choice, else the system. */
export function applyTheme() {
  const theme: Theme = stored() ?? (system().matches ? 'dark' : 'light')
  document.documentElement.classList.toggle('dark', theme === 'dark')
  useTheme.setState({ theme })
}

/** Stores the user's choice and applies it. */
export function setTheme(theme: Theme) {
  try { localStorage.setItem('theme', theme) } catch { /* not stored: still apply for this page */ }
  document.documentElement.classList.toggle('dark', theme === 'dark')
  useTheme.setState({ theme })
}

/** Applies the theme and follows live system changes while no choice is stored. Returns a stop function. */
export function initTheme() {
  applyTheme()
  const mq = system()
  const onChange = () => { if (!stored()) applyTheme() }
  mq.addEventListener('change', onChange)
  return () => mq.removeEventListener('change', onChange)
}
