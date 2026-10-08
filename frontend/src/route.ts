import { useEffect, useState } from 'react'

const parts = () => location.hash.replace(/^#\/?/, '').split('/').filter(Boolean)

export function useHashRoute() {
  const [route, setRoute] = useState(parts)
  useEffect(() => {
    const on = () => setRoute(parts())
    window.addEventListener('hashchange', on)
    return () => window.removeEventListener('hashchange', on)
  }, [])
  return route
}
