<script lang="ts">
  import { tick } from 'svelte'
  let { label, value, items, onSelect }: {
    label: string; value: number; items: { id: number; label: string }[]; onSelect: (id: number) => void
  } = $props()
  let panel: HTMLUListElement
  let trigger: HTMLButtonElement
  let fallbackOpen = $state(false)
  const id = $props.id()
  const chosen = $derived(items.find(item => item.id === value))
  const native = typeof HTMLElement !== 'undefined' && 'showPopover' in HTMLElement.prototype

  function place() {
    if (!panel || !trigger || !(native ? panel.matches(':popover-open') : fallbackOpen)) return
    const box = trigger.getBoundingClientRect()
    const below = window.innerHeight - box.bottom - 12
    const above = box.top - 12
    panel.style.width = `${box.width}px`
    const up = below < Math.min(panel.scrollHeight, 224) && above > below
    panel.style.left = `${box.left}px`
    panel.style.maxHeight = `${Math.max(0, Math.min(224, up ? above : below))}px`
    panel.style.top = up ? 'auto' : `${box.bottom + 4}px`
    panel.style.bottom = up ? `${window.innerHeight - box.top + 4}px` : 'auto'
    panel.dataset.positioned = ''
  }
  function close() {
    if (native) panel.hidePopover()
    else fallbackOpen = false
    trigger.focus()
  }
  async function move(event: KeyboardEvent) {
    if (event.key === 'Escape' && fallbackOpen) {
      event.preventDefault()
      event.stopPropagation()
      close()
      return
    }
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
    event.preventDefault()
    if (native && !panel.matches(':popover-open')) panel.showPopover()
    if (!native && !fallbackOpen) {
      panel.removeAttribute('data-positioned')
      fallbackOpen = true
      await tick()
    }
    place()
    const options = [...panel.querySelectorAll<HTMLButtonElement>('button')]
    const current = options.indexOf(document.activeElement as HTMLButtonElement)
    const last = options.length - 1
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? last
      : event.key === 'ArrowDown' ? Math.min(last, current + 1) : current < 0 ? last : Math.max(0, current - 1)
    options[next]?.focus()
  }
</script>

<svelte:window onresize={place} />
<svelte:document onscrollcapture={place} />
<div class="picker-field">
  <span id={id}>{label}</span>
  <button type="button" class="picker-trigger" bind:this={trigger} aria-labelledby={id + ' ' + id + '-value'}
    popovertarget={native ? id + '-options' : undefined} aria-expanded={native ? undefined : fallbackOpen}
    aria-controls={id + '-options'} onkeydown={move} onclick={() => {
      if (!native) {
        if (!fallbackOpen) panel.removeAttribute('data-positioned')
        fallbackOpen = !fallbackOpen
        void tick().then(place)
      }
    }}>
    <span id={id + '-value'}>{chosen?.label || '请选择'}</span>
    <svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
      <path d="m4 6 4 4 4-4" stroke-linecap="round" stroke-linejoin="round" />
    </svg>
  </button>
  <ul bind:this={panel} id={id + '-options'} popover={native ? 'auto' : undefined} hidden={!native && !fallbackOpen}
    role="list" aria-labelledby={id} onbeforetoggle={() => panel.removeAttribute('data-positioned')} ontoggle={place}>
    {#each items as item (item.id)}
      <li>
        <button type="button" aria-current={value === item.id ? 'true' : undefined} onkeydown={move} onclick={() => {
          close()
          onSelect(item.id)
        }}>{item.label}</button>
      </li>
    {/each}
  </ul>
</div>
