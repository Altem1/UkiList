package knf.kuma.backup.framework

import knf.kuma.backup.objects.BackupObject

// Dropbox eliminado: el servicio queda inerte para no romper Backups.Type.DROPBOX.
class DropBoxService : BackupService() {
    override fun start() {}
    override val isLoggedIn: Boolean = false
    override fun logIn(token: String?): Boolean = false
    override fun logOut() {}
    override suspend fun search(id: String, manual: Boolean): BackupObject<*>? = null
    override suspend fun backup(backupObject: BackupObject<*>, id: String): BackupObject<*>? = null
}
