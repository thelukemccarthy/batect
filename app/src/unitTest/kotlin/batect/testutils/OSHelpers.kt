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

package batect.testutils

import jnr.ffi.Platform
import org.spekframework.spek2.dsl.GroupBody

fun GroupBody.onlyOn(operatingSystems: Set<Platform.OS>, action: GroupBody.() -> Unit) {
    if (Platform.getNativePlatform().os in operatingSystems) {
        action(this)
    }
}

val platformLineSeparator = System.getProperty("line.separator")

fun String.withPlatformSpecificLineSeparator() = this.replace("\n", platformLineSeparator)
