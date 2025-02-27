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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun Iterable<String>.toJsonArray() = JsonArray(this.map { JsonPrimitive(it) })

internal fun Map<String, String>.toJsonObject() = JsonObject(this.mapValues { JsonPrimitive(it.value) })

internal fun Map<String, String>.toDockerFormatJsonArray(): JsonArray = this
    .map { (key, value) -> "$key=$value" }
    .toJsonArray()
