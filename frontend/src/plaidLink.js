function loadPlaid() {
  if (window.Plaid) return Promise.resolve(window.Plaid)
  return new Promise((resolve, reject) => {
    const existing = document.querySelector('script[data-plaid-link]')
    if (existing) {
      existing.addEventListener('load', () => resolve(window.Plaid))
      existing.addEventListener('error', () => reject(new Error('Could not load Plaid Link')))
      return
    }
    const script = document.createElement('script')
    script.src = 'https://cdn.plaid.com/link/v2/stable/link-initialize.js'
    script.async = true
    script.dataset.plaidLink = 'true'
    script.onload = () => resolve(window.Plaid)
    script.onerror = () => reject(new Error('Could not load Plaid Link'))
    document.head.appendChild(script)
  })
}

export function openPlaidLink(linkToken) {
  return loadPlaid().then((Plaid) => new Promise((resolve, reject) => {
    if (!Plaid) {
      reject(new Error('Could not load Plaid Link'))
      return
    }
    const handler = Plaid.create({
      token: linkToken,
      onSuccess(publicToken, metadata) {
        resolve({ publicToken, metadata })
      },
      onExit(err) {
        if (err) {
          reject(new Error(err.display_message || err.error_message || 'Plaid Link could not finish'))
          return
        }
        resolve(null)
      },
    })
    handler.open()
  }))
}

export function accountsFromMetadata(metadata) {
  return (metadata?.accounts || []).flatMap((account) => {
    const accountType = account.type === 'credit'
      ? 'credit'
      : account.type === 'depository'
        ? 'depository'
        : null
    if (!accountType || !account.id) return []
    return [{
      externalAccountId: account.id,
      name: account.name || account.subtype || 'Account',
      mask: account.mask || null,
      accountType,
    }]
  })
}
