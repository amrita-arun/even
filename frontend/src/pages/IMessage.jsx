import { useEffect, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { apiFetch } from '../api'
import { allocateCents, errorText, firstName, formatMoney, initialsFromName, moneyParts } from '../format'
import Icon from '../components/Icon'
import { Avatar, Banner, Modal, PageIntro } from '../components/ui'

export default function IMessage() {
  const { initials, refreshShell } = useOutletContext()
  const [proposals, setProposals] = useState([])
  const [selectedId, setSelectedId] = useState('')
  const [postedOn, setPostedOn] = useState({})
  const [owed, setOwed] = useState(0)
  const [thread, setThread] = useState(null)
  const [draft, setDraft] = useState('')
  const [handle, setHandle] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [editing, setEditing] = useState(null)
  const [people, setPeople] = useState([])
  const [picked, setPicked] = useState([])

  async function reload(preferredId) {
    const [rows, balances, transactions, conversation] = await Promise.all([
      apiFetch('/api/proposals?status=pending'),
      apiFetch('/api/reports/balances'),
      apiFetch('/api/transactions'),
      apiFetch('/api/conversations'),
    ])
    setThread(conversation)
    setHandle(conversation.handle || '')
    setProposals(rows)
    setSelectedId((current) => {
      const next = preferredId || current
      return rows.some((row) => row.id === next) ? next : rows[0]?.id || ''
    })
    setOwed(balances.owedToYouCents || 0)
    setPostedOn(Object.fromEntries(transactions.map((row) => [row.id, row.postedOn])))
  }

  useEffect(() => {
    reload().catch((err) => setError(errorText(err)))
  }, [])

  const selected = proposals.find((proposal) => proposal.id === selectedId) || null
  const shares = selected ? proposalShares(selected) : []
  const yours = shares[0]?.cents || 0
  const requested = shares.slice(1).reduce((sum, share) => sum + share.cents, 0)
  const amount = selected ? moneyParts(selected.amountCents) : null
  const when = selected ? formatChargeStamp(postedOn[selected.transactionId]) : ''

  async function confirm() {
    if (!selected) return
    setBusy(true)
    setError('')
    setNotice('')
    try {
      await apiFetch(`/api/proposals/${selected.id}/approve`, { method: 'POST' })
      setNotice(`Confirmed ${selected.description}.`)
      await reload()
      refreshShell?.()
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function cancel() {
    if (!selected) return
    setBusy(true)
    setError('')
    setNotice('')
    try {
      await apiFetch(`/api/proposals/${selected.id}/reject`, { method: 'POST' })
      setNotice(`Canceled ${selected.description}.`)
      await reload()
      refreshShell?.()
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function send(event) {
    event.preventDefault()
    const text = draft.trim()
    if (!text) return
    setBusy(true)
    setError('')
    setNotice('')
    try {
      await apiFetch('/api/conversations/messages', {
        method: 'POST',
        body: JSON.stringify({ text }),
      })
      setDraft('')
      await reload()
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function saveHandle(event) {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      const conversation = await apiFetch('/api/conversations/handle', {
        method: 'PUT',
        body: JSON.stringify({ handle }),
      })
      setThread(conversation)
      setHandle(conversation.handle || '')
      setNotice('Saved the number Even will answer.')
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function startEdit() {
    if (!selected) return
    setError('')
    try {
      const list = await apiFetch('/api/people')
      setPeople(list.filter((person) => person.kind === 'friend'))
      setPicked(selected.personIds || [])
      setEditing(selected)
    } catch (err) {
      setError(errorText(err))
    }
  }

  async function saveEdit(event) {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      await apiFetch(`/api/proposals/${editing.id}/edit`, {
        method: 'POST',
        body: JSON.stringify({ personIds: picked }),
      })
      setNotice('Updated. It stays pending until you confirm.')
      setEditing(null)
      await reload(editing.id)
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <PageIntro title="iMessage agent">
        <span className="chip quiet">Simulated</span>
      </PageIntro>
      <Banner>{error}</Banner>
      <Banner tone="ok">{notice}</Banner>
      {thread && !thread.handle ? (
        <form className="handle-row" onSubmit={saveHandle}>
          <label>
            Your phone number
            <input
              value={handle}
              onChange={(event) => setHandle(event.target.value)}
              placeholder="+15551212"
              aria-label="Your phone number"
            />
          </label>
          <button className="btn secondary" type="submit" disabled={busy || !handle.trim()}>Save</button>
        </form>
      ) : null}
      {thread?.smsHref ? (
        <p className="muted-line">
          <a href={thread.smsHref}>Text Even first</a>
          {' '}so the line is a conversation you started.
        </p>
      ) : null}
      <section className="imessage-grid">
        <article className="panel thread">
          <header className="thread-head">
            <Avatar name="Even" initials="EV" tone="forest" size={40} />
            <span className="thread-id">
              <strong>Even</strong>
              <small>iMessage</small>
            </span>
            <span className="thread-info">
              <Icon name="info" size={20} />
            </span>
          </header>
          <div className="thread-body">
            {(thread?.messages || []).length === 0 ? (
              <p className="muted-line">Nothing is waiting. A new question shows up here when a purchase needs you.</p>
            ) : thread.messages.map((message) => (
              message.direction === 'inbound' ? (
                <div key={message.id} className="bubble-row">
                  <p className="bubble you">{message.body}</p>
                </div>
              ) : (
                <p key={message.id} className="bubble">{message.body}</p>
              )
            ))}
          </div>
          <form className="thread-compose" onSubmit={send}>
            <div className="compose-field">
              <input
                aria-label="Message"
                placeholder="iMessage"
                value={draft}
                onChange={(event) => setDraft(event.target.value)}
              />
              <button type="submit" className="compose-icon" aria-label="Send" disabled={busy || !draft.trim()}>
                <Icon name="arrow-up" size={18} />
              </button>
            </div>
          </form>
        </article>
        <div className="imessage-side">
          <article className="panel confirm-card">
            <span className="chip quiet">Awaiting confirmation</span>
            {proposals.length > 1 ? (
              <label className="stack-field">
                Pending proposal
                <select className="proposal-switch" value={selectedId} onChange={(event) => setSelectedId(event.target.value)}>
                  {proposals.map((proposal) => (
                    <option key={proposal.id} value={proposal.id}>
                      {proposal.description} · {formatMoney(proposal.amountCents)}
                    </option>
                  ))}
                </select>
              </label>
            ) : null}
            {selected && amount ? (
              <>
                <div className="confirm-charge">
                  <Avatar name={selected.description} initials={merchantMark(selected.description)} tone="forest" size={48} />
                  <span className="confirm-merchant">
                    <strong>{selected.description}</strong>
                    {when ? <small className="mono">{when}</small> : null}
                  </span>
                  <p className="confirm-amount">
                    <span>{amount.dollars}</span>
                    <span className="cents">.{amount.cents}</span>
                  </p>
                </div>
                <hr className="confirm-rule" />
                <ul className="share-rows">
                  {shares.map((share, index) => (
                    <li key={`${share.label}-${index}`}>
                      <Avatar
                        name={share.label}
                        initials={index === 0 ? initials : initialsFromName(share.label)}
                        tone="forest"
                        size={40}
                      />
                      <span>{share.label}</span>
                      <span className="pct mono">{share.percent}%</span>
                      <span className="amt mono">{formatMoney(share.cents)}</span>
                    </li>
                  ))}
                </ul>
                <div className="share-split">
                  <div className="share-split-bar" aria-hidden="true">
                    <i className="yours" style={{ flex: Math.max(yours, 1) }} />
                    {requested > 0 ? <i className="theirs" style={{ flex: requested }} /> : null}
                  </div>
                  <p>Your share {formatMoney(yours)} · Proposed request {formatMoney(requested)}</p>
                </div>
                <p className="match-line">{selected.score}% match</p>
              </>
            ) : (
              <p className="muted-line">No pending proposal to confirm. The conversation stays illustrative.</p>
            )}
            <div className="confirm-actions">
              <button type="button" className="btn primary" disabled={busy || !selected} onClick={confirm}>
                Confirm split
              </button>
              <button type="button" className="btn secondary" disabled={busy || !selected} onClick={startEdit}>
                Edit
              </button>
              <button type="button" className="btn secondary" disabled={busy || !selected} onClick={cancel}>
                Cancel
              </button>
            </div>
          </article>
          <div className="owe-note">
            <Icon name="shield" size={24} />
            <div>
              <strong>Friends owe you</strong>
              <p>{formatMoney(owed)} · Draft excluded.</p>
            </div>
          </div>
        </div>
      </section>
      {editing ? (
        <Modal title="Edit suggestion" onClose={() => setEditing(null)} sheet>
          <form className="stack-form" onSubmit={saveEdit}>
            <div className="sheet-head">
              <div>
                <h2>Edit {editing.description}</h2>
                <p className="muted-line">This stays pending. A proposal that already has shares has to be dismissed and described again.</p>
              </div>
              <button type="button" className="icon-btn" aria-label="Close" onClick={() => setEditing(null)}>
                <Icon name="close" size={18} />
              </button>
            </div>
            {people.map((person) => (
              <label key={person.id} className="check-row">
                <input
                  type="checkbox"
                  checked={picked.includes(person.id)}
                  onChange={() => setPicked((current) => (
                    current.includes(person.id)
                      ? current.filter((id) => id !== person.id)
                      : [...current, person.id]
                  ))}
                />
                {person.displayName}
              </label>
            ))}
            <Banner>{error}</Banner>
            <button className="btn primary wide" type="submit" disabled={busy || picked.length === 0}>Save</button>
          </form>
        </Modal>
      ) : null}
    </>
  )
}

function proposalShares(proposal) {
  const friends = (proposal.personNames || []).map(firstName).filter(Boolean)
  const labels = ['You', ...friends]
  const amount = proposal.amountCents || 0
  if (amount <= 0) return []
  const cents = allocateCents(amount, labels.map(() => 1))
  return labels.map((label, index) => ({
    label,
    cents: cents[index],
    percent: Math.round((cents[index] / amount) * 100),
  }))
}

function merchantMark(description) {
  const letter = String(description || '').trim()[0]
  return letter ? letter.toUpperCase() : '·'
}

function formatChargeStamp(iso) {
  if (!iso) return ''
  const [year, month, day] = iso.split('-').map(Number)
  const date = new Date(year, month - 1, day)
  const weekday = date.toLocaleDateString('en-US', { weekday: 'short' }).toUpperCase()
  const mon = date.toLocaleDateString('en-US', { month: 'short' }).toUpperCase()
  return `${weekday} · ${mon} ${day}`
}
