import { useEffect, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { apiFetch } from '../api'
import {
  avatarTone,
  errorText,
  firstName,
  formatMoney,
  moneyParts,
} from '../format'
import Icon from '../components/Icon'
import { Avatar, Banner, Modal, PageIntro } from '../components/ui'

export default function People() {
  const { refreshShell } = useOutletContext()
  const [balances, setBalances] = useState(null)
  const [people, setPeople] = useState([])
  const [adding, setAdding] = useState(false)
  const [kind, setKind] = useState('friend')
  const [first, setFirst] = useState('')
  const [last, setLast] = useState('')
  const [venmo, setVenmo] = useState('')
  const [zelle, setZelle] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [filter, setFilter] = useState('owed')
  const [showAll, setShowAll] = useState(false)

  async function load() {
    const [report, personRows] = await Promise.all([
      apiFetch('/api/reports/balances'),
      apiFetch('/api/people'),
    ])
    setBalances(report)
    setPeople(personRows)
  }

  useEffect(() => {
    load().catch((err) => setError(errorText(err)))
  }, [])

  const owed = balances?.owedToYou ?? []
  const youOwe = balances?.youOwe ?? []
  const openIds = new Set([...owed, ...youOwe].map((row) => row.personId))
  const settled = people.filter((person) => !openIds.has(person.id))

  async function addPerson(event) {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      await apiFetch('/api/people', {
        method: 'POST',
        body: JSON.stringify(
          kind === 'reimbursement'
            ? { displayName: first.trim(), kind: 'reimbursement' }
            : {
                firstName: first.trim(),
                lastName: last.trim(),
                venmoHandle: venmo.trim() || null,
                zelleHandle: zelle.trim() || null,
                kind: 'friend',
              },
        ),
      })
      setFirst('')
      setLast('')
      setVenmo('')
      setZelle('')
      setAdding(false)
      await load()
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function nudge(personId) {
    setBusy(true)
    setError('')
    try {
      const draft = await apiFetch('/api/reminders', {
        method: 'POST',
        body: JSON.stringify({ personId }),
      })
      await navigator.clipboard.writeText(draft.text)
      setNotice(draft.usedTemplate
        ? `Copied a template because the draft did not match the ledger. ${draft.text}`
        : `Copied reminder. ${draft.text}`)
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function markPaid(row) {
    setBusy(true)
    setError('')
    try {
      await apiFetch('/api/settlements/mark-paid', {
        method: 'POST',
        body: JSON.stringify({ personId: row.personId, amountCents: row.amountCents }),
      })
      setNotice(`Marked ${formatMoney(row.amountCents)} from ${row.displayName} paid.`)
      await load()
      refreshShell?.()
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function openVenmo(person) {
    if (!person?.venmoHandle) {
      setError('Save a Venmo username before opening Venmo. A display name is not a recipient.')
      return
    }
    setBusy(true)
    setError('')
    try {
      const draft = await apiFetch(`/api/settlements/draft?personId=${person.id}&channel=venmo`)
      if (draft.url) window.open(draft.url, '_blank', 'noopener,noreferrer')
      setNotice(draft.text)
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function copyZelle(personId) {
    setBusy(true)
    setError('')
    try {
      const draft = await apiFetch(`/api/settlements/draft?personId=${personId}&channel=zelle`)
      await navigator.clipboard.writeText(draft.text)
      setNotice(`Copied Zelle draft. ${draft.text}`)
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  const owedParts = moneyParts(balances?.owedToYouCents ?? 0)
  const youOweParts = moneyParts(balances?.youOweCents ?? 0)
  const chart = chartColumns(owed, youOwe, settled)
  const namedIds = new Set(chart.named.map((person) => person.personId))
  const namedCharges = owed.filter((row) => namedIds.has(row.personId))
  const otherCharges = owed.filter((row) => !namedIds.has(row.personId))
  const visibleCharges = filter === 'you-owe'
    ? youOwe
    : filter === 'settled'
      ? []
      : showAll ? owed : namedCharges

  return (
    <>
      <PageIntro title="People">
        <button type="button" className="btn secondary" onClick={() => setAdding(true)}>
          <Icon name="plus" size={16} /> Add a person
        </button>
      </PageIntro>
      <Banner>{error}</Banner>
      <Banner tone="ok">{notice}</Banner>
      <section className="panel balance-card">
        <div className="balance-totals">
          <div>
            <p className="balance-kicker">Friends owe you</p>
            <p className="balance-figure">
              <span>{owedParts.dollars}</span>
              <span className="cents">.{owedParts.cents}</span>
            </p>
          </div>
          <div className="balance-owe">
            <p className="balance-kicker">You owe</p>
            <p className="balance-figure aside">
              <span>{youOweParts.dollars}</span>
              <span className="cents">.{youOweParts.cents}</span>
            </p>
          </div>
        </div>
        <BalanceChart columns={chart.columns} />
        <p className="chart-legend">Above: owed to you · Below: you owe · Flat: settled</p>
      </section>
      <div className="people-head">
        <h2>Balances</h2>
        <div className="chip-row">
          <button type="button" className={filter === 'owed' ? 'chip active' : 'chip'} onClick={() => { setFilter('owed'); setShowAll(false) }}>
            Owed to you
          </button>
          <button type="button" className={filter === 'you-owe' ? 'chip active' : 'chip'} onClick={() => setFilter('you-owe')}>
            You owe · {formatMoney(balances?.youOweCents ?? 0)}
          </button>
          <button type="button" className={filter === 'settled' ? 'chip active' : 'chip'} onClick={() => setFilter('settled')}>
            Settled
          </button>
        </div>
      </div>
      <section className="panel people-ledger">
        {filter === 'settled' ? (
          settled.length === 0 ? <p className="muted-line people-empty">No settled people yet.</p> : settled.map((person) => (
            <div key={person.id} className="people-row">
              <span className="people-who">
                <Avatar name={person.displayName} tone="ink" />
                <span>
                  <strong>{firstName(person.displayName)}</strong>
                </span>
              </span>
              <span />
              <strong className="mono people-amount">{formatMoney(0)}</strong>
            </div>
          ))
        ) : (
          <>
            {visibleCharges.length === 0 ? (
              <p className="muted-line people-empty">
                {filter === 'you-owe' ? 'You owe no one. That list stays empty.' : 'No open charges.'}
              </p>
            ) : null}
            {visibleCharges.map((row) => {
              const person = people.find((item) => item.id === row.personId)
              return (
                <div key={`${row.personId}-${row.transactionId}`} className="people-row">
                  <span className="people-who">
                    <Avatar name={row.displayName} tone={avatarTone(Math.max(0, chart.named.findIndex((item) => item.personId === row.personId)))} />
                    <span>
                      <strong>{firstName(row.displayName)}</strong>
                    </span>
                  </span>
                  <span className="people-detail">
                    <strong>{row.description} · {formatShortDate(row.postedOn)}</strong>
                  </span>
                  <strong className="mono people-amount">{formatMoney(row.amountCents)}</strong>
                  <span className="people-actions">
                    <button type="button" className="btn nudge" disabled={busy} onClick={() => nudge(row.personId)}>
                      Nudge
                    </button>
                    <button type="button" className="btn secondary" disabled={busy} onClick={() => openVenmo(person)}>
                      Venmo
                    </button>
                    <button type="button" className="btn secondary" disabled={busy} onClick={() => copyZelle(row.personId)}>
                      Zelle
                    </button>
                    <button type="button" className="text-btn" disabled={busy} onClick={() => markPaid(row)}>
                      Mark paid
                    </button>
                  </span>
                </div>
              )
            })}
            {filter === 'owed' && otherCharges.length > 0 && !showAll ? (
              <div className="people-footer">
                <span>
                  Other balances · {chart.otherPeople} {chart.otherPeople === 1 ? 'person' : 'people'} · {otherCharges.length} {otherCharges.length === 1 ? 'split' : 'splits'}
                </span>
                <strong className="mono">{formatMoney(chart.otherCents)}</strong>
                <button type="button" className="text-btn" onClick={() => setShowAll(true)}>
                  View details →
                </button>
              </div>
            ) : null}
          </>
        )}
      </section>
      {adding ? (
        <Modal title="Add a person" onClose={() => setAdding(false)} sheet>
          <form className="stack-form" onSubmit={addPerson}>
            <div className="sheet-head">
              <h2>Add a person</h2>
              <button type="button" className="icon-btn" aria-label="Close" onClick={() => setAdding(false)}>
                <Icon name="close" size={18} />
              </button>
            </div>
            <div className="chip-row">
              <button type="button" className={kind === 'friend' ? 'chip active' : 'chip'} onClick={() => setKind('friend')}>
                Friend
              </button>
              <button type="button" className={kind === 'reimbursement' ? 'chip active' : 'chip'} onClick={() => setKind('reimbursement')}>
                Reimbursement
              </button>
            </div>
            <label className="stack-field">
              {kind === 'reimbursement' ? 'Name' : 'First name'}
              <input value={first} onChange={(event) => setFirst(event.target.value)} required />
            </label>
            {kind === 'friend' ? (
              <>
                <label className="stack-field">
                  Last name
                  <input value={last} onChange={(event) => setLast(event.target.value)} required />
                </label>
                <label className="stack-field">
                  Venmo username
                  <input value={venmo} onChange={(event) => setVenmo(event.target.value)} placeholder="alex-kim" />
                </label>
                <label className="stack-field">
                  Zelle
                  <input value={zelle} onChange={(event) => setZelle(event.target.value)} />
                </label>
              </>
            ) : null}
            <Banner>{error}</Banner>
            <button className="btn primary wide" type="submit" disabled={busy}>Add</button>
          </form>
        </Modal>
      ) : null}
    </>
  )
}

const BAR_COLORS = ['#123c2d', '#9bbd77', '#d3e5b8']

function chartColumns(owedRows, youOweRows, settledPeople) {
  const owedPeople = groupPeople(owedRows)
  const named = owedPeople.slice(0, 3)
  const other = owedPeople.slice(3)
  const youOweByPerson = new Map(groupPeople(youOweRows).map((person) => [person.personId, person.amountCents]))
  const namedCents = named.reduce((sum, person) => sum + person.amountCents, 0)
  const otherCents = other.reduce((sum, person) => sum + person.amountCents, 0)
  const maxUp = Math.max(...named.map((person) => person.amountCents), otherCents, 1)
  const columns = named.map((person, index) => {
    const payable = youOweByPerson.get(person.personId) || 0
    return {
      key: person.personId,
      name: firstName(person.displayName),
      owedCents: person.amountCents,
      youOweCents: payable,
      below: payable > 0 ? `You owe ${formatMoney(payable)}` : 'You owe $0',
      color: BAR_COLORS[index % BAR_COLORS.length],
      height: Math.max(5, Math.round((person.amountCents / maxUp) * 139)),
    }
  })
  if (otherCents > 0) {
    const otherCharges = owedRows.filter((row) => !named.some((person) => person.personId === row.personId))
    const otherPayable = other.reduce((sum, person) => sum + (youOweByPerson.get(person.personId) || 0), 0)
    columns.push({
      key: 'other',
      name: 'Other balances',
      owedCents: otherCents,
      youOweCents: otherPayable,
      below: `${other.length} ${other.length === 1 ? 'person' : 'people'} · ${otherCharges.length} ${otherCharges.length === 1 ? 'split' : 'splits'}`,
      color: '#34624a',
      height: Math.max(5, Math.round((otherCents / maxUp) * 139)),
    })
  }
  const shown = new Set([...named, ...other].map((person) => person.personId))
  youOweByPerson.forEach((payable, personId) => {
    if (shown.has(personId)) return
    const person = youOweRows.find((row) => row.personId === personId)
    columns.push({
      key: `owe-${personId}`,
      name: firstName(person?.displayName),
      owedCents: 0,
      youOweCents: payable,
      below: `You owe ${formatMoney(payable)}`,
      height: 0,
    })
  })
  const settled = settledPeople[0]
  if (settled) {
    columns.push({
      key: `settled-${settled.id}`,
      name: firstName(settled.displayName),
      owedCents: 0,
      youOweCents: 0,
      below: 'Settled',
      settled: true,
      height: 0,
    })
  }
  const maxDown = Math.max(...columns.map((column) => column.youOweCents), 1)
  columns.forEach((column) => {
    column.downHeight = column.youOweCents > 0
      ? Math.max(4, Math.round((column.youOweCents / maxDown) * 12))
      : 0
  })
  return { columns, named, namedCents, otherCents, otherPeople: other.length }
}

function BalanceChart({ columns }) {
  if (columns.length === 0) {
    return <p className="muted-line">No balances on the line yet.</p>
  }
  return (
    <div className="balance-chart">
      <div className="balance-line" />
      {columns.map((column) => (
        <div key={column.key} className="balance-col">
          <div className="up-stack">
            <span className="up-label">
              <span>{column.name}</span>
              <strong className="mono">{formatMoney(column.owedCents)}</strong>
            </span>
            {column.settled ? (
              <span className="settled-mark"><i /><i /></span>
            ) : column.height > 0 ? (
              <span className="owed-bar" style={{ height: column.height, background: column.color }} />
            ) : null}
          </div>
          <div className="down-stack">
            {column.downHeight > 0 ? <span className="down-bar" style={{ height: column.downHeight }} /> : null}
            <span className="down-label">{column.below}</span>
          </div>
        </div>
      ))}
    </div>
  )
}

function formatShortDate(iso) {
  if (!iso) return ''
  const [year, month, day] = String(iso).split('-').map(Number)
  if (!year || !month || !day) return ''
  return new Date(year, month - 1, day).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })
}

function groupPeople(rows) {
  const map = new Map()
  rows.forEach((row) => {
    const current = map.get(row.personId) || {
      personId: row.personId,
      displayName: row.displayName,
      amountCents: 0,
    }
    current.amountCents += row.amountCents
    map.set(row.personId, current)
  })
  return [...map.values()]
}
