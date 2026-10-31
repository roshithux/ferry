package app.ferry

import android.app.Activity

/**
 * Android only lets the focused app read the clipboard, so the tile and the notification
 * open this invisible activity, read the clipboard once it has focus, and close.
 */
class ClipboardActivity : Activity() {
    private var handled = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        Ferry.startService(this)
        Ferry.sendClipboard(this)
        finish()
    }
}
