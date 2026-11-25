// SPDX-License-Identifier: Apache-2.0
// Best-effort, bounded, nonblocking snapshots; never delay the terminal reply.
export function createHintPreview(send, now = Date.now) {
  let last = '', lastAt = -Infinity, busy = false
  return (snapshot) => {
    const text = snapshot.trim()
    if (busy || text.length < 2 || text.length > 240 || text.startsWith('[') || text === 'PASS' || text === last || now() - lastAt < 350) return
    if (/[\uD800-\uDBFF]$/.test(text)) return
    last = text; lastAt = now(); busy = true
    Promise.resolve().then(() => send(text)).catch(() => {}).finally(() => { busy = false })
  }
}
