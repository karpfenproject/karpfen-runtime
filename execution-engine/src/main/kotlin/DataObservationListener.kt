package io.karpfen

interface DataObservationListener {

    fun onChange(clientId: String, objectId: String)

}