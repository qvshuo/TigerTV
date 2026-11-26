<script lang="ts">
  import { cachedRecentPoster, rememberRecentPoster } from './recent-posters.ts'

  let { title, candidates, eager = false, priority = false, recent = false, onShown }:
    { title: string; candidates: string[]; eager?: boolean; priority?: boolean; recent?: boolean; onShown?: (url: string) => void } = $props()
  let shown = $state('')
  let failed = $state.raw(new Set<string>())
  const url = $derived(shown || candidates.find(candidate => candidate && !failed.has(candidate)))
  let cached = $state.raw<{ url: string; objectUrl?: string }>()
  $effect(() => {
    if (!recent || !url || !globalThis.indexedDB) return
    const candidate = url
    let active = true
    let objectUrl: string | undefined
    void cachedRecentPoster(candidate).then(blob => {
      if (!active) return
      objectUrl = blob ? URL.createObjectURL(blob) : undefined
      cached = { url: candidate, objectUrl }
    })
    return () => {
      active = false
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  })
  const imageUrl = $derived(recent && globalThis.indexedDB ? (cached?.url === url ? cached?.objectUrl ?? url : undefined) : url)
</script>

<span class="poster" aria-hidden="true">
  <span class="poster-fallback">{[...title.trim()][0] || '虎'}</span>
  {#if imageUrl}
    {#key imageUrl}
      {@const candidate = url}
      <img src={imageUrl} alt="" fetchpriority={priority ? 'high' : undefined} loading={eager || priority ? 'eager' : 'lazy'} decoding="async" referrerpolicy="no-referrer"
        onload={() => {
          shown = candidate ?? ''
          if (!shown) return
          onShown?.(shown)
          if (recent && !cached?.objectUrl) rememberRecentPoster(shown)
        }}
        onerror={() => {
          const failedUrl = candidate
          if (failedUrl) failed = new Set([...failed, failedUrl])
          if (failedUrl === shown) shown = ''
        }} />
    {/key}
  {/if}
</span>
