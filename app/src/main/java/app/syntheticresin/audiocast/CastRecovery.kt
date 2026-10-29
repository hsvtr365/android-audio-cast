package app.syntheticresin.audiocast

/** A brief PLAYING pulse must not hide repeated buffering. All times are monotonic. */
class CastRecovery {
    private var unstableSince = -1L
    private var playingSince = -1L
    private var lastRetry = -1L

    fun reset() { unstableSince = -1; playingSince = -1; lastRetry = -1 }

    fun shouldRetry(now: Long, playing: Boolean, buffering: Boolean): Boolean {
        if (playing) {
            if (playingSince < 0) playingSince = now
            if (now - playingSince >= 20000) reset()
            return false
        }
        playingSince = -1
        if (!buffering) { unstableSince = -1; return false }
        if (unstableSince < 0) unstableSince = now
        if (now - unstableSince < 12000 || (lastRetry >= 0 && now - lastRetry < 20000)) return false
        lastRetry = now; unstableSince = now
        return true
    }
}
