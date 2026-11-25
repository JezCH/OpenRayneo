// SPDX-License-Identifier: Apache-2.0
/** Optional DSH route for automatic hints only. No fallback, keys, or session model changes. */
export function isAmbientHint(request) {
  const input = request.input
  return request.kind === 'hint' && input?.backfill !== true &&
    input?.voiceContext != null && Number.isFinite(input?.hintDeadline) &&
    (input.source === 'ALWAYS_ON' || (input.source === 'REALTIME' && (input.streamHint === true || input.realtimeHint === true)))
}

export function ambientOverride(config) {
  const read = (name) => {
    const raw = config[name] ?? ''
    if (typeof raw !== 'string' || raw.length > 256 || /[\x00-\x1f\x7f]/.test(raw))
      throw new Error(`Invalid OpenRayneo model setting: ${name}`)
    return raw.trim()
  }
  const provider = read('ambientProvider'), model = read('ambientModel'), reasoningEffort = read('ambientReasoningEffort')
  if (!provider && !model && !reasoningEffort) return null
  if (!provider || !model) throw new Error('OpenRayneo ambientProvider and ambientModel must both be configured')
  return {provider, model, ...(reasoningEffort ? {reasoningEffort} : {})}
}

function usesAmbientRoute(request) {
  return isAmbientHint(request) || (request.kind === 'hint' && request.input?.purpose === 'agent_probe' &&
    request.input?.probeRoute === 'ambient' && request.input?.backfill !== true)
}

export function modelRouteMetadata(route, config, request) {
  const valid = value => typeof value === 'string' && value.trim().length > 0 && value.length <= 256 && !/[\x00-\x1f\x7f]/.test(value)
  if (!valid(route?.provider) || !valid(route?.model)) return undefined
  return {provider: route.provider, model: route.model, selection: usesAmbientRoute(request)
    ? (ambientOverride(config) ? 'ambient_override' : 'ambient_default') : 'default'}
}

export async function selectHintRoute(ctx, config, request, signal) {
  if (!usesAmbientRoute(request)) return ctx.agentDefaultModel.currentSelection()
  const selected = ambientOverride(config)
  if (!selected) return ctx.agentDefaultModel.currentSelection()
  signal?.throwIfAborted()
  if (typeof ctx.llm.resolveCallConfig !== 'function')
    throw new Error('DSH model capability validation is unavailable. Update DSH or clear the ambient override.')
  const resolved = await ctx.llm.resolveCallConfig(selected, signal)
  signal?.throwIfAborted()
  if (resolved?.provider !== selected.provider || resolved?.model !== selected.model ||
      (selected.reasoningEffort !== undefined && resolved.reasoningEffort !== selected.reasoningEffort))
    throw new Error('DSH returned a different model route or explicit reasoning effort')
  return resolved
}
