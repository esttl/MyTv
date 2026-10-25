package app.mytv.player

data class Category(val id: String, val name: String)

data class Channel(val id: String, val name: String, val categoryId: String)

data class AccountInfo(
    val status: String,
    val expiry: String?,
    val activeConnections: String,
    val maxConnections: String
)

/** Datos en memoria compartidos entre pantallas. */
object Repo {
    var categories: List<Category> = emptyList()
    var channels: List<Channel> = emptyList()

    /** Lista que se está viendo ahora; se usa para cambiar de canal en el reproductor. */
    var playlist: List<Channel> = emptyList()

    fun clear() {
        categories = emptyList()
        channels = emptyList()
        playlist = emptyList()
    }
}
