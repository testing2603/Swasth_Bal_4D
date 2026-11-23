package gov.up.swasthbal4d

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import gov.up.swasthbal4d.data.Repository
import gov.up.swasthbal4d.data.local.LocalDb
import gov.up.swasthbal4d.data.local.SessionStore
import gov.up.swasthbal4d.data.net.ApiClient
import gov.up.swasthbal4d.sync.SyncScheduler
import kotlinx.coroutines.runBlocking

class SwasthBalApp : Application() {
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        WorkManager.initialize(this, Configuration.Builder().build())
        SyncScheduler.createChannel(this)
        val session = SessionStore(this)
        runBlocking { session.load() } // tiny DataStore read, needed before the first screen
        repo = Repository(this, session, ApiClient { session.token }, LocalDb.create(this))
    }
}
