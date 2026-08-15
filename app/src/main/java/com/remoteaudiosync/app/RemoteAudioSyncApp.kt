package com.remoteaudiosync.app

import android.app.Application
import com.remoteaudiosync.sync.SyncoConnection

class RemoteAudioSyncApp : Application() {

    val syncoConnection: SyncoConnection by lazy {
        SyncoConnection(this).also { it.start() }
    }
}
