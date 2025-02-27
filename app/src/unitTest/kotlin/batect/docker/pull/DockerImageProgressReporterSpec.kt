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

package batect.docker.pull

import batect.testutils.createForEachTest
import batect.testutils.equalTo
import batect.testutils.given
import batect.testutils.on
import batect.testutils.runNullableForEachTest
import batect.utils.Json
import com.natpryce.hamkrest.absent
import com.natpryce.hamkrest.assertion.assertThat
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe

object DockerImageProgressReporterSpec : Spek({
    describe("a Docker image pull progress reporter") {
        val reporter by createForEachTest { DockerImageProgressReporter() }

        mapOf(
            "an empty event" to "{}",
            "an 'image pull starting' event" to """{"status":"Pulling from library/node","id":"10-stretch"}""",
            "a 'layer already exists' event" to """{"status":"Already exists","progressDetail":{},"id":"55cbf04beb70"}""",
            "a 'layer pull starting' event" to """{"status":"Pulling fs layer","progressDetail":{},"id":"d6cd23cd1a2c"}""",
            "a 'waiting to pull layer' event" to """{"status":"Waiting","progressDetail":{},"id":"4c60885e4f94"}""",
            "an 'image digest' event" to """{"status":"Digest: sha256:e6b798f4eeb4e6334d195cdeabc18d07dc5158aa88ad5d83670462852b431a71"}""",
            "a 'downloaded newer image' event" to """{"status":"Status: Downloaded newer image for node:10-stretch"}""",
            "an 'image up to date' event" to """{"status":"Status: Image is up to date for node:10-stretch"}"""
        ).forEach { (description, json) ->
            given(description) {
                on("processing the event") {
                    val progressUpdate by runNullableForEachTest { reporter.processRawProgressUpdate(json) }

                    it("does not return an update") {
                        assertThat(progressUpdate, absent())
                    }
                }
            }
        }

        given("a single 'layer downloading' event") {
            val json = """{"status":"Downloading","progressDetail":{"current":329,"total":4159},"id":"d6cd23cd1a2c"}"""

            on("processing the event") {
                val progressUpdate by runNullableForEachTest { reporter.processRawProgressUpdate(json) }

                it("returns an appropriate progress update") {
                    assertThat(progressUpdate, equalTo(DockerImageProgress("downloading", 329, 4159)))
                }
            }
        }

        given("a single 'layer extracting' event") {
            val json = """{"status":"Extracting","progressDetail":{"current":329,"total":4159},"id":"d6cd23cd1a2c"}"""

            on("processing the event") {
                val progressUpdate by runNullableForEachTest { reporter.processRawProgressUpdate(json) }

                it("returns an appropriate progress update") {
                    assertThat(progressUpdate, equalTo(DockerImageProgress("extracting", 329, 4159)))
                }
            }
        }

        given("a single 'verifying checksum' event without any previous events for that layer") {
            val json = """{"status":"Verifying Checksum","progressDetail":{},"id":"d6cd23cd1a2c"}"""

            on("processing the event") {
                val progressUpdate by runNullableForEachTest { reporter.processRawProgressUpdate(json) }

                it("returns an appropriate progress update") {
                    assertThat(progressUpdate, equalTo(DockerImageProgress("verifying checksum", 0, 0)))
                }
            }
        }

        given("a single 'layer downloading' event has been processed") {
            beforeEachTest {
                reporter.processRawProgressUpdate("""{"status":"Downloading","progressDetail":{"current":329,"total":4159},"id":"d6cd23cd1a2c"}""")
            }

            on("processing another 'layer downloading' event for the same layer") {
                val json = """{"status":"Downloading","progressDetail":{"current":900,"total":4159},"id":"d6cd23cd1a2c"}"""
                val progressUpdate by runNullableForEachTest { reporter.processRawProgressUpdate(json) }

                it("returns an appropriate progress update") {
                    assertThat(progressUpdate, equalTo(DockerImageProgress("downloading", 900, 4159)))
                }
            }

            on("processing another 'layer downloading' event for the same layer that does not result in updated information") {
                val json = """{"status":"Downloading","progressDetail":{"current":329,"total":4159},"id":"d6cd23cd1a2c"}"""
                val progressUpdate by runNullableForEachTest { reporter.processRawProgressUpdate(json) }

                it("does not return an update") {
                    assertThat(progressUpdate, absent())
                }
            }

            on("processing a 'verifying checksum' event for the same layer") {
                // Note that the 'status' field for a 'verifying checksum' event has the S capitalised, while
                // every other kind of event uses lowercase for later words in the description.
                val json = """{"status":"Verifying Checksum","progressDetail":{},"id":"d6cd23cd1a2c"}"""
                val progressUpdate by runNullableForEachTest { reporter.processRawProgressUpdate(json) }

                it("returns an appropriate progress update") {
                    assertThat(progressUpdate, equalTo(DockerImageProgress("verifying checksum", 0, 4159)))
                }
            }

            on("processing a 'download complete' event for the same layer") {
                val json = """{"status":"Download complete","progressDetail":{},"id":"d6cd23cd1a2c"}"""
                val progressUpdate by runNullableForEachTest { reporter.processRawProgressUpdate(json) }

                it("returns an appropriate progress update") {
                    assertThat(progressUpdate, equalTo(DockerImageProgress("download complete", 4159, 4159)))
                }
            }

            on("processing an 'extracting' event for the same layer") {
                val json = """{"status":"Extracting","progressDetail":{"current":1000,"total":4159},"id":"d6cd23cd1a2c"}"""
                val progressUpdate by runNullableForEachTest { reporter.processRawProgressUpdate(json) }

                it("returns an appropriate progress update") {
                    assertThat(progressUpdate, equalTo(DockerImageProgress("extracting", 1000, 4159)))
                }
            }

            on("processing a 'pull complete' event for the same layer") {
                val json = """{"status":"Pull complete","progressDetail":{},"id":"d6cd23cd1a2c"}"""
                val progressUpdate by runNullableForEachTest { reporter.processRawProgressUpdate(json) }

                it("returns an appropriate progress update") {
                    assertThat(progressUpdate, equalTo(DockerImageProgress("pull complete", 4159, 4159)))
                }
            }

            on("processing a 'layer downloading' event for another layer") {
                val json = """{"status":"Downloading","progressDetail":{"current":900,"total":7000},"id":"b59856e9f0ab"}"""
                val progressUpdate by runNullableForEachTest { reporter.processRawProgressUpdate(json) }

                it("returns a progress update combining the state of both layers") {
                    assertThat(progressUpdate, equalTo(DockerImageProgress("downloading", 329 + 900, 4159 + 7000)))
                }
            }

            given("a 'layer downloading' event for another layer has been processed") {
                beforeEachTest {
                    reporter.processRawProgressUpdate("""{"status":"Downloading","progressDetail":{"current":900,"total":7000},"id":"b59856e9f0ab"}""")
                }

                mapOf(
                    "verifying checksum" to """{"status":"Verifying Checksum","progressDetail":{},"id":"b59856e9f0ab"}""",
                    "download complete" to """{"status":"Download complete","progressDetail":{},"id":"b59856e9f0ab"}""",
                    "extracting" to """{"status":"Extracting","progressDetail":{"current":1000,"total":7000},"id":"b59856e9f0ab"}""",
                    "pull complete" to """{"status":"Pull complete","progressDetail":{},"id":"b59856e9f0ab"}"""
                ).forEach { (eventType, json) ->
                    on("processing a '$eventType' event for that other layer") {
                        val progressUpdate by runNullableForEachTest { reporter.processRawProgressUpdate(json) }

                        it("returns a progress update combining the state of both layers") {
                            assertThat(progressUpdate, equalTo(DockerImageProgress("downloading", 329 + 7000, 4159 + 7000)))
                        }
                    }
                }
            }

            given("a 'pull complete' event has been processed") {
                beforeEachTest {
                    reporter.processRawProgressUpdate("""{"status":"Pull complete","progressDetail":{},"id":"d6cd23cd1a2c"}""")
                }

                on("processing a 'layer downloading' event for another layer") {
                    val json = """{"status":"Downloading","progressDetail":{"current":900,"total":7000},"id":"b59856e9f0ab"}"""
                    val progressUpdate by runNullableForEachTest { reporter.processRawProgressUpdate(json) }

                    it("returns a progress update combining the state of both layers") {
                        assertThat(progressUpdate, equalTo(DockerImageProgress("downloading", 4159 + 900, 4159 + 7000)))
                    }
                }
            }
        }

        given("a single 'verifying checksum' event has been processed") {
            beforeEachTest {
                reporter.processRawProgressUpdate("""{"status":"Verifying Checksum","progressDetail":{},"id":"d6cd23cd1a2c"}""")
            }

            on("processing another 'verifying checksum' event for the same layer") {
                val json = """{"status":"Verifying Checksum","progressDetail":{},"id":"d6cd23cd1a2c"}"""
                val progressUpdate by runNullableForEachTest { reporter.processRawProgressUpdate(json) }

                it("does not return an update") {
                    assertThat(progressUpdate, absent())
                }
            }

            on("processing a 'layer downloading' event for the same layer") {
                val json = """{"status":"Downloading","progressDetail":{"current":900,"total":4159},"id":"d6cd23cd1a2c"}"""
                val progressUpdate by runNullableForEachTest { reporter.processRawProgressUpdate(json) }

                it("returns an appropriate progress update") {
                    assertThat(progressUpdate, equalTo(DockerImageProgress("downloading", 900, 4159)))
                }
            }
        }

        given("a single 'downloading' event that is not for a layer") {
            val json = """{"status":"Downloading","progressDetail":{"current":329,"total":4159}}"""

            on("processing the event") {
                val progressUpdate by runNullableForEachTest { reporter.processRawProgressUpdate(json) }

                it("returns an appropriate progress update") {
                    assertThat(progressUpdate, equalTo(DockerImageProgress("downloading", 329, 4159)))
                }
            }
        }

        given("a single 'downloading' event with an unknown number of total bytes") {
            val json = """{"status":"Downloading","progressDetail":{"current":329,"total":-1}}"""

            on("processing the event") {
                val progressUpdate by runNullableForEachTest { reporter.processRawProgressUpdate(json) }

                it("returns an appropriate progress update") {
                    assertThat(progressUpdate, equalTo(DockerImageProgress("downloading", 329, 0)))
                }
            }
        }
    }
})

private fun DockerImageProgressReporter.processRawProgressUpdate(json: String): DockerImageProgress? {
    val parsedJson = Json.parser.parseJson(json).jsonObject

    return this.processProgressUpdate(parsedJson)
}
