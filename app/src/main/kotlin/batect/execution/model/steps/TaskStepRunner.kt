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

package batect.execution.model.steps

import batect.config.EnvironmentVariableExpression
import batect.config.EnvironmentVariableExpressionEvaluationException
import batect.docker.ContainerCreationFailedException
import batect.docker.ContainerHealthCheckException
import batect.docker.client.DockerClient
import batect.docker.DockerContainer
import batect.docker.DockerContainerCreationRequestFactory
import batect.docker.DockerContainerEnvironmentVariableProvider
import batect.docker.DockerException
import batect.docker.client.DockerImageBuildProgress
import batect.docker.client.HealthStatus
import batect.docker.ImageBuildFailedException
import batect.docker.ImagePullFailedException
import batect.docker.api.ContainerRemovalFailedException
import batect.docker.api.ContainerStopFailedException
import batect.docker.api.NetworkCreationFailedException
import batect.docker.api.NetworkDeletionFailedException
import batect.execution.CancellationContext
import batect.execution.RunAsCurrentUserConfigurationProvider
import batect.execution.RunOptions
import batect.execution.TaskStepRunContext
import batect.execution.model.events.ContainerBecameHealthyEvent
import batect.execution.model.events.ContainerCreatedEvent
import batect.execution.model.events.ContainerCreationFailedEvent
import batect.execution.model.events.ContainerDidNotBecomeHealthyEvent
import batect.execution.model.events.ContainerBecameReadyEvent
import batect.execution.model.events.ContainerRemovalFailedEvent
import batect.execution.model.events.ContainerRemovedEvent
import batect.execution.model.events.ContainerRunFailedEvent
import batect.execution.model.events.ContainerStartedEvent
import batect.execution.model.events.ContainerStopFailedEvent
import batect.execution.model.events.ContainerStoppedEvent
import batect.execution.model.events.ImageBuildFailedEvent
import batect.execution.model.events.ImageBuildProgressEvent
import batect.execution.model.events.ImageBuiltEvent
import batect.execution.model.events.ImagePullFailedEvent
import batect.execution.model.events.ImagePullProgressEvent
import batect.execution.model.events.ImagePulledEvent
import batect.execution.model.events.RunningContainerExitedEvent
import batect.execution.model.events.RunningSetupCommandEvent
import batect.execution.model.events.SetupCommandExecutionErrorEvent
import batect.execution.model.events.SetupCommandFailedEvent
import batect.execution.model.events.SetupCommandsCompletedEvent
import batect.execution.model.events.TaskEventSink
import batect.execution.model.events.TaskNetworkCreatedEvent
import batect.execution.model.events.TaskNetworkCreationFailedEvent
import batect.execution.model.events.TaskNetworkDeletedEvent
import batect.execution.model.events.TaskNetworkDeletionFailedEvent
import batect.execution.model.events.TemporaryDirectoryDeletedEvent
import batect.execution.model.events.TemporaryDirectoryDeletionFailedEvent
import batect.execution.model.events.TemporaryFileDeletedEvent
import batect.execution.model.events.TemporaryFileDeletionFailedEvent
import batect.os.SystemInfo
import batect.os.proxies.ProxyEnvironmentVariablesProvider
import batect.ui.containerio.ContainerIOStreamingOptions
import java.io.IOException
import java.nio.file.Files

