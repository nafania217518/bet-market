import { act, renderHook } from '@testing-library/react'
import { expect, test } from 'vitest'
import { useHashRoute } from './route'

test('useHashRoute returns the hash path split into parts and follows hash changes', () => {
  location.hash = '#/markets/m1'
  const { result } = renderHook(() => useHashRoute())
  expect(result.current).toEqual(['markets', 'm1'])
  act(() => { location.hash = '#/wallet'; window.dispatchEvent(new HashChangeEvent('hashchange')) })
  expect(result.current).toEqual(['wallet'])
})

test('useHashRoute returns an empty list for the root route', () => {
  location.hash = ''
  const { result } = renderHook(() => useHashRoute())
  expect(result.current).toEqual([])
})
