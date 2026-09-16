package dev.lbento.thorsidepad.pad

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: one tap shows or hides the pad. */
class PadTileService : TileService() {
    override fun onStartListening() { refresh() }

    override fun onClick() {
        OverlayService.send(this, OverlayService.ACTION_TOGGLE)
        // The service updates state asynchronously; reflect the intended state immediately.
        qsTile?.let { t -> t.state = if (OverlayService.visible) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE; t.updateTile() }
    }

    private fun refresh() {
        qsTile?.let { t ->
            t.state = if (OverlayService.visible) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            t.updateTile()
        }
    }
}