class TaskStepRunner(
    private val dockerClient: DockerClient,
    private val proxyEnvironmentVariablesProvider: ProxyEnvironmentVariablesProvider,
    private val creationRequestFactory: DockerContainerCreationRequestFactory,
    private val environmentVariableProvider: DockerContainerEnvironmentVariableProvider,
    private val runAsCurrentUserConfigurationProvider: RunAsCurrentUserConfigurationProvider,
    private val systemInfo: SystemInfo,
    private val hostEnvironmentVariables: Map<String, String>
) {
    constructor(
        dockerClient: DockerClient,
        proxyEnvironmentVariablesProvider: ProxyEnvironmentVariablesProvider,
        creationRequestFactory: DockerContainerCreationRequestFactory,
        environmentVariableProvider: DockerContainerEnvironmentVariableProvider,
        runAsCurrentUserConfigurationProvider: RunAsCurrentUserConfigurationProvider,
        systemInfo: SystemInfo
    ) : this(dockerClient, proxyEnvironmentVariablesProvider, creationRequestFactory, environmentVariableProvider, runAsCurrentUserConfigurationProvider, systemInfo, System.getenv())

    fun run(step: TaskStep, context: TaskStepRunContext) {
        when (step) {
            is BuildImageStep -> handleBuildImageStep(step, context.eventSink, context.runOptions, context.cancellationContext)
            is PullImageStep -> handlePullImageStep(step, context.eventSink, context.cancellationContext)
            is CreateTaskNetworkStep -> handleCreateTaskNetworkStep(context.eventSink)
            is CreateContainerStep -> handleCreateContainerStep(step, context.eventSink, context.runOptions, context.ioStreamingOptions)
            is RunContainerStep -> handleRunContainerStep(step, context.eventSink, context.ioStreamingOptions, context.cancellationContext)
            is WaitForContainerToBecomeHealthyStep -> handleWaitForContainerToBecomeHealthyStep(step, context.eventSink, context.cancellationContext)
            is RunContainerSetupCommandsStep -> handleRunContainerSetupCommandsStep(step, context.eventSink, context.runOptions, context.cancellationContext)
            is StopContainerStep -> handleStopContainerStep(step, context.eventSink)
            is RemoveContainerStep -> handleRemoveContainerStep(step, context.eventSink)
            is DeleteTemporaryFileStep -> handleDeleteTemporaryFileStep(step, context.eventSink)
            is DeleteTemporaryDirectoryStep -> handleDeleteTemporaryDirectoryStep(step, context.eventSink)
            is DeleteTaskNetworkStep -> handleDeleteTaskNetworkStep(step, context.eventSink)
        }
    }

    private fun handleBuildImageStep(step: BuildImageStep, eventSink: TaskEventSink, runOptions: RunOptions, cancellationContext: CancellationContext) {
        try {
            val onStatusUpdate = { p: DockerImageBuildProgress ->
                eventSink.postEvent(ImageBuildProgressEvent(step.source, p))
            }

            val buildArgs = buildTimeProxyEnvironmentVariablesForOptions(runOptions) + substituteBuildArgs(step.source.buildArgs)
            val image = dockerClient.images.build(step.source.buildDirectory, buildArgs, step.source.dockerfilePath, step.imageTags, cancellationContext, onStatusUpdate)
            eventSink.postEvent(ImageBuiltEvent(step.source, image))
        } catch (e: ImageBuildFailedException) {
            val message = e.message ?: ""

            eventSink.postEvent(ImageBuildFailedEvent(step.source, message.replace("\n", systemInfo.lineSeparator)))
        }
    }

    private fun substituteBuildArgs(original: Map<String, EnvironmentVariableExpression>): Map<String, String> =
        original.mapValues { (name, value) -> evaluateBuildArgValue(name, value) }

    private fun evaluateBuildArgValue(name: String, expression: EnvironmentVariableExpression): String {
        try {
            return expression.evaluate(hostEnvironmentVariables)
        } catch (e: EnvironmentVariableExpressionEvaluationException) {
            throw ImageBuildFailedException("The value for the build arg '$name' cannot be evaluated: ${e.message}", e)
        }
    }

    private fun handlePullImageStep(step: PullImageStep, eventSink: TaskEventSink, cancellationContext: CancellationContext) {
        try {
            val image = dockerClient.images.pull(step.source.imageName, cancellationContext) { progressUpdate ->
                eventSink.postEvent(ImagePullProgressEvent(step.source, progressUpdate))
            }

            eventSink.postEvent(ImagePulledEvent(step.source, image))
        } catch (e: ImagePullFailedException) {
            eventSink.postEvent(ImagePullFailedEvent(step.source, e.message ?: ""))
        }
    }

    private fun handleCreateTaskNetworkStep(eventSink: TaskEventSink) {
        try {
            val network = dockerClient.networks.create()
            eventSink.postEvent(TaskNetworkCreatedEvent(network))
        } catch (e: NetworkCreationFailedException) {
            eventSink.postEvent(TaskNetworkCreationFailedEvent(e.outputFromDocker))
        }
    }

    private fun handleCreateContainerStep(step: CreateContainerStep, eventSink: TaskEventSink, runOptions: RunOptions, ioStreamingOptions: ContainerIOStreamingOptions) {
        try {
            val runAsCurrentUserConfiguration = runAsCurrentUserConfigurationProvider.generateConfiguration(step.container, eventSink)

            val creationRequest = creationRequestFactory.create(
                step.container,
                step.image,
                step.network,
                step.config,
                runAsCurrentUserConfiguration.volumeMounts,
                runOptions.propagateProxyEnvironmentVariables,
                runAsCurrentUserConfiguration.userAndGroup,
                ioStreamingOptions.terminalTypeForContainer(step.container),
                step.allContainersInNetwork
            )

            val dockerContainer = dockerClient.containers.create(creationRequest)
            eventSink.postEvent(ContainerCreatedEvent(step.container, dockerContainer))
        } catch (e: ContainerCreationFailedException) {
            eventSink.postEvent(ContainerCreationFailedEvent(step.container, e.message ?: ""))
        }
    }

    private fun handleRunContainerStep(step: RunContainerStep, eventSink: TaskEventSink, ioStreamingOptions: ContainerIOStreamingOptions, cancellationContext: CancellationContext) {
        try {
            val stdout = ioStreamingOptions.stdoutForContainer(step.container)
            val stdin = ioStreamingOptions.stdinForContainer(step.container)

            val result = dockerClient.containers.run(step.dockerContainer, stdout, stdin, cancellationContext, ioStreamingOptions.frameDimensions) {
                eventSink.postEvent(ContainerStartedEvent(step.container))
            }

            eventSink.postEvent(RunningContainerExitedEvent(step.container, result.exitCode))
        } catch (e: DockerException) {
            eventSink.postEvent(ContainerRunFailedEvent(step.container, e.message ?: ""))
        }
    }

    private fun handleWaitForContainerToBecomeHealthyStep(step: WaitForContainerToBecomeHealthyStep, eventSink: TaskEventSink, cancellationContext: CancellationContext) {
        try {
            val event = when (dockerClient.containers.waitForHealthStatus(step.dockerContainer, cancellationContext)) {
                HealthStatus.NoHealthCheck -> ContainerBecameHealthyEvent(step.container)
                HealthStatus.BecameHealthy -> ContainerBecameHealthyEvent(step.container)
                HealthStatus.BecameUnhealthy -> ContainerDidNotBecomeHealthyEvent(step.container, containerBecameUnhealthyMessage(step.dockerContainer))
                HealthStatus.Exited -> ContainerDidNotBecomeHealthyEvent(step.container, "The container exited before becoming healthy.")
            }

            eventSink.postEvent(event)
        } catch (e: ContainerHealthCheckException) {
            eventSink.postEvent(ContainerDidNotBecomeHealthyEvent(step.container, "Waiting for the container's health status failed: ${e.message}"))
        }
    }

    private fun handleRunContainerSetupCommandsStep(step: RunContainerSetupCommandsStep, eventSink: TaskEventSink, runOptions: RunOptions, cancellationContext: CancellationContext) {
        if (step.container.setupCommands.isEmpty()) {
            eventSink.postEvent(ContainerBecameReadyEvent(step.container))
            return
        }

        val environmentVariables = environmentVariableProvider.environmentVariablesFor(
            step.container,
            step.config,
            runOptions.propagateProxyEnvironmentVariables,
            null,
            step.allContainersInNetwork
        )

        val userAndGroup = runAsCurrentUserConfigurationProvider.determineUserAndGroup(step.container)

        step.container.setupCommands.forEachIndexed { index, command ->
            try {
                eventSink.postEvent(RunningSetupCommandEvent(step.container, command, index))

                val result = dockerClient.exec.run(
                    command.command,
                    step.dockerContainer,
                    environmentVariables,
                    step.container.privileged,
                    userAndGroup,
                    command.workingDirectory ?: step.container.workingDirectory,
                    cancellationContext
                )

                if (result.exitCode != 0) {
                    eventSink.postEvent(SetupCommandFailedEvent(step.container, command, result.exitCode, result.output))
                    return
                }
            } catch (e: DockerException) {
                eventSink.postEvent(SetupCommandExecutionErrorEvent(step.container, command, e.message ?: ""))
                return
            }
        }

        eventSink.postEvent(SetupCommandsCompletedEvent(step.container))
        eventSink.postEvent(ContainerBecameReadyEvent(step.container))
    }

    private fun containerBecameUnhealthyMessage(container: DockerContainer): String {
        val lastHealthCheckResult = dockerClient.containers.getLastHealthCheckResult(container)

        val message = when {
            lastHealthCheckResult.exitCode == 0 -> "The most recent health check exited with code 0, which usually indicates that the container became healthy just after the timeout period expired."
            lastHealthCheckResult.output.isEmpty() -> "The last health check exited with code ${lastHealthCheckResult.exitCode} but did not produce any output."
            else -> "The last health check exited with code ${lastHealthCheckResult.exitCode} and output:\n${lastHealthCheckResult.output.trim()}".replace("\n", systemInfo.lineSeparator)
        }

        return "The configured health check did not indicate that the container was healthy within the timeout period. $message"
    }

    private fun handleStopContainerStep(step: StopContainerStep, eventSink: TaskEventSink) {
        try {
            dockerClient.containers.stop(step.dockerContainer)
            eventSink.postEvent(ContainerStoppedEvent(step.container))
        } catch (e: ContainerStopFailedException) {
            eventSink.postEvent(ContainerStopFailedEvent(step.container, e.outputFromDocker))
        }
    }

    private fun handleRemoveContainerStep(step: RemoveContainerStep, eventSink: TaskEventSink) {
        try {
            dockerClient.containers.remove(step.dockerContainer)
            eventSink.postEvent(ContainerRemovedEvent(step.container))
        } catch (e: ContainerRemovalFailedException) {
            eventSink.postEvent(ContainerRemovalFailedEvent(step.container, e.outputFromDocker))
        }
    }

    private fun handleDeleteTemporaryFileStep(step: DeleteTemporaryFileStep, eventSink: TaskEventSink) {
        try {
            Files.delete(step.filePath)
            eventSink.postEvent(TemporaryFileDeletedEvent(step.filePath))
        } catch (e: IOException) {
            eventSink.postEvent(TemporaryFileDeletionFailedEvent(step.filePath, e.toString()))
        }
    }

    private fun handleDeleteTemporaryDirectoryStep(step: DeleteTemporaryDirectoryStep, eventSink: TaskEventSink) {
        try {
            Files.walk(step.directoryPath)
                .sorted { p1, p2 -> -p1.nameCount.compareTo(p2.nameCount) }
                .forEach { Files.delete(it) }

            eventSink.postEvent(TemporaryDirectoryDeletedEvent(step.directoryPath))
        } catch (e: IOException) {
            eventSink.postEvent(TemporaryDirectoryDeletionFailedEvent(step.directoryPath, e.toString()))
        }
    }

    private fun handleDeleteTaskNetworkStep(step: DeleteTaskNetworkStep, eventSink: TaskEventSink) {
        try {
            dockerClient.networks.delete(step.network)
            eventSink.postEvent(TaskNetworkDeletedEvent)
        } catch (e: NetworkDeletionFailedException) {
            eventSink.postEvent(TaskNetworkDeletionFailedEvent(e.outputFromDocker))
        }
    }

    private fun buildTimeProxyEnvironmentVariablesForOptions(runOptions: RunOptions): Map<String, String> = if (runOptions.propagateProxyEnvironmentVariables) {
        proxyEnvironmentVariablesProvider.getProxyEnvironmentVariables(emptySet())
    } else {
        emptyMap()
    }
}
