package dev.lain.claudejb.session

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.PROJECT)
@State(name = "ClaudeCodeSessionHistory", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class LegacySessionHistory : PersistentStateComponent<LegacySessionHistory.State> {

    class State {
        @JvmField var openJson: String = ""
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(s: State) = XmlSerializerUtil.copyBean(s, state)

    fun openSessions(): List<String> = SessionHistory.decodeIds(state.openJson)

    companion object {
        fun getInstance(project: Project): LegacySessionHistory = project.service()
    }
}
