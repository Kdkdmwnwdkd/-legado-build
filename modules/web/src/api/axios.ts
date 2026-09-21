import axios from 'axios'
import { getRelayAuthorization, getRelayBootstrap } from './relay'

/** @type {string} localStorage保存自定义阅读http服务接口的键值 */
export const baseURL_localStorage_key = 'remoteUrl'
const SECOND = 1000

const relayBootstrap = getRelayBootstrap()
const ajax = axios.create({
  baseURL:
    import.meta.env.VITE_API ||
    relayBootstrap?.httpBase ||
    localStorage.getItem(baseURL_localStorage_key) ||
    location.origin,
  timeout: 120 * SECOND,
  withCredentials: relayBootstrap !== null,
})

ajax.interceptors.request.use(config => {
  const requestUrl = new URL(config.url || '', config.baseURL || location.href)
  const authorization = getRelayAuthorization(requestUrl)
  if (authorization) config.headers.set('Authorization', authorization)
  return config
})

export default ajax
