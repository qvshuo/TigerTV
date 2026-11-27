import '@videojs/html/video/skin'
import '@videojs/html/ui/playback-rate-button'
import '@videojs/html/ui/playback-rate-radio-group'
import { VideoSkinElement } from '@videojs/html/video'
import markup from './player-skin.html?raw'

export class TigerVideoSkin extends VideoSkinElement {
  static override readonly template = document.createElement('template')
  static {
    this.template.innerHTML = markup
  }
}

declare global {
  interface HTMLElementTagNameMap { 'tiger-video-skin': TigerVideoSkin }
}

customElements.define('tiger-video-skin', TigerVideoSkin)
