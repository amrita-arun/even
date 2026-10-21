import { supabase } from './supabase'

export async function apiFetch(path, options = {}) {
  const { data, error } = await supabase.auth.getSession()
  if (error) {
    throw new Error(`Session error: ${error.message}`)
  }
  const token = data.session?.access_token
  if (!token) {
    throw new Error('Not signed in (no access token). Try signing out and back in.')
  }

  const headers = new Headers(options.headers ?? {})
  headers.set('Authorization', `Bearer ${token}`)
  if (options.body && !(options.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }

  const response = await fetch(path, { ...options, headers })
  if (!response.ok) {
    const text = await response.text()
    throw new Error(text || `Request failed: ${response.status}`)
  }
  if (response.status === 204) {
    return null
  }
  return response.json()
}
