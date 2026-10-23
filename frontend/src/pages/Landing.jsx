import { useState } from 'react'
import './Landing.css'


const dinnerShares = [
  { initials: "M", name: "Maya", amount: "$42.00" },
  { initials: "A", name: "Alex", amount: "$42.00" },
  { initials: "S", name: "Sam", amount: "$42.00" },
  { initials: "J", name: "Jordan", amount: "$42.00" },
];

const questions = [
  {
    q: "What does true spend mean?",
    a: "The part of the balance that stays yours after shared charges are paid back.",
  },
  {
    q: "Do suggestions send automatically?",
    a: "No. A suggestion waits until you approve it. Nothing moves before that.",
  },
  {
    q: "How is a split settled?",
    a: "You confirm the shares. Even requests only the difference, and you can edit it first.",
  },
];

function Wordmark({ light = false }) {
  return (
    <a className={light ? "wordmark light" : "wordmark"} href="#top">
      <span className="balance-mark" aria-hidden="true">
        <span />
        <span />
      </span>
      even
    </a>
  );
}

function PrimaryButton({ children, href = '#start', onClick }) {
  if (onClick) {
    return (
      <button className="btn primary" type="button" onClick={onClick}>
        {children}
      </button>
    )
  }
  return (
    <a className="btn primary" href={href}>
      {children}
    </a>
  )
}

function QuietButton({ children, href, onClick }) {
  if (href) {
    return (
      <a className="btn quiet" href={href}>
        {children}
      </a>
    )
  }
  return (
    <button className="btn quiet" type="button" onClick={onClick}>
      {children}
    </button>
  )
}

const splitMethods = [
  { id: 'equal', label: 'Equal' },
  { id: 'percent', label: 'Percent' },
  { id: 'itemized', label: 'Itemized' },
]

