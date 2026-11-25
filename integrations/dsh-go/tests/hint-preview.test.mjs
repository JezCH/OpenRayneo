// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict'
import {createHintPreview} from '../lib/hint-preview.js'
let t = 0, sent = [], release
const preview = createHintPreview(text => { sent.push(text); return new Promise(r => { release = r }) }, () => t)
const flush = () => new Promise(r => setImmediate(r))
for (const text of ['', 'a', '[P', '[PASS]', 'PASS', '가'.repeat(241), '가\uD800']) preview(text)
await flush(); assert.deepEqual(sent, [])
preview('  첫 힌트  '); await flush(); assert.deepEqual(sent, ['첫 힌트'])
t = 400; preview('다음 힌트'); await flush(); assert.equal(sent.length, 1)
release(); await flush(); preview('첫 힌트'); await flush(); assert.equal(sent.length, 1)
preview('다음 힌트'); await flush(); assert.deepEqual(sent, ['첫 힌트', '다음 힌트'])
release(); await flush(); t = 749; preview('마지막 힌트'); await flush(); assert.equal(sent.length, 2)
t = 750; preview('마지막 힌트'); await flush(); assert.equal(sent.length, 3); release(); await flush()
const fail = createHintPreview(() => { throw Error('network') }, () => t)
fail('실패 테스트'); await flush(); console.log('7 snapshot assertions passed; send failure isolated')
