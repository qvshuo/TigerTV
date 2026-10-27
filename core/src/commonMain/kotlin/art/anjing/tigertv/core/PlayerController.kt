package art.anjing.tigertv.core

/** Platform players implement these semantics; core never owns a UI surface. */
interface PlayerController {
    fun load(url: String)
    fun play()
    fun pause()
    fun seek(positionMillis: Long)
    fun setVolume(volume: Float)
    fun close()
}
