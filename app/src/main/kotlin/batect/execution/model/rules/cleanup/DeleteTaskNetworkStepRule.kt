/*
   Copyright 2017-2019 Charles Korn.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package batect.execution.model.rules.cleanup

import batect.config.Container
import batect.docker.DockerNetwork
import batect.execution.model.events.ContainerRemovedEvent
import batect.execution.model.events.TaskEvent
import batect.execution.model.rules.TaskStepRuleEvaluationResult
import batect.execution.model.steps.DeleteTaskNetworkStep
import batect.os.OperatingSystem
import batect.utils.mapToSet

data class DeleteTaskNetworkStepRule(val network: DockerNetwork, val containersThatMustBeRemovedFirst: Set<Container>) : CleanupTaskStepRule() {
    override fun evaluate(pastEvents: Set<TaskEvent>): TaskStepRuleEvaluationResult {
        val removedContainers = pastEvents
            .filterIsInstance<ContainerRemovedEvent>()
            .mapToSet { it.container }

        if (removedContainers.containsAll(containersThatMustBeRemovedFirst)) {
            return TaskStepRuleEvaluationResult.Ready(DeleteTaskNetworkStep(network))
        }

        return TaskStepRuleEvaluationResult.NotReady
    }

    override fun getManualCleanupInstructionForOperatingSystem(operatingSystem: OperatingSystem): String? = "docker network rm ${network.id}"
    override val manualCleanupSortOrder: ManualCleanupSortOrder = ManualCleanupSortOrder.DeleteTaskNetwork
    override fun toString() = "${this::class.simpleName}(network: '${network.id}', containers that must be removed first: ${containersThatMustBeRemovedFirst.map { "'${it.name}'" }})"
}
