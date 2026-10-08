import { act, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import App from './App'
import { mockFetch, renderWithQuery } from './test-utils'
import { applyTheme, initTheme } from './theme'

vi.mock('./live', async (orig) => ({ ...(await orig<typeof import('./live')>()), startLive: vi.fn(() => () => {}) }))

let listeners: (() => void)[] = []
/** Stubs the system preference; returns a setter that fires a live change. */
function system(dark: boolean) {
  const mq = { matches: dark, addEventListener: (_: string, f: () => void) => listeners.push(f), removeEventListener: (_: string, f: () => void) => { listeners = listeners.filter((l) => l !== f) } }
  window.matchMedia = (() => mq) as unknown as typeof window.matchMedia
  return (d: boolean) => { mq.matches = d; listeners.forEach((l) => l()) }
}
const isDark = () => document.documentElement.classList.contains('dark')

beforeEach(() => { localStorage.clear(); listeners = []; document.documentElement.classList.remove('dark'); location.hash = '#/'; mockFetch(['GET', /^\/api\/markets\?/, 200, { items: [], page: 0, size: 50, total: 0 }]) })
afterEach(() => vi.restoreAllMocks())

test('the theme follows the system when no choice is stored', () => {
  system(true); applyTheme(); expect(isDark()).toBe(true)
  system(false); applyTheme(); expect(isDark()).toBe(false)
})

test('a stored choice wins over the system', () => {
  system(true); localStorage.setItem('theme', 'light'); applyTheme(); expect(isDark()).toBe(false)
  system(false); localStorage.setItem('theme', 'dark'); applyTheme(); expect(isDark()).toBe(true)
})

test('the header switch toggles the dark class and stores the choice', async () => {
  system(false)
  renderWithQuery(<App />)
  await userEvent.click(screen.getByRole('button', { name: 'Switch to dark theme' }))
  expect(isDark()).toBe(true); expect(localStorage.getItem('theme')).toBe('dark')
  await userEvent.click(screen.getByRole('button', { name: 'Switch to light theme' }))
  expect(isDark()).toBe(false); expect(localStorage.getItem('theme')).toBe('light')
})

test('the switch label names the theme it switches to', () => {
  system(true)
  renderWithQuery(<App />)
  const b = screen.getByRole('button', { name: 'Switch to light theme' })
  expect(b).toHaveAttribute('title', 'Switch to light theme')
})

test('the theme follows live system changes until the user picks one', async () => {
  const set = system(false)
  renderWithQuery(<App />)
  act(() => set(true)); expect(isDark()).toBe(true)
  expect(screen.getByRole('button', { name: 'Switch to light theme' })).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Switch to light theme' }))
  act(() => set(true)); expect(isDark()).toBe(false)
})

test('a localStorage error falls back to the system theme', () => {
  system(true)
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied') })
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('denied') })
  const stop = initTheme()
  expect(isDark()).toBe(true)
  stop()
})
