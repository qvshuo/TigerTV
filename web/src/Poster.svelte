<script lang="ts">
  let { title, candidates, eager = false, onShown }: { title: string; candidates: string[]; eager?: boolean; onShown?: (url: string) => void } = $props()
  let shown = $state('')
  let failed = $state.raw(new Set<string>())
  const url = $derived(shown || candidates.find(candidate => candidate && !failed.has(candidate)))
</script>

<span class="poster" aria-hidden="true">
  <span class="poster-fallback">{[...title.trim()][0] || '虎'}</span>
  {#if url}
    {#key url}
      <img src={url} alt="" loading={eager ? 'eager' : 'lazy'} decoding="async" referrerpolicy="no-referrer"
        onload={event => { shown = event.currentTarget.getAttribute('src') ?? ''; if (shown) onShown?.(shown) }}
        onerror={event => {
          const failedUrl = event.currentTarget.getAttribute('src')
          if (failedUrl) failed = new Set([...failed, failedUrl])
          if (failedUrl === shown) shown = ''
        }} />
    {/key}
  {/if}
</span>
