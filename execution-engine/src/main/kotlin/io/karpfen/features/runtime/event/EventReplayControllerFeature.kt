package io.karpfen.io.karpfen.features.runtime.event

import com.google.auto.service.AutoService
import io.karpfen.io.karpfen.features.DefaultFeature
import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.FeatureProvider
import kotlin.reflect.KClass

class EventReplayControllerFeature(
    private val recordingFeature: EventRecordingFeature,
    private val playbackFeature: EventInjectionFeature
): DefaultFeature() {

    override fun onMessage(message: String): String {
        return when (message) {
            "initialize" -> {
                recordingFeature.onMessage("stop")
                playbackFeature.onMessage("stop")
                val returnMessage = playbackFeature.onMessage("upload:${recordingFeature.onMessage("get")}")
                if (returnMessage == "successfully uploaded events") "replay successfully initialized"
                else "replay could not be initialized, following error occurred: $returnMessage"
            }
            else -> "invalid message: $message"
        }
    }
}

@AutoService(FeatureProvider::class)
class EventReplayControllerProvider: FeatureProvider {
    override val registryName: String = "EventReplayController"
    override val registryClass: KClass<out Feature> = EventReplayControllerFeature::class
    override val featureDependencies: Set<KClass<out Feature>> = hashSetOf(EventRecordingFeature::class, EventInjectionFeature::class)

    override fun createFeature(manager: FeatureManager): Feature {
        return EventReplayControllerFeature(manager.getActiveFeature<EventRecordingFeature>(), manager.getActiveFeature<EventInjectionFeature>())
    }
}