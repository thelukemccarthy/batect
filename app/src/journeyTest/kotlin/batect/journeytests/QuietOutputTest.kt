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

package batect.journeytests

import batect.journeytests.testutils.ApplicationRunner
import batect.journeytests.testutils.itCleansUpAllContainersItCreates
import batect.journeytests.testutils.itCleansUpAllNetworksItCreates
import batect.testutils.createForGroup
import batect.testutils.on
import batect.testutils.platformLineSeparator
import batect.testutils.runBeforeGroup
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.containsSubstring
import com.natpryce.hamkrest.equalTo
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe

object QuietOutputTest : Spek({
    describe("running a task in quiet output mode") {
        val runner by createForGroup { ApplicationRunner("task-with-prerequisite") }

        on("running that task") {
            val result by runBeforeGroup { runner.runApplication(listOf("--output=quiet", "do-stuff")) }

            it("prints the only the output from the task commands") {
                assertThat(result.output, containsSubstring("This is some output from the build task\r\n${platformLineSeparator}This is some output from the main task\r\n"))
            }

            it("returns the exit code from that task") {
                assertThat(result.exitCode, equalTo(123))
            }

            itCleansUpAllContainersItCreates { result }
            itCleansUpAllNetworksItCreates { result }
        }
    }
})
