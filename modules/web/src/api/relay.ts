const RELAY_PATH_PATTERN =
  /^\/d\/([A-Za-z0-9_-]{22,64}\.[A-Za-z0-9_-]{22})(?:\/|$)/
const RELAY_TOKEN_KEY = 'legado_relay_token'
const RELAY_TOKEN_PATTERN = /^[A-Za-z0-9_-]{16,64}\.[A-Za-z0-9_-]{32,64}$/

export type RelayBootstrap = {
  deviceId: string
  httpBase: string
  websocketBase: string
}

const parseRelayUrl = (input: string | URL): RelayBootstrap | null => {
  try {
    const url = new URL(input)
    const match = RELAY_PATH_PATTERN.exec(url.pathname)
    if (!match) return null

    const basePath = `/d/${match[1]}/`
    const httpUrl = new URL(basePath, url.origin)
    const websocketUrl = new URL(basePath, url.origin)
    websocketUrl.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'

    return {
      deviceId: match[1],
      httpBase: httpUrl.toString(),
      websocketBase: websocketUrl.toString(),
    }
  } catch {
    return null
  }
}

const readTokenFromHash = (): string | null => {
  const hash = location.hash
  if (!hash) return null

  const queryIndex = hash.indexOf('?')
  const routePart = queryIndex >= 0 ? hash.slice(0, queryIndex) : '#/'
  const parameterText =
    queryIndex >= 0 ? hash.slice(queryIndex + 1) : hash.slice(1)
  const parameters = new URLSearchParams(parameterText)
  const token = parameters.get('relay_token') || parameters.get('token')
  if (!token) return null

  parameters.delete('relay_token')
  parameters.delete('token')
  const remaining = parameters.toString()
  const nextHash = `${routePart || '#/'}${remaining ? `?${remaining}` : ''}`
  history.replaceState(
    null,
    '',
    `${location.pathname}${location.search}${nextHash}`,
  )
  return RELAY_TOKEN_PATTERN.test(token) ? token : null
}

const storeRelayToken = (token: string) => {
  try {
    sessionStorage.setItem(RELAY_TOKEN_KEY, token)
  } catch {
    // Strict privacy modes may disable storage. Failing closed is safer than
    // copying the capability into persistent storage.
  }
}

const currentRelay = parseRelayUrl(location.href)
const fragmentToken = currentRelay ? readTokenFromHash() : null
let inMemoryRelayToken = fragmentToken
if (fragmentToken) storeRelayToken(fragmentToken)

export const getRelayBootstrap = () => currentRelay

export const getRelayBootstrapForUrl = (input: string | URL) =>
  parseRelayUrl(input)

export const getRelayToken = (): string | null => {
  try {
    const token = sessionStorage.getItem(RELAY_TOKEN_KEY)
    if (token && RELAY_TOKEN_PATTERN.test(token)) return token
  } catch {
    // Fall back to the capability retained in module memory.
  }
  return inMemoryRelayToken
}

export const isCurrentRelayRequest = (input: string | URL): boolean => {
  if (!currentRelay) return false
  const candidate = parseRelayUrl(input)
  return (
    candidate?.deviceId === currentRelay.deviceId &&
    candidate.httpBase === currentRelay.httpBase
  )
}

export const getRelayAuthorization = (input: string | URL): string | null => {
  if (!isCurrentRelayRequest(input)) return null
  const token = getRelayToken()
  return token ? `Bearer ${token}` : null
}

export const initializeRelaySession = async (): Promise<void> => {
  if (!currentRelay) return
  const token = getRelayToken()
  if (!token) return

  const abortController = new AbortController()
  const timeout = window.setTimeout(() => abortController.abort(), 10_000)
  const response = await fetch(new URL('_session', currentRelay.httpBase), {
    method: 'POST',
    credentials: 'same-origin',
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
    signal: abortController.signal,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ token }),
  }).finally(() => window.clearTimeout(timeout))

  if (!response.ok) {
    throw new Error(`Relay session exchange failed: ${response.status}`)
  }

  try {
    sessionStorage.removeItem(RELAY_TOKEN_KEY)
  } catch {
    // The HttpOnly session cookie is authoritative after a successful exchange.
  }
  inMemoryRelayToken = null
}
