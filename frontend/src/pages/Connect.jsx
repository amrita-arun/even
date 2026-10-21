import { useEffect, useState } from 'react'
import { Link, useOutletContext } from 'react-router-dom'
import { apiFetch } from '../api'
import { errorText, relativeUpdated } from '../format'
import { accountsFromMetadata, openPlaidLink } from '../plaidLink'
import Icon from '../components/Icon'
import { Banner, PageIntro } from '../components/ui'

const INSTITUTIONS = [
  { id: 'demo-national', name: 'Demo National', initials: 'DN' },
  { id: 'sandbox', name: 'Sandbox Credit Union', initials: 'SC' },
  { id: 'example', name: 'Example Bank', initials: 'EB' },
]

export default function Connect() {
  const { refreshShell } = useOutletContext()
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState(INSTITUTIONS[0].id)
  const [items, setItems] = useState([])
  const [accounts, setAccounts] = useState([])
  const [accountId, setAccountId] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [fixtureMode, setFixtureMode] = useState(true)

  async function load() {
    const [linked, accountRows, mode] = await Promise.all([
      apiFetch('/api/plaid/items'),
      apiFetch('/api/accounts'),
      apiFetch('/api/plaid/mode'),
    ])
    setItems(linked)
    setAccounts(accountRows)
    setAccountId((current) => current || accountRows[0]?.id || '')
    setFixtureMode(mode.fixtureMode)
  }

  useEffect(() => {
    load().catch((err) => setError(errorText(err)))
  }, [])

  const visible = INSTITUTIONS.filter((bank) => bank.name.toLowerCase().includes(query.trim().toLowerCase()))

  async function ensureAccount() {
    if (accountId) return accountId
    const bank = INSTITUTIONS.find((item) => item.id === selected)
    const created = await apiFetch('/api/accounts', {
      method: 'POST',
      body: JSON.stringify({ name: bank?.name || 'Card', accountType: 'credit', currency: 'USD' }),
    })
    setAccounts((current) => [...current, created])
    setAccountId(created.id)
    return created.id
  }

  async function finishLink(accountIdToSync, institutionName) {
    const synced = await apiFetch('/api/plaid/sync', {
      method: 'POST',
      body: JSON.stringify({ accountId: accountIdToSync }),
    })
    await apiFetch('/api/jobs/reprocess', { method: 'POST' })
    await load()
    refreshShell?.()
    setNotice(`Linked ${institutionName}. Synced ${synced.imported} row(s).`)
  }

  async function link() {
    setBusy(true)
    setError('')
    setNotice('')
    try {
      const linkToken = await apiFetch('/api/plaid/link-token', { method: 'POST' })
      if (linkToken.fixtureMode) {
        const id = await ensureAccount()
        const exchanged = await apiFetch('/api/plaid/exchange', {
          method: 'POST',
          body: JSON.stringify({ accountId: id, publicToken: `fixture-public-${id}` }),
        })
        await finishLink(id, exchanged.institutionName || 'Fixture Bank')
        return
      }
      const result = await openPlaidLink(linkToken.linkToken)
      if (!result) return
      const selected = accountsFromMetadata(result.metadata)
      if (selected.length === 0) {
        throw new Error('Choose a checking or credit account in Plaid. Other account types stay out of the ledger.')
      }
      const exchanged = await apiFetch('/api/plaid/exchange', {
        method: 'POST',
        body: JSON.stringify({
          publicToken: result.publicToken,
          institutionName: result.metadata?.institution?.name || null,
          accounts: selected,
        }),
      })
      const accountIdToSync = exchanged.accountIds?.[0]
      if (!accountIdToSync) throw new Error('Plaid linked, but no account was stored.')
      await finishLink(accountIdToSync, exchanged.institutionName || 'Plaid')
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function syncItem(item) {
    const id = item.accountIds?.[0]
    if (!id) {
      setError('This link has no account to sync.')
      return
    }
    setBusy(true)
    setError('')
    setNotice('')
    try {
      const synced = await apiFetch('/api/plaid/sync', {
        method: 'POST',
        body: JSON.stringify({ accountId: id }),
      })
      await apiFetch('/api/jobs/reprocess', { method: 'POST' })
      await load()
      refreshShell?.()
      setNotice(`Synced ${synced.imported} row(s).`)
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function disconnect(id) {
    setBusy(true)
    setError('')
    try {
      await apiFetch(`/api/plaid/items/${id}`, { method: 'DELETE' })
      await load()
      refreshShell?.()
      setNotice('Disconnected.')
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function seed() {
    setBusy(true)
    setError('')
    try {
      const seeded = await apiFetch('/api/demo/seed', { method: 'POST' })
      await apiFetch('/api/jobs/reprocess', { method: 'POST' })
      await load()
      setNotice(seeded.alreadySeeded ? 'Demo data is already loaded.' : 'Loaded demo people and charges.')
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function reset() {
    if (!window.confirm('Remove people, splits, reimbursements, and demo data? The Capital One statement stays.')) return
    setBusy(true)
    setError('')
    try {
      await apiFetch('/api/demo/reset', { method: 'POST' })
      await load()
      setNotice('Reset to the Capital One statement.')
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function upload(event) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    setBusy(true)
    setError('')
    try {
      const id = await ensureAccount()
      const body = new FormData()
      body.append('file', file)
      body.append('account_id', id)
      await apiFetch('/api/imports/statement', { method: 'POST', body })
      await apiFetch('/api/jobs/reprocess', { method: 'POST' })
      setNotice(`Imported ${file.name}.`)
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <PageIntro title="Connect account">
        <Link to="/" className="btn secondary">
          <Icon name="arrow-left" size={16} /> Back to overview
        </Link>
      </PageIntro>
      <ol className="steps">
        <li className="current"><span>1</span> Choose your bank</li>
        <li><span>2</span> Securely link account</li>
        <li><span>3</span> Review & finish</li>
      </ol>
      <section className="connect-grid">
        <article className="panel">
          {fixtureMode ? <p className="eyebrow">Demo</p> : null}
          {fixtureMode ? (
            <>
              <h2>Choose a bank</h2>
              <p className="muted-line">No real account will be connected.</p>
              <label className="search">
                <Icon name="search" size={16} />
                <input
                  value={query}
                  onChange={(event) => setQuery(event.target.value)}
                  placeholder="Search institutions"
                  aria-label="Filter demo institutions"
                />
              </label>
              <ul className="bank-list">
                {visible.map((bank) => (
                  <li key={bank.id}>
                    <button
                      type="button"
                      className={selected === bank.id ? 'bank selected' : 'bank'}
                      onClick={() => setSelected(bank.id)}
                    >
                      <span className="avatar tone-forest">{bank.initials}</span>
                      <span>
                        <strong>{bank.name}</strong>
                        {selected === bank.id ? <small>Selected</small> : null}
                      </span>
                      {selected === bank.id ? <Icon name="check" size={18} /> : <Icon name="chevron" size={16} />}
                    </button>
                  </li>
                ))}
              </ul>
              <button type="button" className="btn primary wide" disabled={busy || visible.length === 0} onClick={link}>
                Continue
              </button>
            </>
          ) : (
            <>
              <h2>Choose a bank</h2>
              <p className="muted-line">Authorize at your bank.</p>
              <button type="button" className="btn primary wide" disabled={busy} onClick={link}>
                Continue
              </button>
            </>
          )}
        </article>
        <article className="panel security">
          <p><Icon name="shield" size={16} /> Authorize at your bank</p>
          <p><Icon name="card" size={16} /> Review account</p>
          <p><Icon name="lock" size={16} /> Read-only access</p>
          <p><Icon name="shield" size={16} /> No bank passwords</p>
          <p><Icon name="unlink" size={16} /> Disconnect anytime</p>
        </article>
      </section>
      <section className="panel">
        <h2>Linked</h2>
        {items.length === 0 ? <p className="muted-line">Nothing linked yet.</p> : null}
        <ul className="linked-list">
          {items.map((item) => (
            <li key={item.id}>
              <span>
                <strong>{item.institutionName}</strong>
                <small>{relativeUpdated(item.updatedAt)}</small>
              </span>
              <span className="row-actions">
                <button type="button" className="btn secondary" disabled={busy || !item.accountIds?.length} onClick={() => syncItem(item)}>
                  Sync
                </button>
                <button type="button" className="btn secondary" disabled={busy} onClick={() => disconnect(item.id)}>
                  <Icon name="unlink" size={14} /> Disconnect
                </button>
              </span>
            </li>
          ))}
        </ul>
      </section>
      <Banner>{error}</Banner>
      <Banner tone="ok">{notice}</Banner>
      <section className="quiet-tools">
        <h2>Account tools</h2>
        <label className="stack-field">
          Card for the fixture link and statement upload
          <select value={accountId} onChange={(event) => setAccountId(event.target.value)}>
            <option value="">Create one on the next action</option>
            {accounts.map((account) => (
              <option key={account.id} value={account.id}>{account.name}</option>
            ))}
          </select>
        </label>
        <div className="quiet-actions">
          <button type="button" className="btn secondary" disabled={busy} onClick={seed}>Load demo</button>
          <button type="button" className="btn secondary" disabled={busy} onClick={reset}>Reset</button>
          <label className="btn secondary file-btn">
            Upload statement
            <input type="file" accept=".pdf,.csv,application/pdf,text/csv" onChange={upload} disabled={busy} />
          </label>
          <Link to="/imessage" className="btn secondary">iMessage preview</Link>
        </div>
      </section>
    </>
  )
}
