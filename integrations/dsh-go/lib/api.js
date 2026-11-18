// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
import { credentialRef } from '@deepseek-ai/dsh-credentials'

export class ApiError extends Error {
  constructor(message, kind, status) {
    super(message); this.name = 'ApiError'; this.kind = kind; this.status = status
  }
  get transient() { return ['network', 'timeout', 'server', 'protocol'].includes(this.kind) }
}

export function createApi(ctx, config, lifetime) {
  const base = new URL(config.baseUrl)
  if (base.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname)) {
    throw new Error('OpenRayneo must use a loopback HTTP address')
  }
  const tokenRef = () => credentialRef(config.tokenEnv.get())

  async function api(path, {body, signal, timeoutMs = 5_000, cleanup = false} = {}) {
    const requestSignal = AbortSignal.any([AbortSignal.timeout(timeoutMs),
      ...(!cleanup ? [lifetime] : []), ...(signal ? [signal] : [])])
    try {
      requestSignal.throwIfAborted()
      const ref = tokenRef()
      const token = (await ctx.get('credentials')?.resolve(ref))?.value ?? process.env[ref]
      if (!token) throw new ApiError('연결 토큰이 없습니다. 설정을 복사한 뒤 안경 연결을 누르세요', 'auth', 401)
      const response = await fetch(new URL(path, base), {
        method: body === undefined ? 'GET' : 'POST', redirect: 'error',
        headers: {Authorization: `Bearer ${token}`, ...(body === undefined ? {} : {'Content-Type': 'application/json'})},
        ...(body === undefined ? {} : {body: JSON.stringify(body)}), signal: requestSignal,
      })
      // Authentication failures also occur with empty/HTML bodies. Never parse them as JSON.
      if (response.status === 401 || response.status === 403) {
        await response.body?.cancel()
        throw new ApiError(response.status === 401
          ? '연결 토큰이 만료되었습니다. 설정을 다시 복사한 뒤 안경 연결을 누르세요'
          : '로컬 엔드포인트가 접근을 거부했습니다. 주소와 권한을 확인하세요', 'auth', response.status)
      }
      const reader = response.body?.getReader()
      const chunks = []; let bytes = 0
      if (reader) {
        try {
          while (true) {
            const part = await reader.read()
            if (part.done) break
            bytes += part.value.byteLength
            if (bytes > 1_048_576) {
              await reader.cancel()
              throw new ApiError('API 결과가 1 MiB를 초과합니다. 조회 범위를 줄이세요', 'size')
            }
            chunks.push(part.value)
          }
        } finally { reader.releaseLock() }
      }
      const text = Buffer.concat(chunks).toString('utf8')
      let value
      if (text.trim() && /(?:application\/json|\+json)\b/i.test(response.headers.get('content-type') ?? '')) {
        try { value = JSON.parse(text) }
        catch { throw new ApiError('API가 손상된 JSON을 반환했습니다. 로컬 서비스를 확인하세요', 'protocol', response.status) }
      }
      if (!response.ok) {
        const message = typeof value?.error === 'string' ? value.error.slice(0, 160) : `로컬 API 실패: HTTP ${response.status}`
        throw new ApiError(message, response.status >= 500 || response.status === 429 ? 'server' : 'http', response.status)
      }
      if (response.status === 204) return {}
      if (!value || typeof value !== 'object' || Array.isArray(value)) {
        throw new ApiError('API가 JSON 객체를 반환하지 않았습니다. 서비스 주소를 확인하세요', 'protocol', response.status)
      }
      return value
    } catch (error) {
      if (signal?.aborted) throw signal.reason
      if (!cleanup && lifetime.aborted) throw lifetime.reason
      if (error instanceof ApiError) throw error
      throw new ApiError(requestSignal.aborted ? '로컬 서비스 응답 시간 초과. 엔드포인트가 켜져 있는지 확인하세요' : '로컬 서비스에 연결할 수 없습니다. OpenRayneo 엔드포인트를 확인하세요',
        requestSignal.aborted ? 'timeout' : 'network')
    }
  }
  return {api, base, tokenRef}
}
