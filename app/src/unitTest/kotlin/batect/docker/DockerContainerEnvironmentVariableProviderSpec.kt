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
import batect.config.LiteralValue
import batect.config.ReferenceValue
import batect.execution.ContainerRuntimeConfiguration
import batect.os.proxies.ProxyEnvironmentVariablesProvider
import batect.testutils.given
import batect.testutils.imageSourceDoesNotMatter
import batect.testutils.isEmptyMap
import batect.testutils.on
import batect.testutils.withMessage
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.throws
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe

object DockerContainerEnvironmentVariableProviderSpec : Spek({
    describe("a Docker container environment variable provider") {
        val allContainersInNetwork = setOf(
            Container("container-1", imageSourceDoesNotMatter()),
            Container("container-2", imageSourceDoesNotMatter())
        )

        given("the console's type is not provided") {
            val terminalType = null as String?

            given("propagating proxy environment variables is disabled") {
                val hostEnvironmentVariables = emptyMap<String, String>()
                val propagateProxyEnvironmentVariables = false
                val proxyEnvironmentVariablesProvider = mock<ProxyEnvironmentVariablesProvider> {
                    on { getProxyEnvironmentVariables(setOf("container-1", "container-2")) } doReturn mapOf("SOME_PROXY_VAR" to "this should not be used")
                }

                val provider = DockerContainerEnvironmentVariableProvider(proxyEnvironmentVariablesProvider, hostEnvironmentVariables)

                given("there are no additional environment variables") {
                    val container = Container(
                        "some-container",
                        imageSourceDoesNotMatter(),
                        environment = mapOf("SOME_VAR" to LiteralValue("SOME_VALUE"))
                    )

                    val additionalEnvironmentVariables = emptyMap<String, EnvironmentVariableExpression>()
                    val config = configWithAdditionalEnvironmentVariables(additionalEnvironmentVariables)
                    val environmentVariables = provider.environmentVariablesFor(container, config, propagateProxyEnvironmentVariables, terminalType, allContainersInNetwork)

                    on("getting environment variables for the container") {
                        it("returns the environment variables from the container") {
                            assertThat(environmentVariables, equalTo(mapOf("SOME_VAR" to "SOME_VALUE")))
                        }
                    }
                }

                given("there are additional environment variables") {
                    val additionalEnvironmentVariables = mapOf(
                        "SOME_HOST_VAR" to LiteralValue("SOME_HOST_VALUE")
                    )

                    val config = configWithAdditionalEnvironmentVariables(additionalEnvironmentVariables)

                    given("none of them conflict with environment variables on the container") {
                        val container = Container(
                            "some-container",
                            imageSourceDoesNotMatter(),
                            environment = mapOf("SOME_VAR" to LiteralValue("SOME_VALUE"))
                        )

                        on("getting environment variables for the container") {
                            val environmentVariables = provider.environmentVariablesFor(container, config, propagateProxyEnvironmentVariables, terminalType, allContainersInNetwork)

                            it("returns the environment variables from the container and from the additional environment variables") {
                                assertThat(
                                    environmentVariables, equalTo(
                                        mapOf(
                                            "SOME_VAR" to "SOME_VALUE",
                                            "SOME_HOST_VAR" to "SOME_HOST_VALUE"
                                        )
                                    )
                                )
                            }
                        }
                    }

                    given("one of them conflicts with environment variables on the container") {
                        val container = Container(
                            "some-container",
                            imageSourceDoesNotMatter(),
                            environment = mapOf("SOME_HOST_VAR" to LiteralValue("SOME_CONTAINER_VALUE"))
                        )

                        on("getting environment variables for the container") {
                            val environmentVariables = provider.environmentVariablesFor(container, config, propagateProxyEnvironmentVariables, terminalType, allContainersInNetwork)

                            it("returns the environment variables from the container and from the additional environment variables, with the additional environment variables taking precedence") {
                                assertThat(
                                    environmentVariables, equalTo(
                                        mapOf(
                                            "SOME_HOST_VAR" to "SOME_HOST_VALUE"
                                        )
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        given("the console's type is provided") {
            val terminalType = "some-term"
            val proxyEnvironmentVariablesProvider = mock<ProxyEnvironmentVariablesProvider>()
            val hostEnvironmentVariables = emptyMap<String, String>()
            val provider = DockerContainerEnvironmentVariableProvider(proxyEnvironmentVariablesProvider, hostEnvironmentVariables)
            val propagateProxyEnvironmentVariables = false

            given("a container with no override for the TERM environment variable") {
                val container = Container(
                    "some-container",
                    imageSourceDoesNotMatter(),
                    environment = mapOf("SOME_VAR" to LiteralValue("SOME_VALUE"))
                )

                val additionalEnvironmentVariables = emptyMap<String, EnvironmentVariableExpression>()

                on("getting environment variables for the container") {
                    val config = configWithAdditionalEnvironmentVariables(additionalEnvironmentVariables)
                    val environmentVariables = provider.environmentVariablesFor(container, config, propagateProxyEnvironmentVariables, terminalType, allContainersInNetwork)

                    it("returns the environment variables from the container and the TERM environment variable from the host") {
                        assertThat(environmentVariables, equalTo(mapOf(
                            "SOME_VAR" to "SOME_VALUE",
                            "TERM" to "some-term"
                        ))
                        )
                    }
                }
            }

            given("a container with an override for the TERM environment variable") {
                val container = Container(
                    "some-container",
                    imageSourceDoesNotMatter(),
                    environment = mapOf(
                        "SOME_VAR" to LiteralValue("SOME_VALUE"),
                        "TERM" to LiteralValue("some-other-term")
                    )
                )

                val additionalEnvironmentVariables = emptyMap<String, EnvironmentVariableExpression>()

                on("getting environment variables for the container") {
                    val config = configWithAdditionalEnvironmentVariables(additionalEnvironmentVariables)
                    val environmentVariables = provider.environmentVariablesFor(container, config, propagateProxyEnvironmentVariables, terminalType, allContainersInNetwork)

                    it("returns the environment variables from the container and the TERM environment variable from the container") {
                        assertThat(environmentVariables, equalTo(mapOf(
                            "SOME_VAR" to "SOME_VALUE",
                            "TERM" to "some-other-term"
                        ))
                        )
                    }
                }
            }

            given("some additional environment variables with an override for the TERM environment variable") {
                val container = Container(
                    "some-container",
                    imageSourceDoesNotMatter(),
                    environment = mapOf(
                        "SOME_VAR" to LiteralValue("SOME_VALUE")
                    )
                )

                val additionalEnvironmentVariables = mapOf("TERM" to LiteralValue("some-additional-term"))

                on("getting environment variables for the container") {
                    val config = configWithAdditionalEnvironmentVariables(additionalEnvironmentVariables)
                    val environmentVariables = provider.environmentVariablesFor(container, config, propagateProxyEnvironmentVariables, terminalType, allContainersInNetwork)

                    it("returns the environment variables from the container and the TERM environment variable from the additional environment variables") {
                        assertThat(environmentVariables, equalTo(mapOf(
                            "SOME_VAR" to "SOME_VALUE",
                            "TERM" to "some-additional-term"
                        ))
                        )
                    }
                }
            }

            given("both the container and the additional environment variables have an override for the TERM environment variable") {
                val container = Container(
                    "some-container",
                    imageSourceDoesNotMatter(),
                    environment = mapOf(
                        "SOME_VAR" to LiteralValue("SOME_VALUE"),
                        "TERM" to LiteralValue("some-container-term")
                    )
                )

                val additionalEnvironmentVariables = mapOf("TERM" to LiteralValue("some-additional-term"))

                on("getting environment variables for the container") {
                    val config = configWithAdditionalEnvironmentVariables(additionalEnvironmentVariables)
                    val environmentVariables = provider.environmentVariablesFor(container, config, propagateProxyEnvironmentVariables, terminalType, allContainersInNetwork)

                    it("returns the environment variables from the container and the TERM environment variable from the additional environment variables") {
                        assertThat(environmentVariables, equalTo(mapOf(
                            "SOME_VAR" to "SOME_VALUE",
                            "TERM" to "some-additional-term"
                        )))
                    }
                }
            }
        }

        given("there are references to host environment variables") {
            val terminalType = null as String?
            val proxyEnvironmentVariablesProvider = mock<ProxyEnvironmentVariablesProvider>()
            val hostEnvironmentVariables = mapOf("SOME_HOST_VARIABLE" to "SOME_HOST_VALUE")
            val provider = DockerContainerEnvironmentVariableProvider(proxyEnvironmentVariablesProvider, hostEnvironmentVariables)
            val propagateProxyEnvironmentVariables = false

            given("and those references are on the container") {
                val additionalEnvironmentVariables = emptyMap<String, EnvironmentVariableExpression>()

                given("and the reference is valid") {
                    val container = Container(
                        "some-container",
                        imageSourceDoesNotMatter(),
                        environment = mapOf("SOME_VAR" to ReferenceValue("SOME_HOST_VARIABLE"))
                    )

                    on("getting environment variables for the container") {
                        val config = configWithAdditionalEnvironmentVariables(additionalEnvironmentVariables)
                        val environmentVariables = provider.environmentVariablesFor(container, config, propagateProxyEnvironmentVariables, terminalType, allContainersInNetwork)

                        it("returns the environment variables' values from the host") {
                            assertThat(environmentVariables, equalTo(mapOf(
                                "SOME_VAR" to "SOME_HOST_VALUE"
                            )))
                        }
                    }
                }

                given("and the reference is to an environment variable that does not exist on the host") {
                    val container = Container(
                        "some-container",
                        imageSourceDoesNotMatter(),
                        environment = mapOf("SOME_VAR" to ReferenceValue("SOME_HOST_VARIABLE_THAT_ISNT_DEFINED"))
                    )

                    on("getting environment variables for the container") {
                        val config = configWithAdditionalEnvironmentVariables(additionalEnvironmentVariables)

                        it("throws an appropriate exception") {
                            assertThat({ provider.environmentVariablesFor(container, config, propagateProxyEnvironmentVariables, terminalType, allContainersInNetwork) },
                                throws<ContainerCreationFailedException>(withMessage("The value for the environment variable 'SOME_VAR' cannot be evaluated: The host environment variable 'SOME_HOST_VARIABLE_THAT_ISNT_DEFINED' is not set, and no default value has been provided."))
                            )
                        }
                    }
                }
            }

            given("and those references are in the additional environment variables") {
                given("and the reference is valid") {
                    val container = Container(
                        "some-container",
                        imageSourceDoesNotMatter()
                    )

                    val additionalEnvironmentVariables = mapOf("SOME_VAR" to ReferenceValue("SOME_HOST_VARIABLE"))

                    on("getting environment variables for the container") {
                        val config = configWithAdditionalEnvironmentVariables(additionalEnvironmentVariables)
                        val environmentVariables = provider.environmentVariablesFor(container, config, propagateProxyEnvironmentVariables, terminalType, allContainersInNetwork)

                        it("returns the environment variables' values from the host") {
                            assertThat(environmentVariables, equalTo(mapOf(
                                "SOME_VAR" to "SOME_HOST_VALUE"
                            ))
                            )
                        }
                    }
                }

                given("and the references is to an environment variable that does not exist on the host") {
                    val container = Container(
                        "some-container",
                        imageSourceDoesNotMatter()
                    )

                    val additionalEnvironmentVariables = mapOf("SOME_VAR" to ReferenceValue("SOME_HOST_VARIABLE_THAT_ISNT_DEFINED"))

                    on("getting environment variables for the container") {
                        val config = configWithAdditionalEnvironmentVariables(additionalEnvironmentVariables)

                        it("throws an appropriate exception") {
                            assertThat({ provider.environmentVariablesFor(container, config, propagateProxyEnvironmentVariables, terminalType, allContainersInNetwork) },
                                throws<ContainerCreationFailedException>(withMessage("The value for the environment variable 'SOME_VAR' cannot be evaluated: The host environment variable 'SOME_HOST_VARIABLE_THAT_ISNT_DEFINED' is not set, and no default value has been provided."))
                            )
                        }
                    }
                }

                given("and the reference overrides a container-level environment variable that does not exist on the host") {
                    val container = Container(
                        "some-container",
                        imageSourceDoesNotMatter(),
                        environment = mapOf("SOME_VAR" to ReferenceValue("SOME_HOST_VARIABLE_THAT_ISNT_DEFINED"))
                    )

                    val additionalEnvironmentVariables = mapOf("SOME_VAR" to ReferenceValue("SOME_HOST_VARIABLE"))

                    on("getting environment variables for the container") {
                        val config = configWithAdditionalEnvironmentVariables(additionalEnvironmentVariables)
                        val environmentVariables = provider.environmentVariablesFor(container, config, propagateProxyEnvironmentVariables, terminalType, allContainersInNetwork)

                        it("returns the environment variables' values from the host and does not throw an exception") {
                            assertThat(environmentVariables, equalTo(mapOf(
                                "SOME_VAR" to "SOME_HOST_VALUE"
                            ))
                            )
                        }
                    }
                }
            }
        }

        given("there are proxy environment variables present on the host") {
            val terminalType = null as String?
            val hostEnvironmentVariables = emptyMap<String, String>()
            val proxyEnvironmentVariablesProvider = mock<ProxyEnvironmentVariablesProvider> {
                on { getProxyEnvironmentVariables(setOf("container-1", "container-2")) } doReturn mapOf(
                    "HTTP_PROXY" to "http://some-proxy",
                    "NO_PROXY" to "dont-proxy-this"
                )
            }

            val provider = DockerContainerEnvironmentVariableProvider(proxyEnvironmentVariablesProvider, hostEnvironmentVariables)

            given("propagating proxy environment variables is enabled") {
                val propagateProxyEnvironmentVariables = true

                given("neither the container nor the additional environment variables override the proxy settings") {
                    val container = Container(
                        "some-container",
                        imageSourceDoesNotMatter()
                    )

                    val additionalEnvironmentVariables = emptyMap<String, EnvironmentVariableExpression>()

                    on("getting environment variables for the container") {
                        val config = configWithAdditionalEnvironmentVariables(additionalEnvironmentVariables)
                        val environmentVariables = provider.environmentVariablesFor(container, config, propagateProxyEnvironmentVariables, terminalType, allContainersInNetwork)

                        it("returns the proxy environment variables from the host") {
                            assertThat(environmentVariables, equalTo(mapOf(
                                "HTTP_PROXY" to "http://some-proxy",
                                "NO_PROXY" to "dont-proxy-this"
                            ))
                            )
                        }
                    }
                }

                given("the container overrides the proxy settings") {
                    val container = Container(
                        "some-container",
                        imageSourceDoesNotMatter(),
                        environment = mapOf(
                            "HTTP_PROXY" to LiteralValue("http://some-other-proxy")
                        )
                    )

                    val additionalEnvironmentVariables = emptyMap<String, EnvironmentVariableExpression>()

                    on("getting environment variables for the container") {
                        val config = configWithAdditionalEnvironmentVariables(additionalEnvironmentVariables)
                        val environmentVariables = provider.environmentVariablesFor(container, config, propagateProxyEnvironmentVariables, terminalType, allContainersInNetwork)

                        it("returns the proxy environment variables from the host, with overrides from the container") {
                            assertThat(environmentVariables, equalTo(mapOf(
                                "HTTP_PROXY" to "http://some-other-proxy",
                                "NO_PROXY" to "dont-proxy-this"
                            ))
                            )
                        }
                    }
                }

                given("the additional environment variables override the proxy settings") {
                    val container = Container(
                        "some-container",
                        imageSourceDoesNotMatter(),
                        environment = mapOf(
                            "HTTP_PROXY" to LiteralValue("http://some-other-proxy")
                        )
                    )

                    val additionalEnvironmentVariables = mapOf(
                        "HTTP_PROXY" to LiteralValue("http://some-additional-proxy")
                    )

                    on("getting environment variables for the container") {
                        val config = configWithAdditionalEnvironmentVariables(additionalEnvironmentVariables)
                        val environmentVariables = provider.environmentVariablesFor(container, config, propagateProxyEnvironmentVariables, terminalType, allContainersInNetwork)

                        it("returns the proxy environment variables from the host, with overrides from the container and additional environment variables") {
                            assertThat(environmentVariables, equalTo(mapOf(
                                "HTTP_PROXY" to "http://some-additional-proxy",
                                "NO_PROXY" to "dont-proxy-this"
                            ))
                            )
                        }
                    }
                }
            }

            given("propagating proxy environment variables is disabled") {
                val propagateProxyEnvironmentVariables = false

                on("getting environment variables for the container") {
                    val container = Container(
                        "some-container",
                        imageSourceDoesNotMatter()
                    )

                    val additionalEnvironmentVariables = emptyMap<String, EnvironmentVariableExpression>()
                    val config = configWithAdditionalEnvironmentVariables(additionalEnvironmentVariables)
                    val environmentVariables = provider.environmentVariablesFor(container, config, propagateProxyEnvironmentVariables, terminalType, allContainersInNetwork)

                    it("does not propagate the proxy environment variables") {
                        assertThat(environmentVariables, isEmptyMap())
                    }
                }
            }
        }
    }
})

private fun configWithAdditionalEnvironmentVariables(additionalEnvironmentVariables: Map<String, EnvironmentVariableExpression>) =
    ContainerRuntimeConfiguration(null, null, null, additionalEnvironmentVariables, emptySet())
