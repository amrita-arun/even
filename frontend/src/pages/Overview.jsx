import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { apiFetch } from '../api'
import {
  CATEGORY_COLORS,
  avatarTone,
  currentMonth,
  errorText,
  firstName,
  formatDay,
  formatMoney,
  formatMonth,
  formatMonthRange,
  moneyParts,
  monthRange,
} from '../format'
import Icon from '../components/Icon'
import { Avatar, Banner, MonthButton, PageIntro } from '../components/ui'

export default function Overview() {
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const month = params.get('month') || currentMonth()
  const [report, setReport] = useState(null)
  const [flags, setFlags] = useState([])
  const [transactions, setTransactions] = useState([])
  const [balances, setBalances] = useState(null)
  const [error, setError] = useState('')

  useEffect(() => {
    const { from, to } = monthRange(month)
    let cancelled = false
    setError('')
    Promise.all([
      apiFetch(`/api/reports/month?from=${from}&to=${to}`),
      apiFetch('/api/flags?status=open'),
      apiFetch(`/api/transactions?from=${from}&to=${to}`),
      apiFetch('/api/reports/balances'),
    ])
      .then(([monthReport, openFlags, rows, balanceReport]) => {
        if (cancelled) return
        setReport(monthReport)
        setFlags(openFlags)
        setTransactions(rows)
        setBalances(balanceReport)
      })
      .catch((err) => {
        if (!cancelled) setError(errorText(err))
      })
    return () => {
      cancelled = true
    }
  }, [month])

  function setMonth(next) {
    const copy = new URLSearchParams(params)
    copy.set('month', next)
    setParams(copy)
  }

  const hero = moneyParts(report?.trueSpendCents ?? 0)
  const categories = report?.categories ?? []
  const categoryTotal = categories.reduce((sum, row) => sum + row.amountCents, 0)
  const friends = report?.friendsShareCents ?? 0
  const trueSpend = report?.trueSpendCents ?? 0
  const people = groupBalances(balances?.owedToYou ?? [])
  const shown = people.slice(0, 3)
  const other = people.slice(3).reduce((sum, person) => sum + person.amountCents, 0)
  const txnById = new Map(transactions.map((row) => [row.id, row]))

  return (
    <>
      <PageIntro title="Overview">
        <MonthButton month={month} label={formatMonth(month)} onChange={setMonth} />
      </PageIntro>
      <Banner>{error}</Banner>
      <section className="wallet">
        <div className="card-strip">
          <span>
            <Icon name="card" size={17} />
            Card balance
          </span>
          <span className="mono">{report ? formatMoney(report.cardBalanceCents) : '…'}</span>
        </div>
        <div className="hero">
          <div className="hero-top">
            <p>True spend</p>
            <p className="mono range">{formatMonthRange(month)}</p>
            <Icon name="equal" size={20} />
          </div>
          <div className="hero-mid">
            <p className="hero-amount">
              <span>{hero.dollars}</span>
              <span className="cents">.{hero.cents}</span>
            </p>
          </div>
          <div className="reconcile">
            <div className="reconcile-bar" aria-hidden="true">
              <span style={{ flex: trueSpend }} />
              <span className="friends" style={{ width: friends > 0 ? 240 : 0 }} />
              <i />
            </div>
            <div className="reconcile-ends">
              <p><strong className="mono">{formatMoney(trueSpend)}</strong><span>True spend</span></p>
              <p><strong className="mono">{formatMoney(friends)}</strong><span>Friends owe you</span></p>
              <p className="end"><strong className="mono">{formatMoney(report?.cardBalanceCents ?? 0)}</strong><span>Card balance</span></p>
            </div>
          </div>
        </div>
      </section>
      <section className="split-panels">
        <article className="panel">
          <div className="panel-head">
            <h2>Spending</h2>
            <p>Your share</p>
          </div>
          {categories.length === 0 ? (
            <p className="muted-line">No share in this month yet.</p>
          ) : (
            <ul className="spend-list">
              {categories.map((row) => {
                const percent = categoryTotal ? Math.round((row.amountCents / categoryTotal) * 100) : 0
                return (
                  <li key={row.category}>
                    <div className="spend-meta">
                      <span>{row.category}</span>
                      <span className="mono">{formatMoney(row.amountCents)}</span>
                      <em className="mono">{percent}%</em>
                    </div>
                    <div className="spend-track" aria-hidden="true">
                      <span style={{ width: `${percent}%`, background: CATEGORY_COLORS[row.category] || '#d3e5b8' }} />
                    </div>
                  </li>
                )
              })}
            </ul>
          )}
        </article>
        <article className="panel">
          <div className="panel-head">
            <h2>Review splits</h2>
            <p className="mono">{String(flags.length).padStart(2, '0')}</p>
          </div>
          {flags.length === 0 ? (
            <p className="muted-line">Nothing flagged.</p>
          ) : (
            <ul className="attention-list">
              {flags.map((flag, index) => {
                const txn = txnById.get(flag.transactionId)
                const subtitle = [flag.ruleName, txn ? formatDay(txn.postedOn) : '']
                  .filter(Boolean)
                  .join(' · ')
                return (
                  <li key={flag.id}>
                    <button
                      type="button"
                      onClick={() => navigate(`/transactions?month=${month}&txn=${flag.transactionId}`)}
                    >
                      <Avatar name={flag.description} tone={avatarTone(index)} />
                      <span>
                        <strong>{flag.description}</strong>
                        <small>{subtitle}</small>
                      </span>
                      <span className="amount-col">
                        <strong className="mono">{formatMoney(flag.amountCents)}</strong>
                        <small><i className="flag-dot" /> Review split</small>
                      </span>
                    </button>
                  </li>
                )
              })}
            </ul>
          )}
        </article>
      </section>
      <Link to="/people" className="owe-strip">
        <span>
          <small>Friends owe you</small>
          <strong className="mono">{formatMoney(balances?.owedToYouCents ?? friends)}</strong>
        </span>
        {shown.map((person, index) => (
          <span key={person.personId} className="owe-person">
            <Avatar name={person.displayName} tone={avatarTone(index)} />
            <span>
              <strong>{firstName(person.displayName)}</strong>
              <small className="mono">{formatMoney(person.amountCents)}</small>
            </span>
          </span>
        ))}
        {other > 0 ? (
          <span className="owe-other">
            <small>Other balances</small>
            <strong className="mono">{formatMoney(other)}</strong>
          </span>
        ) : <span className="owe-other" />}
        <Icon name="arrow-up-right" size={20} />
      </Link>
    </>
  )
}

function groupBalances(rows) {
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
  return [...map.values()].sort((a, b) => b.amountCents - a.amountCents)
}
