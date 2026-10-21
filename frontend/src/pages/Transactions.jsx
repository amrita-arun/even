import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { apiFetch } from '../api'
import {
  avatarTone,
  currentMonth,
  errorText,
  formatLedgerDate,
  formatMoney,
  formatMonth,
  monthRange,
} from '../format'
import Icon from '../components/Icon'
import SplitSheet from '../components/SplitSheet'
import { Avatar, Banner, MonthButton, PageIntro } from '../components/ui'

const FILTERS = [
  { id: 'all', label: 'All purchases' },
  { id: 'split', label: 'Split' },
  { id: 'unsplit', label: 'Unsplit' },
  { id: 'flagged', label: 'Flagged' },
]

export default function Transactions() {
  const [params, setParams] = useSearchParams()
  const month = params.get('month') || currentMonth()
  const filter = params.get('state') || 'all'
  const query = params.get('q') || ''
  const openId = params.get('txn') || ''
  const [rows, setRows] = useState([])
  const [report, setReport] = useState(null)
  const [error, setError] = useState('')
  const [draftQuery, setDraftQuery] = useState(query)
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    setDraftQuery(query)
  }, [query])

  useEffect(() => {
    const { from, to } = monthRange(month)
    const search = new URLSearchParams({ from, to, state: 'all' })
    if (query) search.set('q', query)
    let cancelled = false
    Promise.all([
      apiFetch(`/api/transactions?${search}`),
      apiFetch(`/api/reports/month?from=${from}&to=${to}`),
    ])
      .then(([list, monthReport]) => {
        if (cancelled) return
        setRows(list)
        setReport(monthReport)
      })
      .catch((err) => {
        if (!cancelled) setError(errorText(err))
      })
    return () => {
      cancelled = true
    }
  }, [month, query, reloadKey])

  function updateParams(patch) {
    const next = new URLSearchParams(params)
    Object.entries(patch).forEach(([key, value]) => {
      if (!value) next.delete(key)
      else next.set(key, value)
    })
    setParams(next)
  }

  const visible = useMemo(
    () => rows.filter((row) => filter === 'all' || row.splitState === filter),
    [rows, filter],
  )
  const flaggedCount = rows.filter((row) => row.splitState === 'flagged').length
  const selected = rows.find((row) => row.id === openId) || null

  function exportList() {
    const header = ['Date', 'Merchant', 'Category', 'Card amount', 'Your share', 'Status']
    const body = visible.map((row) => [
      row.postedOn,
      row.description,
      row.category || '',
      (row.amountCents / 100).toFixed(2),
      row.yourShareCents == null ? '' : (row.yourShareCents / 100).toFixed(2),
      row.splitState,
    ])
    const csv = [header, ...body]
      .map((line) => line.map((cell) => `"${String(cell).replaceAll('"', '""')}"`).join(','))
      .join('\n')
    const blob = new Blob([csv], { type: 'text/csv' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `even-${month}.csv`
    link.click()
    URL.revokeObjectURL(url)
  }

  return (
    <>
      <PageIntro title="Transactions">
        <button type="button" className="btn secondary" onClick={exportList}>
          <Icon name="download" size={16} /> Export
        </button>
      </PageIntro>
      <Banner>{error}</Banner>
      <section className="equation">
        <p><span>Card balance</span><strong>{report ? formatMoney(report.cardBalanceCents) : '…'}</strong></p>
        <span className="op">−</span>
        <p><span>Friends owe you</span><strong>{report ? formatMoney(report.friendsShareCents) : '…'}</strong></p>
        <span className="op">=</span>
        <p><span>True spend</span><strong>{report ? formatMoney(report.trueSpendCents) : '…'}</strong></p>
      </section>
      <div className="toolbar">
        <div className="chip-row">
          {FILTERS.map((item) => (
            <button
              key={item.id}
              type="button"
              className={filter === item.id ? 'chip active' : 'chip'}
              onClick={() => updateParams({ state: item.id === 'all' ? '' : item.id })}
            >
              {item.id === 'flagged' ? `Flagged · ${flaggedCount}` : item.label}
            </button>
          ))}
        </div>
        <form
          className="search"
          onSubmit={(event) => {
            event.preventDefault()
            updateParams({ q: draftQuery.trim() })
          }}
        >
          <Icon name="search" size={16} />
          <input
            value={draftQuery}
            onChange={(event) => setDraftQuery(event.target.value)}
            placeholder="Search merchants"
            aria-label="Search merchants"
          />
        </form>
        <MonthButton month={month} label={formatMonth(month)} onChange={(next) => updateParams({ month: next })} />
      </div>
      <div className="ledger">
        <div className="ledger-head">
          <span>Merchant / date</span>
          <span>Category</span>
          <span>Card amount</span>
          <span>Your share</span>
          <span>Status</span>
        </div>
        {visible.length === 0 ? (
          <p className="muted-line ledger-empty">No purchases in this view.</p>
        ) : (
          <ul>
            {visible.map((row, index) => (
              <li key={row.id}>
                <button type="button" className="ledger-row" onClick={() => updateParams({ txn: row.id })}>
                  <span className="merchant">
                    <Avatar name={row.description} tone={avatarTone(index)} size={36} />
                    <span>
                      <strong>{row.description}</strong>
                      <small>
                        {formatLedgerDate(row.postedOn)}
                        {row.flagName ? ` · ${row.flagName}` : ''}
                      </small>
                    </span>
                  </span>
                  <span>{row.category || '—'}</span>
                  <span className="mono">{formatMoney(row.amountCents)}</span>
                  <span className="mono">{row.yourShareCents == null ? '—' : formatMoney(row.yourShareCents)}</span>
                  <span className={`status status-${row.splitState}`}>
                    {row.splitState === 'flagged' ? 'Flagged' : row.splitState === 'split' ? '↗ Split' : 'Unsplit'}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
        <p className="ledger-foot">
          <span>{visible.length} of {rows.length} {rows.length === 1 ? 'purchase' : 'purchases'}</span>
          <span>Unsplit purchases count in full.</span>
          {filter !== 'all' ? (
            <button type="button" className="text-btn" onClick={() => updateParams({ state: '' })}>
              View all purchases →
            </button>
          ) : <span />}
        </p>
      </div>
      {selected ? (
        <SplitSheet
          transaction={selected}
          onClose={() => updateParams({ txn: '' })}
          onSaved={() => setReloadKey((key) => key + 1)}
        />
      ) : null}
    </>
  )
}
