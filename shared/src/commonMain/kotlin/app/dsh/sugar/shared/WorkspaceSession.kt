package app.dsh.sugar.shared

/** The local DSH login exchange is complete before this address reaches the browser. */
class WorkspaceEndpoint private constructor(val origin: String) {
    val home: String get() = "$origin/"

    fun owns(url: String): Boolean {
        if (url.any { it <= ' ' || it == '\\' || it == '\u007f' }) return false
        return url == origin || url.startsWith(home) ||
            url.startsWith("$origin?") || url.startsWith("$origin#")
    }

    companion object {
        private val local = Regex("""http://127\.0\.0\.1:([1-9][0-9]{0,4})/?""")
        fun parse(address: String): WorkspaceEndpoint? {
            val match = local.matchEntire(address) ?: return null
            val port = match.groupValues[1].toInt()
            if (port !in 1..65535) return null
            return WorkspaceEndpoint("http://127.0.0.1:$port")
        }
    }
}

sealed interface LaunchState {
    data class Starting(val message: String = "") : LaunchState
    data class Opening(val endpoint: WorkspaceEndpoint) : LaunchState
    data class Ready(val endpoint: WorkspaceEndpoint) : LaunchState
    data class Failed(val message: String) : LaunchState
}

/** Called on the UI thread. A delayed callback from an earlier attempt cannot reopen its server. */
class WorkspaceSession {
    private var attempt = 0L
    var state: LaunchState = LaunchState.Starting()
        private set

    fun begin(): Long {
        state = LaunchState.Starting()
        return ++attempt
    }

    fun progress(id: Long, message: String) {
        if (id == attempt && state is LaunchState.Starting) state = LaunchState.Starting(message)
    }

    fun connected(id: Long, address: String): WorkspaceEndpoint? {
        if (id != attempt || state !is LaunchState.Starting) return null
        val endpoint = WorkspaceEndpoint.parse(address)
        state = if (endpoint == null) LaunchState.Failed("") else LaunchState.Opening(endpoint)
        return endpoint
    }

    fun pageVisible(id: Long, address: String) {
        val opening = state as? LaunchState.Opening ?: return
        if (id == attempt && opening.endpoint.owns(address)) state = LaunchState.Ready(opening.endpoint)
    }

    fun failed(id: Long, message: String) {
        if (id == attempt) state = LaunchState.Failed(message)
    }
}
