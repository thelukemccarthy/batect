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

package batect.docker

import batect.config.Container
import batect.config.EnvironmentVariableExpression
import batect.config.EnvironmentVariableExpressionEvaluationException
import batect.execution.ContainerRuntimeConfiguration
import batect.os.proxies.ProxyEnvironmentVariablesProvider
import batect.utils.mapToSet

class DockerContainerEnvironmentVariableProvider(
    private val proxyEnvironmentVariablesProvider: ProxyEnvironmentVariablesProvider,
    private val hostEnvironmentVariables: Map<String, String>
) {
    constructor(proxyEnvironmentVariablesProvider: ProxyEnvironmentVariablesProvider)
        : this(proxyEnvironmentVariablesProvider, System.getenv())

    fun environmentVariablesFor(
        container: Container,
        config: ContainerRuntimeConfiguration,
        propagateProxyEnvironmentVariables: Boolean,
        terminalType: String?,
        allContainersInNetwork: Set<Container>
    ): Map<String, String> =
        terminalEnvironmentVariablesFor(terminalType) +
            proxyEnvironmentVariables(propagateProxyEnvironmentVariables, allContainersInNetwork) +
            substituteEnvironmentVariables(container.environment + config.additionalEnvironmentVariables)

    private fun terminalEnvironmentVariablesFor(terminalType: String?): Map<String, String> = if (terminalType == null) {
        emptyMap()
    } else {
        mapOf("TERM" to terminalType)
    }

    private fun proxyEnvironmentVariables(propagateProxyEnvironmentVariables: Boolean, allContainersInNetwork: Set<Container>): Map<String, String> = if (propagateProxyEnvironmentVariables) {
        proxyEnvironmentVariablesProvider.getProxyEnvironmentVariables(allContainersInNetwork.mapToSet { it.name })
    } else {
        emptyMap()
    }

    private fun substituteEnvironmentVariables(original: Map<String, EnvironmentVariableExpression>): Map<String, String> =
        original.mapValues { (name, value) -> evaluateEnvironmentVariableValue(name, value) }

    private fun evaluateEnvironmentVariableValue(name: String, expression: EnvironmentVariableExpression): String {
        try {
            return expression.evaluate(hostEnvironmentVariables)
        } catch (e: EnvironmentVariableExpressionEvaluationException) {
            throw ContainerCreationFailedException("The value for the environment variable '$name' cannot be evaluated: ${e.message}", e)
        }
    }
}
