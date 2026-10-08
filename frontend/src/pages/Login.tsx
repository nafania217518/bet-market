import { useState, type FormEvent } from 'react'
import { api } from '../api'
import { useAuth } from '../auth'

const input = 'border rounded px-2 py-1 block w-full'

export default function Login() {
  const [error, setError] = useState('')

  async function logIn(email: string, password: string) {
    const r = await api<{ accessToken: string }>('/api/auth/login', { method: 'POST', body: { email, password } })
    useAuth.getState().login(r.accessToken)
    location.hash = '#/'
  }

  const submit = (fn: (f: Record<string, string>) => Promise<void>) => (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    setError('')
    fn(Object.fromEntries(new FormData(e.currentTarget)) as Record<string, string>).catch((err) => setError(err.message))
  }

  return (
    <div className="grid gap-8 md:grid-cols-2 max-w-3xl">
      {error && <p role="alert" className="text-red-600 md:col-span-2">{error}</p>}
      <form className="space-y-2" onSubmit={submit((f) => logIn(f.email, f.password))}>
        <h2 className="text-xl font-bold">Log in</h2>
        <label className="block">Email<input name="email" type="email" required className={input} /></label>
        <label className="block">Password<input name="password" type="password" required className={input} /></label>
        <button className="bg-blue-600 text-white rounded px-3 py-1">Log in</button>
      </form>
      <form className="space-y-2" onSubmit={submit(async (f) => {
        await api('/api/auth/register', { method: 'POST', body: { email: f.email, password: f.password, displayName: f.displayName } })
        await logIn(f.email, f.password)
      })}>
        <h2 className="text-xl font-bold">Register</h2>
        <label className="block">Email<input name="email" type="email" required className={input} /></label>
        <label className="block">Password<input name="password" type="password" required className={input} /></label>
        <label className="block">Display name<input name="displayName" required className={input} /></label>
        <button className="bg-blue-600 text-white rounded px-3 py-1">Register</button>
      </form>
    </div>
  )
}
