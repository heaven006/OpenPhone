package org.openphone.assistant;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class OpenPhoneQuickSettingsTileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        Intent activity = new Intent(this, MainActivity.class);
        activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityAndCollapse(activity);
    }
}
