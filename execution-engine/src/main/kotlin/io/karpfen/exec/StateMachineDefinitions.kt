/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.karpfen.io.karpfen.exec

import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.instance.DataObject
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.states.StateMachine

/**
 * The state machines one engine knows, as an environment holds them when it is activated.
 *
 * There are two kinds. An attached state machine belongs to the object whose key the run configuration names, the
 * `attachedTo` of its upload. An attachable state machine is a definition: it is kept under its name and gives
 * instances to objects whose type `behaves after` it, to objects that an `ATTACH` clause names it for and to objects
 * an `EXCHANGE` gives it to. The value is immutable and safe to read from any thread; the parsed state machines are
 * read only, so the instances of one definition share them while every mutable part of an instance is built anew for
 * it (see [StateMachineInstanceFactory]).
 *
 * @property attached the attached state machines by the key of their object, in upload order.
 * @property definitions the attachable state machines by their name.
 */
class StateMachineDefinitions(
    attached: Map<String, StateMachine>,
    definitions: Map<String, StateMachine> = emptyMap()
) {

    val attached: Map<String, StateMachine> = LinkedHashMap(attached)

    val definitions: Map<String, StateMachine> = LinkedHashMap(definitions)

    /** The attachable state machine named [name], or null when there is none. */
    fun named(name: String): StateMachine? = definitions[name]

    /**
     * The attachable state machine named [name] after checking that it gives an instance to an object of the type
     * [typeName]. These are the checks of an `ATTACH` clause and of `EXCHANGE` at run time.
     *
     * @throws IllegalArgumentException with `No attachable state machine is named '<name>'` when there is no such
     *   definition, or `The state machine '<name>' is attachable to 'A', 'B', not to '<type>'` when its types do not
     *   list [typeName].
     */
    fun requireAttachable(name: String, typeName: String): StateMachine {
        val definition = named(name) ?: throw IllegalArgumentException("No attachable state machine is named '$name'")
        require(typeName in definition.types) {
            "The state machine '$name' is attachable to ${quotedList(definition.types)}, not to '$typeName'"
        }
        return definition
    }

    /**
     * The types of [metamodel] whose `behaves after` this set cannot serve: the name of no definition, or a definition
     * that does not list the type. An engine refuses to be built while there is one, since an object of such a type
     * could not get its instance at the start or when a rule creates it.
     */
    fun behavesAfterProblems(metamodel: Metamodel): BehavesAfterProblems {
        val missing = mutableListOf<String>()
        val mismatched = mutableListOf<String>()
        for (type in metamodel.types) {
            val name = type.behavesAfter?.stateMachineName ?: continue
            val definition = named(name)
            when {
                definition == null -> missing += "'${type.name}' behaves after '$name'"
                type.name !in definition.types ->
                    mismatched += "'${type.name}' behaves after '$name', which is attachable to ${quotedList(definition.types)}"
            }
        }
        return BehavesAfterProblems(missing, mismatched)
    }

    /**
     * The instances a start builds over [rootObjects], in the order of the state machines in a tick: first the attached
     * state machines in upload order, then one instance for every object
     * whose type `behaves after` a definition and that has no attached state machine, in the depth-first order of the
     * objects of the model. An attached state machine therefore wins over the `behaves after` of the type of its object.
     *
     * An attached state machine whose object is not in the model is left out when [skipMissingObjects] is true, as for the
     * instances a start would build that are shown before it; otherwise it is an error.
     *
     * @throws IllegalStateException when an attached state machine has no object and [skipMissingObjects] is false, or
     *   when a type behaves after a definition this set does not hold or that does not list
     *   it; [behavesAfterProblems] rules that out before an engine runs.
     */
    fun initialInstances(rootObjects: MutableList<DataObject>, skipMissingObjects: Boolean = false): List<InstanceSpec> {
        val objects = Model.collectAllObjects(rootObjects).filter { it.id.isNotEmpty() }
        val present = objects.map { it.id }.toSet()
        val specs = mutableListOf<InstanceSpec>()
        for ((objectId, stateMachine) in attached) {
            if (objectId in present) {
                specs += InstanceSpec(objectId, null, stateMachine)
            } else {
                check(skipMissingObjects) { "No object '$objectId' in the model for its attached state machine" }
            }
        }
        for (obj in objects) {
            if (obj.id in attached) continue
            behavesAfterSpec(obj)?.let { specs += it }
        }
        return specs
    }

    /**
     * The instance an object gets for the `behaves after` of its type, or null when its type has none.
     *
     * @throws IllegalStateException when the definition is missing or does not list the type of [obj].
     */
    fun behavesAfterSpec(obj: DataObject): InstanceSpec? {
        val name = obj.ofType.behavesAfter?.stateMachineName ?: return null
        val definition = try {
            requireAttachable(name, obj.ofType.name)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("The type '${obj.ofType.name}' of '${obj.id}' behaves after '$name': ${e.message}")
        }
        return InstanceSpec(obj.id, name, definition)
    }

    private fun quotedList(types: List<String>): String = types.joinToString(", ") { "'$it'" }
}

/**
 * The types of a metamodel whose `behaves after` a [StateMachineDefinitions] cannot serve, each as a phrase such as
 * `'Bulb' behaves after 'glowing'`.
 *
 * @property missing types that behave after a name no definition has.
 * @property mismatched types that behave after a definition that does not list them, with the types it lists.
 */
data class BehavesAfterProblems(val missing: List<String>, val mismatched: List<String>) {

    /** True when every `behaves after` of the metamodel can be served. */
    val isEmpty: Boolean
        get() = missing.isEmpty() && mismatched.isEmpty()
}

/**
 * What one instance of a state machine is made of: the object it runs on and the state machine it follows.
 *
 * @property objectId the key of the object of the instance; one object has at most one instance at a time.
 * @property definitionName the name of the attachable state machine, or null for the state machine attached to the
 *   object by the run configuration.
 * @property stateMachine the parsed state machine, shared read only by every instance of it.
 */
data class InstanceSpec(val objectId: String, val definitionName: String?, val stateMachine: StateMachine)
