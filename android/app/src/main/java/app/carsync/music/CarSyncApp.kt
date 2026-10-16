package app.carsync.music
import android.app.Application
import androidx.room.Room
class CarSyncApp : Application() {
    lateinit var sync: SyncCoordinator; private set
    override fun onCreate() { super.onCreate(); sync = SyncCoordinator(this, Room.databaseBuilder(this, LibraryDb::class.java, "library.db").addMigrations(LibraryDb.MIGRATION_1_2).build().dao()) }
}
