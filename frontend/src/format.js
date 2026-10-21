export function dollarsToCents(input) {
  const raw = String(input ?? '').trim().replace(/[$,\s]/g, '')
  if (!raw) return 0
  if (!/^\d+(\.\d{0,2})?$/.test(raw)) return null
  const [whole, frac = ''] = raw.split('.')
  return Number(whole) * 100 + Number((frac + '00').slice(0, 2))
}

export function centsToInput(cents) {
  const abs = Math.abs(cents)
  const sign = cents < 0 ? '-' : ''
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`
}

export function formatMoney(cents) {
  if (cents == null || Number.isNaN(cents)) return '—'
  const sign = cents < 0 ? '−' : ''
  const abs = Math.abs(Math.trunc(cents))
  const dollars = Math.floor(abs / 100).toLocaleString('en-US')
  return `${sign}$${dollars}.${String(abs % 100).padStart(2, '0')}`
}

export function moneyParts(cents) {
  const abs = Math.abs(Math.trunc(cents || 0))
  const sign = cents < 0 ? '−' : ''
  return {
    sign,
    dollars: `${sign}$${Math.floor(abs / 100).toLocaleString('en-US')}`,
    cents: String(abs % 100).padStart(2, '0'),
  }
}

export function initialsFromName(name) {
  const parts = String(name || '')
    .trim()
    .split(/\s+/)
    .filter(Boolean)
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase()
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase()
  return '·'
}

export function initialsFromEmail(email) {
  const local = String(email || '').split('@')[0]
  return initialsFromName(local.replace(/[._-]+/g, ' '))
}

export function nameFromEmail(email) {
  const local = String(email || '').split('@')[0]
  const first = local.split(/[._-]+/).filter(Boolean)[0]
  if (!first) return ''
  return first.charAt(0).toUpperCase() + first.slice(1)
}

export function currentMonth() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

export function monthRange(month) {
  const [year, rawMonth] = month.split('-').map(Number)
  const from = `${year}-${String(rawMonth).padStart(2, '0')}-01`
  const last = new Date(year, rawMonth, 0).getDate()
  const to = `${year}-${String(rawMonth).padStart(2, '0')}-${String(last).padStart(2, '0')}`
  return { from, to }
}

export function formatMonth(month) {
  const [year, rawMonth] = month.split('-').map(Number)
  return new Date(year, rawMonth - 1, 1).toLocaleDateString('en-US', {
    month: 'long',
    year: 'numeric',
  })
}

export function formatMonthRange(month) {
  const { from, to } = monthRange(month)
  const start = new Date(from + 'T00:00:00')
  const end = new Date(to + 'T00:00:00')
  const mon = start.toLocaleDateString('en-US', { month: 'short' }).toUpperCase()
  return `${mon} ${String(start.getDate()).padStart(2, '0')} — ${end.getDate()}, ${end.getFullYear()}`
}

export function formatDay(iso) {
  if (!iso) return ''
  const [year, month, day] = iso.split('-').map(Number)
  return new Date(year, month - 1, day).toLocaleDateString('en-US', {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
  })
}

export function formatLedgerDate(iso) {
  if (!iso) return ''
  const [year, month, day] = iso.split('-').map(Number)
  const date = new Date(year, month - 1, day)
  const mon = date.toLocaleDateString('en-US', { month: 'short' }).toUpperCase()
  return `${mon} ${day}`
}

export function relativeUpdated(iso) {
  if (!iso) return 'Not synced'
  const then = new Date(iso).getTime()
  if (Number.isNaN(then)) return 'Not synced'
  const minutes = Math.max(0, Math.round((Date.now() - then) / 60000))
  if (minutes < 1) return 'Updated just now'
  if (minutes < 60) return `Updated ${minutes} min ago`
  const hours = Math.round(minutes / 60)
  if (hours < 48) return `Updated ${hours} hr ago`
  const days = Math.round(hours / 24)
  return `Updated ${days} days ago`
}

export function errorText(err) {
  const raw = err?.message || 'Something went wrong'
  try {
    const parsed = JSON.parse(raw)
    if (typeof parsed === 'string') return parsed
    return parsed.message || parsed.error || raw
  } catch {
    return raw
  }
}

export function allocateCents(total, weights) {
  const sum = weights.reduce((acc, weight) => acc + weight, 0)
  if (total <= 0 || sum <= 0) return weights.map(() => 0)
  const exact = weights.map((weight) => (total * weight) / sum)
  const floors = exact.map((value) => Math.floor(value))
  let remainder = total - floors.reduce((acc, value) => acc + value, 0)
  const order = exact
    .map((value, index) => ({ index, frac: value - Math.floor(value) }))
    .sort((a, b) => b.frac - a.frac || a.index - b.index)
  const shares = [...floors]
  for (let i = 0; i < remainder; i += 1) shares[order[i].index] += 1
  return shares
}

export function firstName(displayName) {
  return String(displayName || '').trim().split(/\s+/)[0] || displayName
}

const AVATAR_TONES = ['forest', 'moss', 'ink']

export function avatarTone(index) {
  return AVATAR_TONES[index % AVATAR_TONES.length]
}

export const CATEGORY_COLORS = {
  'Food & coffee': '#123c2d',
  Shopping: '#34624a',
  Groceries: '#658b53',
  Transport: '#9bbd77',
  'Everything else': '#d3e5b8',
}
