import { readFileSync } from 'node:fs'
import { Spectrum } from 'spectrum-ts'
import { imessage } from 'spectrum-ts/providers/imessage'

loadEnv(new URL('../.env', import.meta.url))

const projectId = process.env.SPECTRUM_PROJECT_ID || ''
const projectSecret = process.env.SPECTRUM_PROJECT_SECRET || ''

if (!projectId || !projectSecret) {
  console.log('Spectrum credentials are not set. The bridge is not starting.')
  process.exit(0)
}

if (projectId.startsWith('pho_prj_')) {
  console.error('This project id is Photon Beta. Use a Stable Spectrum project.')
  process.exit(1)
}

const bridgeSecret = process.env.EVEN_BRIDGE_SECRET || ''
if (!bridgeSecret) {
  console.error('EVEN_BRIDGE_SECRET is not set.')
  process.exit(1)
}

const api = (process.env.EVEN_API_URL || 'http://localhost:8080').replace(/\/$/, '')
const spaces = new Map()

const app = await Spectrum({
  projectId,
  projectSecret,
  providers: [imessage.config()],
})

pollOutbound()

for await (const [space, message] of app.messages) {
  const from = senderAddress(message)
  const type = message.content?.type || 'none'
  console.log(
    `imessage event platform=${message.platform || 'none'} type=${type} sender=${from ? 'yes' : 'no'}`,
  )
  if (message.platform && message.platform !== 'imessage') continue
  if (message.content?.type !== 'text') continue
  if (!from) continue
  spaces.set(phoneKey(from), space)
  try {
    const response = await fetch(`${api}/api/conversations/inbound`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Even-Bridge-Secret': bridgeSecret,
      },
      body: JSON.stringify({
        from,
        text: message.content.text,
        externalId: message.id || null,
      }),
    })
    if (!response.ok) {
      console.error(`inbound failed: ${response.status}`)
      continue
    }
    const body = await response.json()
    if (!body.ignored && body.reply) {
      await space.send(body.reply)
    }
  } catch (err) {
    console.error('inbound error', err instanceof Error ? err.message : err)
  }
}

function pollOutbound() {
  setInterval(async () => {
    try {
      const response = await fetch(`${api}/api/conversations/outbound`, {
        headers: { 'X-Even-Bridge-Secret': bridgeSecret },
      })
      if (!response.ok) return
      const pending = await response.json()
      for (const item of pending) {
        const space = spaces.get(phoneKey(item.to))
        if (!space) continue
        await space.send(item.body)
        await fetch(`${api}/api/conversations/outbound/${item.id}/sent`, {
          method: 'POST',
          headers: { 'X-Even-Bridge-Secret': bridgeSecret },
        })
      }
    } catch (err) {
      console.error('outbound poll failed', err instanceof Error ? err.message : err)
    }
  }, 4000)
}

function senderAddress(message) {
  const sender = message.sender || {}
  return sender.address || sender.id || ''
}

function phoneKey(value) {
  const trimmed = String(value || '').trim()
  const digits = trimmed.replace(/\D/g, '')
  if (trimmed.includes('@')) return trimmed.toLowerCase()
  if (digits.length === 10) return `+1${digits}`
  if (digits.length === 11 && digits.startsWith('1')) return `+${digits}`
  if (trimmed.startsWith('+') && digits.length >= 8) return `+${digits}`
  return trimmed.toLowerCase()
}

function loadEnv(url) {
  let text = ''
  try {
    text = readFileSync(url, 'utf8')
  } catch {
    return
  }
  for (const line of text.split('\n')) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#') || !trimmed.includes('=')) continue
    const eq = trimmed.indexOf('=')
    const key = trimmed.slice(0, eq).trim()
    let value = trimmed.slice(eq + 1).trim()
    if (
      (value.startsWith('"') && value.endsWith('"'))
      || (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1)
    }
    if (process.env[key] === undefined) process.env[key] = value
  }
}
