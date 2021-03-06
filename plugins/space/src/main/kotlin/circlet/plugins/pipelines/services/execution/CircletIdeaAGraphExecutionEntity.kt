package circlet.plugins.pipelines.services.execution

import circlet.pipelines.common.api.ExecutionStatus
import circlet.pipelines.config.api.ScriptAction
import circlet.pipelines.engine.api.storage.AGraphExecutionContext
import circlet.pipelines.engine.api.storage.AGraphExecutionEntity
import circlet.pipelines.engine.api.storage.AStepExecutionEntity

class CircletIdeaAGraphExecutionEntity(
  override val id: Long,
  override var startTime: Long,
  override var endTime: Long?,
  override var status: ExecutionStatus,
  override var executionMeta: ScriptAction,
  override val graphContext: AGraphExecutionContext?,
  val jobsList: MutableList<AStepExecutionEntity<*>>
) : AGraphExecutionEntity {

  override val steps: Sequence<AStepExecutionEntity<*>>
    get() = jobsList.asSequence()

  override fun equals(other: Any?): Boolean {
    if (other !is CircletIdeaAGraphExecutionEntity) return false
    return this.id == other.id
  }

  override fun hashCode(): Int {
    return this.id.hashCode()
  }

  override val problems = emptySet<Int>()
}
