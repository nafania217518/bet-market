import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test, vi } from 'vitest'
import { PoolBar, Segmented, timeLabel } from './ui'

test('the pool bar splits TRUE and FALSE in proportion to the pools', () => {
  const { container } = render(<PoolBar t={60} f={40} />)
  expect(screen.getByRole('img', { name: 'TRUE 60%, FALSE 40%' })).toBeInTheDocument()
  expect(container.querySelector<HTMLElement>('[data-side="TRUE"]')!.style.width).toBe('60%')
  expect(container.querySelector<HTMLElement>('[data-side="FALSE"]')!.style.width).toBe('40%')
})

test('the pool bar is empty when the pool is 0', () => {
  const { container } = render(<PoolBar t={0} f={0} />)
  expect(screen.getByRole('img', { name: 'No bets yet' })).toBeInTheDocument()
  expect(container.querySelector('[data-side]')).toBeNull()
})

test('the relative time label shows the time left until freeze', () => {
  const freeze = '2026-10-10T10:00:00Z'
  const at = (min: number) => Date.parse(freeze) - min * 60000
  expect(timeLabel('OPEN', freeze, at(133))).toBe('Freezes in 2h 13m')
  expect(timeLabel('OPEN', freeze, at(7))).toBe('Freezes in 7m')
  expect(timeLabel('OPEN', freeze, at(3 * 1440 + 125))).toBe('Freezes in 3d 2h')
  expect(timeLabel('OPEN', freeze, at(-1))).toBe('Freezing now')
})

test('the relative time label names closed states', () => {
  expect(timeLabel('FROZEN', '')).toBe('Frozen')
  expect(timeLabel('ENDED', '')).toBe('Ended')
  expect(timeLabel('RESOLVED', '')).toBe('Resolved')
  expect(timeLabel('CANCELLED', '')).toBe('Cancelled')
})

test('the segmented control marks the chosen option as pressed and reports clicks', async () => {
  const onChange = vi.fn()
  render(<Segmented label="Sort" value="a" options={['a', 'b']} onChange={onChange} />)
  expect(screen.getByRole('button', { name: 'a' })).toHaveAttribute('aria-pressed', 'true')
  expect(screen.getByRole('button', { name: 'b' })).toHaveAttribute('aria-pressed', 'false')
  await userEvent.click(screen.getByRole('button', { name: 'b' }))
  expect(onChange).toHaveBeenCalledWith('b')
})