export default function Landing({ onSignIn }) {
  const [openQuestion, setOpenQuestion] = useState(0);
  const [splitMethod, setSplitMethod] = useState('equal');

  return (
    <div className="landing">
    <div className="page" id="top">
      <header className="nav">
        <Wordmark />
        <nav className="nav-links" aria-label="Page">
          <a href="#dinner">How it works</a>
          <a href="#spending">Product</a>
          <a href="#questions">FAQ</a>
        </nav>
        <div className="nav-actions">
          <QuietButton onClick={onSignIn}>Log in</QuietButton>
          <PrimaryButton onClick={onSignIn}>Get started</PrimaryButton>
        </div>
      </header>

      <section className="hero" aria-labelledby="hero-heading">
        <div className="hero-copy">
          <h1 id="hero-heading">
            Your card
            <br />
            balance isn’t
            <br />
            your spending.
          </h1>
          <p>
            See what you actually spent. Even shares of every table, settled.
          </p>
          <div className="hero-actions">
            <PrimaryButton onClick={onSignIn}>Get started</PrimaryButton>
            <QuietButton href="#dinner">See a dinner</QuietButton>
          </div>
        </div>
        <div className="wallet" aria-hidden="true">
          <article className="wallet-card pale">
            <p className="card-kicker">Card balance</p>
            <p className="card-figure">$2,847.38</p>
          </article>
          <article className="wallet-card spend">
            <div className="card-kicker-row">
              <p className="card-kicker">True spend</p>
              <p className="card-kicker">Oct</p>
            </div>
            <p className="card-figure">$2,112.35</p>
            <div className="mini-balance" />
            <p className="card-note">After shared charges are paid back</p>
          </article>
        </div>
      </section>

      <section className="dinner" id="dinner" aria-labelledby="dinner-heading">
        <div className="section-intro">
          <h2 id="dinner-heading">One dinner. Four shares.</h2>
          <p>Text the group. Even drafts the shares, and you confirm before anyone pays.</p>
        </div>
        <ol className="steps">
          <li>
            <span className="step-line" />
            The charge
          </li>
          <li className="active">
            <span className="step-line" />
            The split
          </li>
          <li>
            <span className="step-line" />
            Your share
          </li>
        </ol>
        <div className="dinner-stage">
          <article className="charge-card">
            <p className="eyebrow">Tonight · card</p>
            <div className="charge-merchant">
              <span className="avatar">L</span>
              <div>
                <p className="merchant">Lazy Dog</p>
                <p className="muted">Dinner</p>
              </div>
            </div>
            <p className="money lg">$168.00</p>
            <p className="caption">The charge hits your card first.</p>
          </article>

          <article className="split-card">
            <header className="split-head">
              <h3>Split expenses</h3>
              <span className="pill">4</span>
            </header>
            <ul>
              {dinnerShares.map((person) => (
                <li key={person.name}>
                  <span className="avatar sm">{person.initials}</span>
                  <span>{person.name}</span>
                  <span className="money">{person.amount}</span>
                </li>
              ))}
            </ul>
            <div className="hairline" />
            <a className="btn primary wide" href="#start">
              Split evenly
            </a>
            <p className="fine">You can change a share before it sends.</p>
          </article>

          <article className="result-card">
            <p className="eyebrow light">Your share</p>
            <p className="money display">$42.00</p>
            <div className="mini-balance light" />
            <p className="card-note">Settled with the table</p>
            <p className="caption light">What stays on your card.</p>
          </article>
        </div>
      </section>

      <section className="spending" id="spending" aria-labelledby="spend-heading">
        <div className="spend-copy">
          <h2 id="spend-heading">
            Your life.
            <br />
            Not the whole table.
          </h2>
        </div>
        <article className="bill">
          <header className="bill-head">
            <h3>Lazy Dog</h3>
            <p className="money md">$168.00</p>
          </header>
          <div className="methods" role="tablist" aria-label="Split method">
            {splitMethods.map((item) => (
              <button
                key={item.id}
                type="button"
                role="tab"
                aria-selected={splitMethod === item.id}
                className={splitMethod === item.id ? 'method on' : 'method'}
                onClick={() => setSplitMethod(item.id)}
              >
                {item.label}
              </button>
            ))}
          </div>
          {splitMethod === 'itemized' ? (
            <ul className="breakdown">
              <li>
                <span>Subtotal</span>
                <span className="money">$148.00</span>
              </li>
              <li>
                <span>Tax</span>
                <span className="money">$8.00</span>
              </li>
              <li>
                <span>Tip</span>
                <span className="money">$12.00</span>
              </li>
            </ul>
          ) : null}
          <ul className="allocations">
            {dinnerShares.map((person) => (
              <li key={person.name}>
                <span>{person.name}</span>
                {splitMethod === 'percent' ? <span className="pct">25%</span> : null}
                <span className="money">{person.amount}</span>
              </li>
            ))}
          </ul>
          <div className="share-bar" aria-hidden="true">
            <span />
            <span />
            <span />
            <span />
          </div>
          <a className="btn primary wide" href="#start">
            Confirm split
          </a>
          <p className="fine">
            {splitMethod === 'percent'
              ? 'Each person takes 25%. Nothing sends until you confirm.'
              : splitMethod === 'itemized'
                ? 'Tax and tip stay in the shares. Nothing sends until you confirm.'
                : 'Four equal shares. Nothing sends until you confirm.'}
          </p>
        </article>
      </section>

      <section className="texts" id="texts" aria-labelledby="text-heading">
        <div className="text-copy">
          <h2 id="text-heading">Split by text.</h2>
          <p>Even asks in iMessage. You answer when you want.</p>
        </div>
        <article className="imessage" aria-label="iMessage with Even">
          <header className="imessage-top">
            <span className="imessage-avatar">E</span>
            <strong>Even</strong>
            <small>iMessage</small>
          </header>
          <p className="imessage-day">Today 9:41 PM</p>
          <div className="imessage-log">
            <p className="imsg in">Lazy Dog looks shared. Split $168.00 four ways?</p>
            <p className="imsg out">Yes — Maya, Alex, Sam, Jordan</p>
            <div className="imsg in receipt">
              <p className="imsg-kicker">Proposed shares</p>
              <ul className="imsg-rows">
                {dinnerShares.map((person) => (
                  <li key={person.name}>
                    <span>{person.name}</span>
                    <span>{person.amount}</span>
                  </li>
                ))}
              </ul>
            </div>
          </div>
          <div className="imsg-replies">
            <a href="#start">Confirm</a>
            <button type="button">Edit</button>
          </div>
          <div className="imessage-compose" aria-hidden="true">
            <span className="imessage-plus">+</span>
            <span>iMessage</span>
          </div>
        </article>
      </section>

      <section className="guess" aria-labelledby="guess-heading">
        <div className="guess-copy">
          <h2 id="guess-heading">
            A good guess.
            <br />
            Your call.
          </h2>
          <p>Even suggests a split. You decide what leaves your card.</p>
        </div>
        <article className="suggest-card">
          <header>
            <span className="avatar">M</span>
            <div>
              <p className="merchant">Maya</p>
              <p className="muted">Just now</p>
            </div>
            <p className="money">$84.00</p>
          </header>
          <h3>Split with Alex?</h3>
          <div className="pair">
            <p>
              <span>Maya</span>
              <strong>$42.00</strong>
            </p>
            <p>
              <span>Alex</span>
              <strong>$42.00</strong>
            </p>
          </div>
          <p className="muted">Confirm</p>
          <div className="suggest-actions">
            <a className="btn primary grow" href="#start">
              Confirm
            </a>
            <button className="btn quiet" type="button">
              Edit
            </button>
            <button className="btn quiet" type="button">
              Dismiss
            </button>
          </div>
        </article>
      </section>

      <section className="control" aria-labelledby="control-heading">
        <div className="section-intro">
          <h2 id="control-heading">Your card. Your say.</h2>
          <p>Every shared charge can sit until you decide who pays.</p>
        </div>
        <ul className="control-grid">
          <li>
            <span className="index">01</span>
            <h3>Connect account</h3>
            <p>Read transactions. Even doesn’t move money to connect.</p>
          </li>
          <li>
            <span className="index">02</span>
            <h3>Review transactions</h3>
            <p>Choose who pays before the request goes out.</p>
          </li>
          <li>
            <span className="index">03</span>
            <h3>Approve requests</h3>
            <p>Nothing is requested until you approve it.</p>
          </li>
        </ul>
      </section>

      <section className="faq" id="questions" aria-labelledby="faq-heading">
        <h2 id="faq-heading">
          A few good
          <br />
          questions.
        </h2>
        <div className="faq-list">
          {questions.map((item, index) => {
            const open = openQuestion === index;
            return (
              <div className={open ? "faq-item open" : "faq-item"} key={item.q}>
                <button
                  type="button"
                  aria-expanded={open}
                  onClick={() => setOpenQuestion(open ? -1 : index)}
                >
                  <span>{item.q}</span>
                  <span className="chevron" aria-hidden="true" />
                </button>
                {open ? <p>{item.a}</p> : null}
              </div>
            );
          })}
        </div>
      </section>

      <section className="close" id="start" aria-labelledby="close-heading">
        <div>
          <h2 id="close-heading">
            Know what’s
            <br />
            yours.
          </h2>
          <PrimaryButton onClick={onSignIn}>Get started</PrimaryButton>
        </div>
      </section>

      <footer className="footer">
        <p className="footer-mark">even</p>
        <nav aria-label="Footer">
          <a href="#dinner">How it works</a>
          <a href="#spending">Product</a>
          <a href="#questions">FAQ</a>
          <a href="#start">Start</a>
        </nav>
        <p className="fine">© 2026 Even</p>
      </footer>
    </div>
    </div>
  )
}
