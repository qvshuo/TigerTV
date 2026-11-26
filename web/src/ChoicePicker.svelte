<script lang="ts">
  let { label, value, items, onSelect }: {
    label: string; value: number; items: { id: number; label: string }[]; onSelect: (id: number) => void
  } = $props()
  let disclosure: HTMLDetailsElement
  let trigger: HTMLElement
  const id = $props.id()
  const chosen = $derived(items.find(item => item.id === value))
  function move(event: KeyboardEvent) {
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
    event.preventDefault()
    disclosure.open = true
    const options = [...disclosure.querySelectorAll<HTMLButtonElement>('li button')]
    const current = options.indexOf(document.activeElement as HTMLButtonElement)
    const last = options.length - 1
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? last
      : event.key === 'ArrowDown' ? Math.min(last, current + 1) : current < 0 ? last : Math.max(0, current - 1)
    options[next]?.focus()
  }
</script>

<div class="picker-field">
  <span id={id}>{label}</span>
  <details name="danmaku-picker" bind:this={disclosure}>
    <summary bind:this={trigger} aria-labelledby={id + ' ' + id + '-value'} onkeydown={move}>
      <span id={id + '-value'}>{chosen?.label || '请选择'}</span>
      <svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
        <path d="m4 6 4 4 4-4" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </summary>
    <ul role="list" aria-labelledby={id}>
      {#each items as item (item.id)}
        <li>
          <button type="button" aria-current={value === item.id ? 'true' : undefined} onkeydown={move} onclick={() => {
            disclosure.open = false
            trigger.focus()
            onSelect(item.id)
          }}>{item.label}</button>
        </li>
      {/each}
    </ul>
  </details>
</div>
